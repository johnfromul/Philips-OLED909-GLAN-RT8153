import cz.codex.rtl8153diagnostic.*;
import java.util.*;
public final class LanPacketsTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static void invalid(Runnable r){try{r.run();throw new AssertionError("Invalid configuration accepted");}catch(IllegalArgumentException good){}}
    public static void main(String[]args){
        IpConfig c=IpConfig.manual("192.168.1.200","255.255.255.0","192.168.1.1","1.1.1.1, 8.8.8.8");
        check(c.prefix==24&&c.dns.size()==2&&c.local(IpConfig.parse("192.168.1.42")),"Static config and local routing");
        check(!c.local(IpConfig.parse("8.8.8.8")),"External routing through gateway");
        invalid(()->IpConfig.parse("1.2.3.256"));invalid(()->IpConfig.parse("1.2.3"));invalid(()->IpConfig.prefix("255.0.255.0"));
        invalid(()->IpConfig.manual("192.168.1.0","24","192.168.1.1","1.1.1.1"));
        invalid(()->IpConfig.manual("192.168.1.255","24","192.168.1.1","1.1.1.1"));
        invalid(()->IpConfig.manual("192.168.1.200","24","192.168.2.1","1.1.1.1"));
        invalid(()->IpConfig.manual("192.168.1.1","24","192.168.1.1","1.1.1.1"));
        invalid(()->IpConfig.manual("192.168.1.200","24","192.168.1.1",""));
        byte[]mac={2,3,4,5,6,7};int xid=0x12345678;
        byte[]arp=LanPackets.arp(mac,c.ip,c.gateway,null,false);
        check(LanPackets.validArp(arp,arp.length)&&LanPackets.i32(arp,28)==c.ip&&LanPackets.i32(arp,38)==c.gateway,"ARP query wire layout");
        check(!LanPackets.validArp(arp,41),"Short ARP rejected");
        byte[]packet=LanPackets.dhcp(mac,xid,3,IpConfig.parse("192.168.1.84"),c.gateway,0);
        check(EthernetFrames.checksum(packet,14,20)==0&&packet[42]==1,"Outgoing DHCP REQUEST checksum");
        // Synthetic DHCP ACK with independent known option encoding.
        packet[42]=2;packet[35]=67;packet[37]=68;LanPackets.w32(packet,58,IpConfig.parse("192.168.1.84"));
        Arrays.fill(packet,282,packet.length,(byte)0);
        int[]opts={53,1,5,1,4,255,255,255,0,3,4,192,168,1,1,6,4,1,1,1,1,54,4,192,168,1,1,51,4,0,0,14,16,255};
        for(int i=0;i<opts.length;i++)packet[282+i]=(byte)opts[i];
        LanPackets.Dhcp ack=LanPackets.parseDhcp(packet,packet.length,mac,xid);
        check(ack!=null&&ack.type==5&&ack.lease==3600&&ack.config().prefix==24,"ACK settings and lease parsed");
        check(LanPackets.parseDhcp(packet,packet.length,mac,xid+1)==null,"Wrong DHCP transaction rejected");
        for(int i=0;i<packet.length;i++)check(LanPackets.parseDhcp(Arrays.copyOf(packet,i),i,mac,xid)==null,"Truncated DHCP rejected");
        byte[]bad=packet.clone();bad[283]=(byte)255;check(LanPackets.parseDhcp(bad,bad.length,mac,xid)==null,"Oversized DHCP option rejected");
        System.out.println("PASS: static IPv4 validation, subnet routing, ARP and DHCP ACK parsing with all truncations");
    }
}
