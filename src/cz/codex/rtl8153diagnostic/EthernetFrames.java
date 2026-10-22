package cz.codex.rtl8153diagnostic;

import java.util.Arrays;

/** Minimal Ethernet/IPv4/UDP DHCP probe; never requests or installs a lease. */
public final class EthernetFrames {
    private EthernetFrames() {}
    private static int u16(byte[] b,int p) { return ((b[p]&255)<<8)|(b[p+1]&255); }
    private static void w16(byte[] b,int p,int n) { b[p]=(byte)(n>>>8); b[p+1]=(byte)n; }
    private static void w32(byte[] b,int p,int n) { for(int i=0;i<4;i++) b[p+i]=(byte)(n>>>(24-8*i)); }
    public static int checksum(byte[] b,int p,int n) {
        long sum=0; while(n>1) { sum+=u16(b,p); p+=2; n-=2; }
        if(n>0) sum+=(b[p]&255)<<8;
        while((sum>>>16)!=0) sum=(sum&65535)+(sum>>>16);
        return (int)(~sum)&65535;
    }
    public static byte[] discover(byte[] mac,int xid) {
        if(mac==null || mac.length!=6) throw new IllegalArgumentException("MAC must be six bytes");
        // BOOTP minimum 300 bytes, including DHCP options and padding.
        byte[] b=new byte[14+20+8+300]; Arrays.fill(b,0,6,(byte)255);
        System.arraycopy(mac,0,b,6,6); w16(b,12,0x0800);
        int ip=14,udp=34,boot=42;
        b[ip]=0x45; w16(b,ip+2,b.length-14); w16(b,ip+4,xid&65535);
        b[ip+8]=64; b[ip+9]=17; Arrays.fill(b,ip+16,ip+20,(byte)255);
        w16(b,ip+10,checksum(b,ip,20));
        w16(b,udp,68); w16(b,udp+2,67); w16(b,udp+4,b.length-udp);
        // UDP checksum zero is permitted for IPv4.
        b[boot]=1; b[boot+1]=1; b[boot+2]=6; w32(b,boot+4,xid);
        w16(b,boot+10,0x8000); System.arraycopy(mac,0,b,boot+28,6);
        w32(b,boot+236,0x63825363);
        int o=boot+240;
        byte[] opts={53,1,1,61,7,1,mac[0],mac[1],mac[2],mac[3],mac[4],mac[5],55,3,1,3,6,(byte)255};
        System.arraycopy(opts,0,b,o,opts.length); return b;
    }
    /** Returns offered IP only for a well-formed matching DHCP OFFER. */
    public static String matchingOffer(byte[] b,int length,byte[] mac,int xid) {
        if(b==null || mac==null || mac.length!=6 || length>b.length || length<14+20+8+240) return null;
        if(u16(b,12)!=0x0800 || (b[14]&240)!=0x40) return null;
        int ihl=(b[14]&15)*4;
        if(ihl<20 || 14+ihl+8+240>length || b[23]!=17 || (u16(b,20)&0x3fff)!=0) return null;
        int ipLen=u16(b,16),udp=14+ihl;
        if(ipLen<ihl+8+240 || ipLen>length-14 || checksum(b,14,ihl)!=0) return null;
        int udpLen=u16(b,udp+4),boot=udp+8;
        if(u16(b,udp)!=67 || u16(b,udp+2)!=68 || udpLen<8+240 || udpLen>ipLen-ihl) return null;
        if(b[boot]!=2 || b[boot+1]!=1 || b[boot+2]!=6) return null;
        for(int i=0;i<4;i++) if((b[boot+4+i]&255)!=((xid>>>(24-8*i))&255)) return null;
        for(int i=0;i<6;i++) if(b[boot+28+i]!=mac[i]) return null;
        if(u16(b,boot+236)!=0x6382 || u16(b,boot+238)!=0x5363) return null;
        int end=udp+udpLen;
        for(int p=boot+240;p<end;) {
            int type=b[p++]&255; if(type==255) break; if(type==0) continue;
            if(p>=end) return null; int n=b[p++]&255; if(n>end-p) return null;
            if(type==53 && n==1 && b[p]==2) {
                int q=boot+16; return (b[q]&255)+"."+(b[q+1]&255)+"."+(b[q+2]&255)+"."+(b[q+3]&255);
            }
            p+=n;
        }
        return null;
    }
}
