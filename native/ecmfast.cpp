#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/eventfd.h>
#include <sys/poll.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <array>
#include <vector>
#include <deque>
#include <unordered_map>
#include <atomic>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <string>
#include <ctime>

namespace {
// Slot ownership: USB -> completion thread -> RX queue -> TUN writer ->
// returned queue -> completion thread -> USB. TX slots move from the free
// pool to the TUN reader (or ARP/control), then USB, then back to the pool.
// A slot is never reused before its completion is reaped. RX stays bounded
// at 256 buffers; TX reserves eight buffers for ARP/DHCP. Neither full queue
// drops ordinary traffic: readers wait for the downstream stage instead.
// Stop joins both packet threads, cancels outstanding URBs, and keeps all
// buffers alive until the final USB descriptor/mapping has been closed.
constexpr int RX=256, TX=128, CAP=2048, RESERVED=8;
uint64_t nowNs(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;}
uint16_t rd16(const uint8_t*b){return uint16_t(b[0])<<8|b[1];}
uint32_t rd32(const uint8_t*b){return uint32_t(rd16(b))<<16|rd16(b+2);}
void wr16(uint8_t*b,uint16_t n){b[0]=n>>8;b[1]=n;}
void wr32(uint8_t*b,uint32_t n){wr16(b,n>>16);wr16(b+2,n);}
bool unicast(uint32_t n){unsigned a=n>>24;return a>0&&a<224&&a!=127;}
struct Slot {usbdevfs_urb*u=nullptr;alignas(16) uint8_t local[CAP]{};uint8_t*b=local;std::atomic<bool>busy{false};int length=0;};
struct Peer {std::array<uint8_t,6>mac;uint64_t time;};
struct Pending {std::vector<int>slots;uint64_t born,last;};
struct Context {
 int usb=-1,tun=-1,wake=-1,tunFlags=-1;uint8_t epIn,epOut,mac[6];uint32_t ip,mask,gateway;
 std::array<Slot,RX+TX>slots;std::vector<int>freeTx;std::deque<int>rxPending,rxReturned;
 std::unordered_map<uint32_t,Peer>peers;std::unordered_map<uint32_t,Pending>pending;
 std::mutex controlsMutex;std::deque<std::vector<uint8_t>>controls;
 std::mutex poolMutex,cacheMutex,rxMutex;std::condition_variable poolReady,rxReady;std::thread txThread,rxThread;
 std::atomic<bool>stop{false};
 void*dma=MAP_FAILED;size_t dmaSize=(RX+TX)*CAP;
 // RX frames/bytes, TX frames/bytes, malformed/unresolved drops, USB errors,
 // TX pool high-water, TUN backpressure events, USB completions, ARP queries,
 // USB DMA mapping active (a connection flag, not an accumulating counter),
 // RX handoff queue high-water and maximum RX completions per reap batch.
 std::array<std::atomic<uint64_t>,13>counts{};
 uint64_t now=0,nextTick=0;std::string error;
 Context(){for(auto&v:counts)v.store(0,std::memory_order_relaxed);}
 ~Context(){if(usb>=0)close(usb);if(dma!=MAP_FAILED)munmap(dma,dmaSize);if(tun>=0){if(tunFlags>=0)fcntl(tun,F_SETFL,tunFlags);close(tun);}if(wake>=0)close(wake);for(auto&s:slots)free(s.u);}
 void signal(){uint64_t one=1;if(wake>=0)write(wake,&one,sizeof(one));poolReady.notify_all();rxReady.notify_all();}
 bool setError(const std::string&s){if(!stop.exchange(true))error=s;signal();return false;}
 bool fail(const char*s){return setError(std::string(s)+": "+strerror(errno));}
 bool submit(int index){Slot&s=slots[index];auto*u=s.u;memset(u,0,sizeof(*u));u->type=USBDEVFS_URB_TYPE_BULK;
  u->endpoint=index<RX?epIn:epOut;u->buffer=s.b;u->buffer_length=index<RX?CAP:s.length;
  u->usercontext=&s;if(index>=RX)u->flags=USBDEVFS_URB_ZERO_PACKET;
  s.busy=true;if(ioctl(usb,USBDEVFS_SUBMITURB,u)<0){s.busy=false;return fail("USB submit");}return true;
 }
 int take(bool data=false){std::lock_guard<std::mutex>lock(poolMutex);if(freeTx.size()<=size_t(data?RESERVED:0))return -1;int i=freeTx.back();freeTx.pop_back();auto used=TX-freeTx.size();auto old=counts[6].load(std::memory_order_relaxed);if(used>old)counts[6]=used;return i;}
 void give(int i){{std::lock_guard<std::mutex>lock(poolMutex);freeTx.push_back(i);}poolReady.notify_one();}
 bool raw(const uint8_t*b,int n){if(n<14||n>1514)return false;int i=take();if(i<0)return false;
  auto&s=slots[i];s.length=n<60?60:n;memcpy(s.b,b,n);if(s.length>n)memset(s.b+n,0,s.length-n);return submit(i);
 }
 bool arp(uint32_t target,const uint8_t*dest=nullptr,uint32_t targetIp=0,bool reply=false){
  uint8_t b[60]{};if(dest)memcpy(b,dest,6);else memset(b,255,6);memcpy(b+6,mac,6);wr16(b+12,0x806);
  wr16(b+14,1);wr16(b+16,0x800);b[18]=6;b[19]=4;wr16(b+20,reply?2:1);memcpy(b+22,mac,6);wr32(b+28,ip);
  if(reply){memcpy(b+32,dest,6);wr32(b+38,targetIp);}else wr32(b+38,target);
  counts[9]++;return raw(b,60);
 }
 void drop(int i){give(i);counts[4]++;}
 bool sendIp(int i){auto&s=slots[i];auto*b=s.b;int len=s.length;
  if(len<20||(b[14]&0xf0)!=0x40||rd32(b+26)!=ip){drop(i);return true;}
  uint32_t dst=rd32(b+30);const uint8_t*dest=nullptr;static const uint8_t broadcast[6]={255,255,255,255,255,255};
  if(dst==0xffffffff||dst==(ip|~mask))dest=broadcast;
  else if((dst>>28)==14){drop(i);return true;}
  uint32_t next=(dst&mask)==(ip&mask)?dst:gateway;
  std::array<uint8_t,6>cached;bool query=false;
  if(!dest){uint64_t time=nowNs();{std::lock_guard<std::mutex>lock(cacheMutex);
    auto p=peers.find(next);if(p!=peers.end()&&time-p->second.time<60000000000ULL){cached=p->second.mac;dest=cached.data();}
    if(!dest){auto wait=pending.find(next);if(wait==pending.end()){pending.emplace(next,Pending{{i},time,time});query=true;}else wait->second.slots.push_back(i);}
   }if(!dest){if(query)arp(next);return true;}}
  memcpy(b,dest,6);memcpy(b+6,mac,6);wr16(b+12,0x800);s.length=14+len;
  if(s.length<60){memset(b+s.length,0,60-s.length);s.length=60;}return submit(i);
 }
 void receiveTun(){pthread_setname_np(pthread_self(),"ecm-rx");
  while(!stop){std::array<int,16>batch;int count=0,done=0;
   {std::unique_lock<std::mutex>lock(rxMutex);rxReady.wait(lock,[&]{return stop||!rxPending.empty();});if(stop)break;while(count<int(batch.size())&&!rxPending.empty()){batch[count++]=rxPending.front();rxPending.pop_front();}}
   for(int k=0;k<count&&!stop;k++){int i=batch[k];auto&s=slots[i];while(!stop){ssize_t n=write(tun,s.b+14,s.length);
    if(n==s.length){counts[0]++;counts[1]+=s.length;done++;break;}
    if(n<0&&errno==EINTR)continue;
    if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){counts[7]++;pollfd f{tun,POLLOUT,0};int ready=poll(&f,1,100);
     if(ready<0){if(errno==EINTR)continue;fail("TUN RX poll");break;}
     if(f.revents&(POLLERR|POLLHUP|POLLNVAL)){setError("TUN disconnected");break;}continue;}
    fail("TUN write");break;
   }}
   if(done){bool notify;{std::lock_guard<std::mutex>lock(rxMutex);notify=rxReturned.empty();for(int k=0;k<done;k++)rxReturned.push_back(batch[k]);}if(notify){uint64_t one=1;write(wake,&one,sizeof(one));}}
  }
 }
 bool received(int i,int n,JNIEnv*env,jobject owner,jmethodID callback){auto&s=slots[i];auto*b=s.b;
  if(n<14||n>CAP){counts[4]++;return submit(i);}int type=rd16(b+12);
  if(type==0x806&&n>=42&&rd16(b+14)==1&&rd16(b+16)==0x800&&b[18]==6&&b[19]==4){
   uint32_t sender=rd32(b+28),target=rd32(b+38);unsigned op=rd16(b+20);
   if((op==1||op==2)&&unicast(sender)&&(sender&mask)==(ip&mask)&&!(b[22]&1)&&!memcmp(b+6,b+22,6)){
    std::vector<int>indexes;{std::lock_guard<std::mutex>lock(cacheMutex);
     if(peers.size()>256)peers.clear();Peer p{};memcpy(p.mac.data(),b+22,6);p.time=now;peers[sender]=p;
     auto wait=pending.find(sender);if(wait!=pending.end()){indexes=std::move(wait->second.slots);pending.erase(wait);}}
    for(int j:indexes)if(!sendIp(j))return false;
   }
   if(op==1&&target==ip)arp(0,b+22,sender,true);return submit(i);
  }
  if(type!=0x800||n<34||(b[14]&0xf0)!=0x40)return submit(i);
  int ihl=(b[14]&15)*4,len=rd16(b+16);if(ihl<20||len<ihl||len>n-14)return submit(i);
  if(b[23]==17&&len>=ihl+8){int udp=14+ihl;if(rd16(b+udp)==67&&rd16(b+udp+2)==68){
    jbyteArray frame=env->NewByteArray(n);if(!frame)return setError("DHCP callback allocation failed");
    env->SetByteArrayRegion(frame,0,n,reinterpret_cast<jbyte*>(b));env->CallVoidMethod(owner,callback,frame);env->DeleteLocalRef(frame);
    if(env->ExceptionCheck()){env->ExceptionClear();return setError("DHCP handler rejected configuration");}
    return submit(i);
  }}
  if(rd32(b+30)!=ip)return submit(i);s.length=len;
  bool notify;{std::lock_guard<std::mutex>lock(rxMutex);notify=rxPending.empty();rxPending.push_back(i);if(rxPending.size()>counts[11])counts[11]=rxPending.size();}if(notify)rxReady.notify_one();return true;
 }
 void tick(){
  std::vector<int>expired;std::vector<uint32_t>queries;{std::lock_guard<std::mutex>lock(cacheMutex);
   uint64_t time=nowNs();for(auto it=pending.begin();it!=pending.end();){auto&p=it->second;
    if(time-p.born>3000000000ULL){for(int i:p.slots)expired.push_back(i);it=pending.erase(it);}
    else{if(time-p.last>1000000000ULL){queries.push_back(it->first);p.last=time;}++it;}
   }}for(int i:expired)drop(i);for(auto ip:queries)arp(ip);
 }
 void transmit(){pthread_setname_np(pthread_self(),"ecm-tx");
  while(!stop){int i=take(true);if(i<0){std::unique_lock<std::mutex>lock(poolMutex);poolReady.wait_for(lock,std::chrono::milliseconds(100),[&]{return stop||freeTx.size()>RESERVED;});continue;}
   auto&s=slots[i];int n=read(tun,s.b+14,1500);
   if(n>0){s.length=n;sendIp(i);}else{int reason=errno;give(i);if(n==0){setError("TUN closed");break;}if(reason==EINTR)continue;
    if(reason!=EAGAIN&&reason!=EWOULDBLOCK){setError(std::string("TUN read: ")+strerror(reason));break;}
    pollfd f{tun,POLLIN,0};int ready=poll(&f,1,100);if(ready<0){if(errno==EINTR)continue;fail("TUN TX poll");break;}
    if(f.revents&(POLLERR|POLLHUP|POLLNVAL)){setError("TUN disconnected");break;}
   }
  }
 }
 void cancelAll(){for(auto&s:slots)if(s.busy)ioctl(usb,USBDEVFS_DISCARDURB,s.u);
  // Keep buffers owned until release closes the last duplicate USB descriptor.
  uint64_t end=nowNs()+2000000000ULL;
  while(nowNs()<end){bool busy=false;for(auto&s:slots)if(s.busy){busy=true;break;}if(!busy)break;
   void*p=nullptr;if(ioctl(usb,USBDEVFS_REAPURBNDELAY,&p)==0){auto*u=static_cast<usbdevfs_urb*>(p);auto*s=static_cast<Slot*>(u->usercontext);if(s)s->busy=false;}
   else if(errno==ENODEV)break;else{pollfd f{usb,POLLOUT,0};poll(&f,1,10);}
  }
 }
 bool recycleRx(){std::array<int,RX>ready;int count=0;
  {std::lock_guard<std::mutex>lock(rxMutex);while(!rxReturned.empty()){ready[count++]=rxReturned.front();rxReturned.pop_front();}}
  for(int k=0;k<count;k++)if(!submit(ready[k]))return false;return true;
 }
 void run(JNIEnv*env,jobject owner){pthread_setname_np(pthread_self(),"ecm-native");now=nowNs();nextTick=now+250000000;
  jclass cls=env->GetObjectClass(owner);jmethodID callback=env->GetMethodID(cls,"onNativeDhcp","([B)V");env->DeleteLocalRef(cls);
  if(!callback){env->ExceptionClear();error="DHCP callback missing";return;}
  for(int i=0;i<RX;i++)if(!submit(i)){cancelAll();return;}
  txThread=std::thread([this]{transmit();});
  rxThread=std::thread([this]{receiveTun();});
  while(!stop){
   pollfd f[3]={{usb,POLLOUT,0},{tun,0,0},{wake,POLLIN,0}};
   int ready=poll(f,3,250);now=nowNs();if(ready<0){if(errno==EINTR)continue;fail("poll");break;}
   if((f[0].revents|f[1].revents)&(POLLERR|POLLHUP|POLLNVAL)){setError("USB or TUN disconnected");break;}
   if(f[2].revents&POLLIN){uint64_t value;read(wake,&value,sizeof(value));}
   if(stop)break;
   if(!recycleRx())break;
   unsigned rxBatch=0;for(int k=0;k<512&&!stop;k++){
    // Refill while draining a burst: waiting for all 512 completions can
    // leave no IN request queued despite downstream buffers being free.
    if((k&31)==31&&!recycleRx())break;
    void*p=nullptr;if(ioctl(usb,USBDEVFS_REAPURBNDELAY,&p)<0){if(errno==EAGAIN)break;if(errno==EINTR)continue;fail("USB reap");break;}
    auto*u=static_cast<usbdevfs_urb*>(p);auto*s=static_cast<Slot*>(u->usercontext);int index=int(s-slots.data());
    if(index<0||index>=RX+TX){setError("Unexpected USB request");break;}s->busy=false;counts[8]++;
    if(u->status){counts[5]++;if(!stop)setError("USB completion status "+std::to_string(u->status));break;}
    if(index<RX){rxBatch++;if(u->actual_length>0)received(index,u->actual_length,env,owner,callback);else submit(index);}
    else{if(u->actual_length!=s->length){setError("Short USB write");break;}counts[2]++;counts[3]+=s->length;give(index);}
   }
   if(rxBatch>counts[12])counts[12]=rxBatch;
   {
    std::lock_guard<std::mutex>lock(controlsMutex);
    while(!controls.empty()){auto&b=controls.front();if(!raw(b.data(),b.size()))break;controls.pop_front();}
   }
   if(now>=nextTick){tick();nextTick=now+250000000;}
  }
  stop=true;signal();if(txThread.joinable())txThread.join();if(rxThread.joinable())rxThread.join();cancelAll();
 }
};
Context*get(jlong h){return reinterpret_cast<Context*>(static_cast<uintptr_t>(h));}
void wake(Context*c){uint64_t one=1;if(c->wake>=0)write(c->wake,&one,sizeof(one));}
}
extern "C" JNIEXPORT jlong JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_create(JNIEnv*e,jclass,jint usb,jint tun,jint in,jint out,jbyteArray mac,jint ip,jint prefix,jint gateway){
 if(prefix<1||prefix>30||e->GetArrayLength(mac)!=6){e->ThrowNew(e->FindClass("java/io/IOException"),"Invalid native configuration");return 0;}
 auto*c=new Context();c->usb=dup(usb);c->tun=dup(tun);c->wake=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC);
 c->epIn=in;c->epOut=out;c->ip=uint32_t(ip);c->mask=0xffffffffU<<(32-prefix);c->gateway=uint32_t(gateway);
 e->GetByteArrayRegion(mac,0,6,reinterpret_cast<jbyte*>(c->mac));
 if(c->usb<0||c->tun<0||c->wake<0){delete c;e->ThrowNew(e->FindClass("java/io/IOException"),"Cannot duplicate USB/TUN descriptors");return 0;}
 c->tunFlags=fcntl(c->tun,F_GETFL);if(c->tunFlags<0||fcntl(c->tun,F_SETFL,c->tunFlags|O_NONBLOCK)<0){delete c;e->ThrowNew(e->FindClass("java/io/IOException"),"Cannot make TUN nonblocking");return 0;}
 unsigned caps=0;if(ioctl(c->usb,USBDEVFS_GET_CAPABILITIES,&caps)==0&&(caps&USBDEVFS_CAP_MMAP)){
  c->dma=mmap(nullptr,c->dmaSize,PROT_READ|PROT_WRITE,MAP_SHARED,c->usb,0);
  if(c->dma!=MAP_FAILED){for(int i=0;i<RX+TX;i++)c->slots[i].b=static_cast<uint8_t*>(c->dma)+i*CAP;c->counts[10]=1;}
 }
 for(int i=0;i<RX+TX;i++){c->slots[i].u=static_cast<usbdevfs_urb*>(calloc(1,sizeof(usbdevfs_urb)));if(!c->slots[i].u){delete c;e->ThrowNew(e->FindClass("java/io/IOException"),"USB buffer allocation failed");return 0;}if(i>=RX)c->freeTx.push_back(i);}
 return static_cast<jlong>(reinterpret_cast<uintptr_t>(c));
}
extern "C" JNIEXPORT jstring JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_run(JNIEnv*e,jclass,jlong h,jobject owner){auto*c=get(h);c->run(e,owner);return e->NewStringUTF(c->error.c_str());}
extern "C" JNIEXPORT void JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_stop(JNIEnv*,jclass,jlong h){auto*c=get(h);c->stop=true;wake(c);}
extern "C" JNIEXPORT jboolean JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_send(JNIEnv*e,jclass,jlong h,jbyteArray frame){auto*c=get(h);int n=e->GetArrayLength(frame);if(n<14||n>1514)return false;
 std::lock_guard<std::mutex>lock(c->controlsMutex);if(c->controls.size()>=16)return false;std::vector<uint8_t>b(n);e->GetByteArrayRegion(frame,0,n,reinterpret_cast<jbyte*>(b.data()));c->controls.push_back(std::move(b));wake(c);return true;}
extern "C" JNIEXPORT jlongArray JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_stats(JNIEnv*e,jclass,jlong h){auto*c=get(h);jlong b[13];for(int i=0;i<13;i++)b[i]=c->counts[i].load(std::memory_order_relaxed);auto a=e->NewLongArray(13);e->SetLongArrayRegion(a,0,13,b);return a;}
extern "C" JNIEXPORT void JNICALL Java_cz_codex_rtl8153diagnostic_NativeBridge_release(JNIEnv*,jclass,jlong h){delete get(h);}
