package cz.codex.rtl8153diagnostic;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Routes IPv4 packets between Android's TUN and a local ECM Ethernet adapter. */
public final class EthernetVpnService extends VpnService {
    public static volatile boolean active,connected,needsUserAction;
    private static volatile String statusSource="Ethernet odpojen";
    private static volatile IpConfig currentConfig;
    private static volatile boolean nativeConnection;
    public static String statusText(){
        IpConfig c=currentConfig;
        if(connected&&c!=null)return L.t(nativeConnection?"Připojeno (nativní USB): ":"Připojeno: ")+c.summary();
        return L.t(statusSource);
    }
    public static volatile String status=L.t("Ethernet odpojen");
    private static final StringBuilder history=new StringBuilder();
    private volatile boolean cancel;
    private volatile boolean destroyed;
    private final Object recoveryLock=new Object();
    private String preferredDevice;
    private volatile String runningDevice;
    private boolean receiverRegistered;
    private final BroadcastReceiver recoveryReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){
                UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(d!=null&&d.getDeviceName().equals(runningDevice))disconnectSession();
            }
            synchronized(recoveryLock){recoveryLock.notifyAll();}
        }
    };
    private volatile ParcelFileDescriptor tun;
    private Thread worker,tunReader;
    private final ArrayBlockingQueue<byte[]> outgoing=new ArrayBlockingQueue<>(256);
    private long txPackets,rxPackets,dropped;
    private IpConfig config;
    private final Map<Integer,Peer> peers=new HashMap<>();
    private final Map<Integer,List<byte[]>> waiting=new HashMap<>();
    private final Map<Integer,Long> arpSent=new HashMap<>();
    private long leaseStarted,nextRenew,leaseEnd;
    private int dhcpXid;
    private boolean renewPending;
    private EcmLink link;
    private PerfStats metrics;
    private final Object nativeLock=new Object(),leaseLock=new Object();
    private long nativeHandle;
    private Thread nativeControl;
    private static final class Peer {byte[]mac;long time;Peer(byte[]m){mac=m;time=SystemClock.elapsedRealtime();}}
    public static synchronized String logText(){return history.toString();}
    private void log(String s){
        synchronized(EthernetVpnService.class){history.append(new Date()).append(" · ").append(s).append('\n');
            if(history.length()>60000)history.delete(0,history.indexOf("\n",history.length()-50000)+1);}
        persist();
    }
    private synchronized void persist(){
        try{File d=getExternalFilesDir(null);if(d!=null)try(FileOutputStream o=new FileOutputStream(new File(d,"usb-ethernet-session.txt"))){o.write((status+"\n"+logText()).getBytes(StandardCharsets.UTF_8));}}catch(IOException ignored){}
    }
    private void state(String s){statusSource=s;status=L.t(s);log(status);}
    @Override public void onCreate(){
        super.onCreate();
        IntentFilter f=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(recoveryReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(recoveryReceiver,f);
        receiverRegistered=true;
    }
    private boolean wanted(){return !destroyed&&RecoveryReceiver.isEnabled(this);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?null:intent.getAction();
        if("STOP".equals(action)){
            RecoveryReceiver.setEnabled(this,false);disconnectSession();
            synchronized(recoveryLock){recoveryLock.notifyAll();}
            if(worker==null){active=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
            return START_NOT_STICKY;
        }
        if("CHECK".equals(action)){
            if(active&&tun!=null)new Thread(this::checkInternet,"ethernet-check").start();
            else if(!active)stopSelf();
            return wanted()?START_STICKY:START_NOT_STICKY;
        }
        // Only an explicit user connection enables persistence. Boot/update/null starts honor saved intent.
        if(intent!=null&&intent.hasExtra("device")){
            preferredDevice=intent.getStringExtra("device");RecoveryReceiver.setEnabled(this,true);
        }
        if(!wanted()){stopSelf();return START_NOT_STICKY;}
        showNotification(L.t("Obnovuji USB Ethernet…"));
        if(worker==null){
            active=true;
            worker=new Thread(this::supervise,"ecm-supervisor");worker.start();
        }else synchronized(recoveryLock){recoveryLock.notifyAll();}
        return START_STICKY;
    }
    private void showNotification(String message){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("ethernet","USB Ethernet",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,EthernetVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,"ethernet").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("RTL8153 USB Ethernet").setContentText(message)
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,L.t("Odpojit"),stop).build()).build();
        startForeground(8153,note);
    }
    private void pauseRecovery(long ms){
        synchronized(recoveryLock){if(wanted())try{recoveryLock.wait(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private void waitingState(String message){needsUserAction=true;if(!L.t(message).equals(status)){state(message);showNotification(L.t(message));}}
    private void supervise(){
        int failures=0;
        try{
            while(wanted()){
                if(VpnService.prepare(this)!=null){waitingState("Pro obnovení potvrď VPN souhlas v aplikaci.");pauseRecovery(5000);continue;}
                UsbManager usb=(UsbManager)getSystemService(USB_SERVICE);
                UsbDevice d=null;
                for(UsbDevice candidate:usb.getDeviceList().values()){
                    if(!DescriptorUtil.isTarget(candidate.getVendorId(),candidate.getProductId()))continue;
                    if(d==null||candidate.getDeviceName().equals(preferredDevice)||(!usb.hasPermission(d)&&usb.hasPermission(candidate)))d=candidate;
                }
                if(d==null){waitingState("Čekám na USB adaptér; automatické obnovení je zapnuté.");failures=0;pauseRecovery(5000);continue;}
                if(!usb.hasPermission(d)){waitingState("Čekám na USB oprávnění; otevři aplikaci a potvrď adaptér.");pauseRecovery(5000);continue;}
                cancel=false;needsUserAction=false;runningDevice=d.getDeviceName();
                outgoing.clear();peers.clear();waiting.clear();arpSent.clear();
                txPackets=rxPackets=dropped=0;nativeControl=null;tunReader=null;metrics=null;
                long began=SystemClock.elapsedRealtime();
                runConnection(runningDevice);
                runningDevice=null;
                if(!wanted())break;
                failures=SystemClock.elapsedRealtime()-began>30000?0:Math.min(failures+1,5);
                long delay=Math.min(30000L,1000L<<failures);
                waitingState(L.t("Obnovím spojení za ")+(delay/1000)+" s. "+status);
                pauseRecovery(delay);
            }
        }finally{
            active=false;connected=false;needsUserAction=false;statusSource="Ethernet odpojen";status=L.t(statusSource);persist();
            if(!destroyed){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        }
    }
    private void ensure()throws IOException{if(cancel||Thread.currentThread().isInterrupted())throw new IOException(L.t("Spojení zrušeno."));}
    private void runConnection(String device){
        FileInputStream input=null;FileOutputStream output=null;
        try{
            state("Připojuji USB Ethernet…");
            UsbManager usb=(UsbManager)getSystemService(USB_SERVICE);UsbDevice d=usb.getDeviceList().get(device);
            if(d==null||!DescriptorUtil.isTarget(d.getVendorId(),d.getProductId())||!usb.hasPermission(d))throw new IOException(L.t("Adaptér není připojen nebo nemá USB oprávnění."));
            metrics=new PerfStats(getExternalFilesDir(null));link=new EcmLink(this::log,metrics);link.open(usb,d);ensure();
            android.content.SharedPreferences pref=getSharedPreferences("ethernet",MODE_PRIVATE);
            if(pref.getBoolean("dhcp",true)){config=acquire();setLease();}
            else config=IpConfig.manual(pref.getString("ip",""),pref.getString("prefix","24"),pref.getString("gateway",""),pref.getString("dns",""));
            ensure();state(L.t("Kontroluji dostupnost adresy ")+IpConfig.text(config.ip)+"…");
            checkConflict();ensure();
            pref.edit().putString("last_ip",IpConfig.text(config.ip)).putString("last_gateway",IpConfig.text(config.gateway)).putString("last_dns",config.dnsText()).putInt("last_prefix",config.prefix).apply();
            Builder b=new Builder().setSession("RTL8153 USB Ethernet").setMtu(1500).setBlocking(true)
                .addAddress(IpConfig.text(config.ip),32).addRoute("0.0.0.0",0);
            if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
            for(int dns:config.dns)b.addDnsServer(IpConfig.text(dns));
            // Leave IPv6 blocked: this bridge implements IPv4 only.
            tun=b.establish();if(tun==null)throw new IOException(L.t("Android nevytvořil VPN rozhraní; zkontroluj VPN souhlas."));
            input=new FileInputStream(tun.getFileDescriptor());output=new FileOutputStream(tun.getFileDescriptor());
            metrics.begin();currentConfig=config;connected=true;nativeConnection=pref.getBoolean("native_fast",true);
            if(pref.getBoolean("native_fast",true)){
                runNative();
                return;
            }
            final FileInputStream readStream=input;
            tunReader=new Thread(()->readTun(readStream),"tun-to-ethernet");tunReader.start();
            state(L.t("Připojeno: ")+config.summary());
            log(L.t("VPN rozhraní vytvořeno. IPv4 směrováno přes USB; IPv6 blokováno. VPN zde nešifruje místní ethernet."));
            link.send(LanPackets.arp(link.mac,config.ip,config.ip,null,false));
            new Thread(this::checkInternet,"ethernet-initial-check").start();
            byte[]buffer=new byte[16384];long stats=SystemClock.elapsedRealtime(),usbCheck=stats;
            while(!cancel){
                ensure();
                for(int k=0;k<32;k++){byte[]packet=outgoing.poll();if(packet==null)break;forward(packet);}
                int n=link.receive(buffer,20);if(n>0)receive(buffer,n,output);
                flushWaiting();renewLease();
                metrics.maybeWrite(false);
                if(SystemClock.elapsedRealtime()-stats>10000){stats=SystemClock.elapsedRealtime();log(L.t("Pakety TUN → Ethernet: ")+txPackets+L.t(", Ethernet → TUN: ")+rxPackets+L.t(", zahozeno: ")+dropped);}
                if(SystemClock.elapsedRealtime()-usbCheck>2000){usbCheck=SystemClock.elapsedRealtime();if(!usb.getDeviceList().containsKey(device))throw new IOException(L.t("USB adaptér byl odpojen."));}
            }
        }catch(Exception e){state(cancel?L.t("Ethernet odpojen"):L.t("Chyba spojení: ")+e.getMessage());}
        finally{
            cancel=true;connected=false;currentConfig=null;ParcelFileDescriptor t=tun;tun=null;
            if(nativeControl!=null){nativeControl.interrupt();try{nativeControl.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            try{if(t!=null)t.close();}catch(IOException ignored){}
            try{if(input!=null)input.close();}catch(IOException ignored){}
            try{if(output!=null)output.close();}catch(IOException ignored){}
            if(tunReader!=null){tunReader.interrupt();try{tunReader.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(metrics!=null)metrics.maybeWrite(true);
            if(link!=null){link.close();link=null;}
            synchronized(nativeLock){if(nativeHandle!=0){NativeBridge.release(nativeHandle);nativeHandle=0;}}
            persist();
        }
    }
    private void readTun(FileInputStream in){
        try{byte[]b=new byte[1600];android.system.StructPollfd poll=new android.system.StructPollfd();poll.fd=in.getFD();poll.events=(short)android.system.OsConstants.POLLIN;
            while(!cancel){if(android.system.Os.poll(new android.system.StructPollfd[]{poll},500)==0)continue;int n=in.read(b);if(n<0)break;
                if(n>0){metrics.readerPackets++;metrics.readerBytes+=n;
                    if(!outgoing.offer(Arrays.copyOf(b,n))){dropped++;metrics.queueFullDrops++;}
                    int depth=outgoing.size();if(depth>metrics.queueHighWater)metrics.queueHighWater=depth;
                }}}
        catch(Exception e){if(!cancel){log(L.t("Čtení VPN rozhraní selhalo: ")+e);cancel=true;}}
    }
    private LanPackets.Dhcp exchange(int type,int requested,int server,int ci,int expected)throws IOException{
        byte[]buf=new byte[16384];long deadline=SystemClock.elapsedRealtime()+15000,next=0;
        while(SystemClock.elapsedRealtime()<deadline){ensure();long now=SystemClock.elapsedRealtime();
            if(now>=next){link.send(LanPackets.dhcp(link.mac,dhcpXid,type,requested,server,ci));next=now+3500;}
            int n=link.receive(buf,200);if(n<=0)continue;
            LanPackets.Dhcp a=LanPackets.parseDhcp(buf,n,link.mac,dhcpXid);if(a!=null){if(a.type==6)throw new IOException(L.t("DHCP server odmítl adresu (NAK)."));if(a.type==expected)return a;}
        }
        throw new IOException(L.t("DHCP neodpovědělo do 15 sekund."));
    }
    private IpConfig acquire()throws IOException{
        state("Získávám IPv4 konfiguraci z DHCP…");dhcpXid=new SecureRandom().nextInt();
        LanPackets.Dhcp offer=exchange(1,0,0,0,2);
        if(!IpConfig.unicast(offer.ip)||!IpConfig.unicast(offer.server))throw new IOException(L.t("DHCP nabídka nemá platnou IP/server."));
        LanPackets.Dhcp ack=exchange(3,offer.ip,offer.server,0,5);
        if(ack.ip!=offer.ip||ack.server!=offer.server)throw new IOException(L.t("DHCP ACK nesouhlasí s vybraným serverem/adresou."));
        IpConfig c=ack.config();if(c.leaseSeconds<60)throw new IOException(L.t("DHCP lease je příliš krátký (<60 s)."));
        log("DHCP ACK: "+c.summary()+L.t(" · lease ")+c.leaseSeconds+" s");return c;
    }
    private void checkConflict()throws IOException{
        byte[]b=new byte[16384];long end=SystemClock.elapsedRealtime()+3000,next=0;int probes=0;
        while(SystemClock.elapsedRealtime()<end){ensure();long now=SystemClock.elapsedRealtime();
            if(now>=next&&probes<3){link.send(LanPackets.arp(link.mac,0,config.ip,null,false));probes++;next=now+1000;}
            int n=link.receive(b,150);
            if(n>0&&LanPackets.validArp(b,n)){
                boolean other=false;for(int k=0;k<6;k++)if(b[22+k]!=link.mac[k])other=true;
                if(other&&(LanPackets.i32(b,28)==config.ip||(LanPackets.i32(b,28)==0&&LanPackets.i32(b,38)==config.ip)))throw new IOException(L.t("Konflikt IP adresy: ")+IpConfig.text(config.ip)+L.t(" již používá jiné zařízení."));
            }
        }
        log(L.t("ARP kontrola adresy: žádný konflikt nezjištěn."));
    }
    private void setLease(){leaseStarted=SystemClock.elapsedRealtime();long ms=Math.min(config.leaseSeconds,0x7fffffffL)*1000L;nextRenew=leaseStarted+ms/2;leaseEnd=leaseStarted+ms;renewPending=false;}
    private void renewLease()throws IOException{
        if(!config.dhcp)return;long now=SystemClock.elapsedRealtime();
        if(now>=leaseEnd)throw new IOException(L.t("Platnost DHCP adresy vypršela; připoj znovu."));
        if(now>=nextRenew){if(!renewPending){dhcpXid=new SecureRandom().nextInt();renewPending=true;}
            byte[]frame=LanPackets.dhcp(link.mac,dhcpXid,3,0,0,config.ip);
            synchronized(nativeLock){if(nativeHandle!=0){if(!NativeBridge.send(nativeHandle,frame))throw new IOException(L.t("Fronta řídicích požadavků je plná."));}else link.send(frame);}
            nextRenew=now+15000;log(L.t("Odeslána žádost o obnovu DHCP adresy."));}
    }
    private void forward(byte[]packet)throws IOException{
        if(packet.length<20||(packet[0]&240)!=64||packet.length>1500||LanPackets.i32(packet,12)!=config.ip){dropped++;return;}
        int dest=LanPackets.i32(packet,16);
        if(dest==-1||dest==(config.ip|~config.mask())){sendIp(packet,LanPackets.BROADCAST);return;}
        if((dest>>>28)==14){dropped++;return;}
        int next=config.local(dest)?dest:config.gateway;Peer p=peers.get(next);long now=SystemClock.elapsedRealtime();
        if(p!=null&&now-p.time<60000){sendIp(packet,p.mac);return;}
        int count=0;for(List<byte[]>q:waiting.values())count+=q.size();
        if(count>=128){dropped++;return;}
        waiting.computeIfAbsent(next,k->new ArrayList<>()).add(packet);
        if(!arpSent.containsKey(next)){arpSent.put(next,now);link.send(LanPackets.arp(link.mac,config.ip,next,null,false));}
    }
    private void sendIp(byte[]p,byte[]mac)throws IOException{link.send(LanPackets.ethernet(mac,link.mac,0x800,p,p.length));txPackets++;}
    private void flushWaiting()throws IOException{
        long now=SystemClock.elapsedRealtime();Iterator<Map.Entry<Integer,List<byte[]>>> it=waiting.entrySet().iterator();
        while(it.hasNext()){Map.Entry<Integer,List<byte[]>>e=it.next();Peer p=peers.get(e.getKey());
            if(p!=null){for(byte[]b:e.getValue())sendIp(b,p.mac);it.remove();arpSent.remove(e.getKey());}
            else if(now-arpSent.get(e.getKey())>3000){dropped+=e.getValue().size();it.remove();arpSent.remove(e.getKey());}
        }
    }
    private void receive(byte[]b,int n,FileOutputStream tunOut)throws IOException{
        if(LanPackets.validArp(b,n)){
            int sender=LanPackets.i32(b,28),target=LanPackets.i32(b,38);byte[]mac=Arrays.copyOfRange(b,22,28);
            if(IpConfig.unicast(sender)&&config.local(sender)&&(mac[0]&1)==0){if(peers.size()>256)peers.clear();peers.put(sender,new Peer(mac));}
            if(LanPackets.u16(b,20)==1&&target==config.ip)link.send(LanPackets.arp(link.mac,config.ip,sender,mac,true));
            return;
        }
        if(config.dhcp&&renewPending){LanPackets.Dhcp a=LanPackets.parseDhcp(b,n,link.mac,dhcpXid);
            if(a!=null&&a.server==config.server){if(a.type==6)throw new IOException(L.t("DHCP server odvolal adresu."));
                if(a.type==5){IpConfig updated=a.config();if(updated.ip!=config.ip||updated.gateway!=config.gateway||updated.prefix!=config.prefix||!updated.dns.equals(config.dns))throw new IOException(L.t("DHCP změnilo konfiguraci; připoj znovu."));
                    config.leaseSeconds=updated.leaseSeconds;setLease();log(L.t("DHCP lease obnoven."));}return;}}
        if(n<34||LanPackets.u16(b,12)!=0x800||(b[14]&240)!=64)return;
        int len=LanPackets.u16(b,16),ihl=(b[14]&15)*4,dest=LanPackets.i32(b,30);
        if(len<ihl||ihl<20||len>n-14||dest!=config.ip)return;
        long beginWrite=System.nanoTime();tunOut.write(b,14,len);metrics.tunWrite(len,System.nanoTime()-beginWrite);rxPackets++;
    }
    private void checkInternet(){
        try{
            Network network=null;ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            long end=SystemClock.elapsedRealtime()+8000;
            while(!cancel&&SystemClock.elapsedRealtime()<end&&network==null){Network n=cm.getActiveNetwork();NetworkCapabilities cap=cm.getNetworkCapabilities(n);
                if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))network=n;
                if(network==null)Thread.sleep(200);}
            if(network==null)throw new IOException(L.t("VPN se dosud nestala výchozí sítí aplikace; zkus ověření znovu."));
            Network defaultNetwork=cm.getActiveNetwork();NetworkCapabilities defaultCap=cm.getNetworkCapabilities(defaultNetwork);
            log(L.t("Výchozí síť aplikace: ")+(defaultCap!=null&&defaultCap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":L.t("jiná nebo nedostupná"))+".");
            InetAddress[]addresses=network.getAllByName("connectivitycheck.gstatic.com");log(L.t("DNS přes VPN: OK (")+addresses.length+L.t(" adres)."));
            HttpURLConnection c=(HttpURLConnection)network.openConnection(new URL("https://connectivitycheck.gstatic.com/generate_204"));
            try{c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);int code=c.getResponseCode();
                log(L.t("HTTPS přes VPN: HTTP ")+code+(code==204?L.t(" — internet ověřen."):L.t(" — neočekávaná odpověď.")));}finally{c.disconnect();}
        }catch(Exception e){log(L.t("Ověření internetu neuspělo: ")+e.getMessage());}
    }
    private void disconnectSession(){
        cancel=true;
        synchronized(nativeLock){if(nativeHandle!=0)NativeBridge.stop(nativeHandle);}
        ParcelFileDescriptor t=tun;try{if(t!=null)t.close();}catch(IOException ignored){}
    }
    @Override public void onRevoke(){
        RecoveryReceiver.setEnabled(this,false);disconnectSession();
        synchronized(recoveryLock){recoveryLock.notifyAll();}
        super.onRevoke();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);status=statusText();if(active)showNotification(statusText());
    }
    @Override public void onDestroy(){
        destroyed=true;disconnectSession();
        synchronized(recoveryLock){recoveryLock.notifyAll();}
        if(receiverRegistered){unregisterReceiver(recoveryReceiver);receiverRegistered=false;}
        super.onDestroy();
    }

    private void runNative()throws IOException{
        synchronized(nativeLock){nativeHandle=NativeBridge.create(link.fileDescriptor(),tun.getFd(),link.inputEndpoint(),link.outputEndpoint(),link.mac,config.ip,config.prefix,config.gateway);}
        state(L.t("Připojeno (nativní USB): ")+config.summary());
        log(L.t("Nativní cesta: tři přenosová vlákna, 256 asynchronních USB RX požadavků, 128 TX bufferů, řízení toku bez zahazování při zaplnění."));
        nativeControl=new Thread(()->{
            try{while(!cancel){
                UsbManager usb=(UsbManager)getSystemService(USB_SERVICE);
                if(!usb.getDeviceList().containsKey(runningDevice))throw new IOException(L.t("USB adaptér byl odpojen."));
                synchronized(leaseLock){renewLease();}
                long[]stats;synchronized(nativeLock){if(nativeHandle==0)return;stats=NativeBridge.stats(nativeHandle);}
                org.json.JSONObject j=new org.json.JSONObject();j.put("uptime_seconds",SystemClock.elapsedRealtimeNanos()/1e9);
                String[]names={"rx_ip_packets","rx_ip_bytes","tx_ethernet_packets","tx_ethernet_bytes","invalid_or_unresolved_drops","usb_errors","tx_pool_high_water","tun_backpressure_events","usb_completions","arp_requests","usb_mmap","rx_queue_high_water","rx_batch_high_water"};
                for(int k=0;k<names.length;k++)j.put(names[k],stats[k]);
                File dir=getExternalFilesDir(null);if(dir!=null)try(FileOutputStream f=new FileOutputStream(new File(dir,"usb-ethernet-native.jsonl"),true)){f.write((j.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
                Thread.sleep(2000);
            }}catch(InterruptedException ignored){}catch(Exception e){log(L.t("Řízení nativního spojení selhalo: ")+e);cancel=true;synchronized(nativeLock){if(nativeHandle!=0)NativeBridge.stop(nativeHandle);}}
        },"ecm-control");nativeControl.start();
        new Thread(this::checkInternet,"ethernet-initial-check").start();
        String error=NativeBridge.run(nativeHandle,this);
        if(!error.isEmpty()&&!cancel)throw new IOException(L.error(error));
    }
    // Called only for DHCP server replies; ordinary frames never cross JNI.
    private void onNativeDhcp(byte[]frame)throws IOException{
        synchronized(leaseLock){
            if(!config.dhcp||!renewPending)return;
            LanPackets.Dhcp a=LanPackets.parseDhcp(frame,frame.length,link.mac,dhcpXid);
            if(a==null||a.server!=config.server)return;
            if(a.type==6)throw new IOException(L.t("DHCP server odvolal adresu."));
            if(a.type==5){IpConfig updated=a.config();
                if(updated.ip!=config.ip||updated.gateway!=config.gateway||updated.prefix!=config.prefix||!updated.dns.equals(config.dns))throw new IOException(L.t("DHCP změnilo konfiguraci; připoj znovu."));
                config.leaseSeconds=updated.leaseSeconds;setLease();log(L.t("DHCP lease obnoven."));
            }
        }
    }
}
