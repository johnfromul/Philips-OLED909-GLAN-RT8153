package cz.codex.rtl8153diagnostic;

import java.util.*;
import java.io.ByteArrayOutputStream;

/** Ethernet/ARP/DHCP framing. TCP/IP remains in Android's network stack. */
public final class LanPackets {
    private LanPackets() {}
    public static final byte[] BROADCAST={(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255};
    public static int u16(byte[]b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
    public static int i32(byte[]b,int p){return (u16(b,p)<<16)|u16(b,p+2);}
    public static void w16(byte[]b,int p,int v){b[p]=(byte)(v>>>8);b[p+1]=(byte)v;}
    public static void w32(byte[]b,int p,int v){w16(b,p,v>>>16);w16(b,p+2,v);}
    public static byte[] ethernet(byte[]dst,byte[]src,int type,byte[]payload,int length){
        if(length<0 || length>payload.length)throw new IllegalArgumentException();
        byte[]b=new byte[Math.max(60,14+length)];System.arraycopy(dst,0,b,0,6);System.arraycopy(src,0,b,6,6);w16(b,12,type);System.arraycopy(payload,0,b,14,length);return b;
    }
    public static byte[] arp(byte[]mac,int own,int target,byte[]peer,boolean reply){
        byte[]p=new byte[28];w16(p,0,1);w16(p,2,0x800);p[4]=6;p[5]=4;w16(p,6,reply?2:1);
        System.arraycopy(mac,0,p,8,6);w32(p,14,own);if(peer!=null)System.arraycopy(peer,0,p,18,6);w32(p,24,target);
        return ethernet(reply?peer:BROADCAST,mac,0x806,p,p.length);
    }
    public static boolean validArp(byte[]b,int n){return n>=42 && n<=b.length && u16(b,12)==0x806 && u16(b,14)==1 && u16(b,16)==0x800 && b[18]==6 && b[19]==4 && (u16(b,20)==1||u16(b,20)==2);}
    public static byte[] dhcp(byte[]mac,int xid,int type,int requested,int server,int ciaddr){
        byte[]base=EthernetFrames.discover(mac,xid),boot=Arrays.copyOfRange(base,42,342);
        w32(boot,12,ciaddr);if(ciaddr!=0)w16(boot,10,0);
        Arrays.fill(boot,240,boot.length,(byte)0);ByteArrayOutputStream opts=new ByteArrayOutputStream();
        opts.write(53);opts.write(1);opts.write(type);
        opts.write(61);opts.write(7);opts.write(1);opts.write(mac,0,6);
        if(requested!=0){opts.write(50);opts.write(4);for(int i=3;i>=0;i--)opts.write(requested>>>(i*8));}
        if(server!=0){opts.write(54);opts.write(4);for(int i=3;i>=0;i--)opts.write(server>>>(i*8));}
        opts.write(55);opts.write(7);for(int x:new int[]{1,3,6,51,54,58,59})opts.write(x);opts.write(255);
        byte[]o=opts.toByteArray();System.arraycopy(o,0,boot,240,o.length);
        byte[]ip=new byte[20+8+boot.length];ip[0]=0x45;w16(ip,2,ip.length);ip[8]=64;ip[9]=17;w32(ip,12,ciaddr);w32(ip,16,-1);
        w16(ip,10,EthernetFrames.checksum(ip,0,20));w16(ip,20,68);w16(ip,22,67);w16(ip,24,8+boot.length);System.arraycopy(boot,0,ip,28,boot.length);
        return ethernet(BROADCAST,mac,0x800,ip,ip.length);
    }
    public static final class Dhcp {
        public int type,ip,server,mask,gateway;public long lease=3600;public List<Integer> dns=new ArrayList<>();
        public IpConfig config(){IpConfig c=new IpConfig();c.ip=ip;c.gateway=gateway;c.server=server;c.leaseSeconds=lease;
            c.prefix=IpConfig.prefix(IpConfig.text(mask));c.dns.addAll(dns);c.validate();return c;}
    }
    public static Dhcp parseDhcp(byte[]b,int n,byte[]mac,int xid){
        if(n>b.length || n<282 || u16(b,12)!=0x800 || (b[14]&240)!=64 || b[23]!=17)return null;
        int ihl=(b[14]&15)*4,udp=14+ihl;if(ihl<20 || udp+248>n || (u16(b,20)&0x3fff)!=0 || EthernetFrames.checksum(b,14,ihl)!=0)return null;
        int total=u16(b,16),ul=u16(b,udp+4);if(total>n-14||total<ihl+248||ul<248||ul>total-ihl||u16(b,udp)!=67||u16(b,udp+2)!=68)return null;
        int p=udp+8,end=udp+ul;if(b[p]!=2||b[p+1]!=1||b[p+2]!=6||i32(b,p+4)!=xid||i32(b,p+236)!=0x63825363)return null;
        for(int k=0;k<6;k++)if(b[p+28+k]!=mac[k])return null;
        Dhcp d=new Dhcp();d.ip=i32(b,p+16);
        for(int q=p+240;q<end;){int code=b[q++]&255;if(code==255)break;if(code==0)continue;if(q>=end)return null;
            int len=b[q++]&255;if(q+len>end)return null;
            if(code==53&&len==1)d.type=b[q]&255;
            if(code==1&&len==4)d.mask=i32(b,q);
            if(code==3&&len>=4)d.gateway=i32(b,q);
            if(code==6&&len%4==0)for(int k=0;k<len;k+=4)d.dns.add(i32(b,q+k));
            if(code==54&&len==4)d.server=i32(b,q);
            if(code==51&&len==4)d.lease=i32(b,q)&0xffffffffL;
            q+=len;
        }
        return d.type==0?null:d;
    }
}
