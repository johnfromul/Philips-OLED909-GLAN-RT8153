import cz.codex.rtl8153diagnostic.EthernetFrames;
import java.util.Arrays;
public final class EthernetFramesTest {
    private static void check(boolean b,String msg) {if(!b) throw new AssertionError(msg);}
    public static void main(String[] args) {
        byte[] mac={2,3,4,5,6,7};int xid=0x12abcd89;
        byte[] request=EthernetFrames.discover(mac,xid);
        check(request.length==342,"BOOTP minimum length");
        check(EthernetFrames.checksum(request,14,20)==0,"IPv4 checksum");
        check(request[34]==0 && request[35]==68 && request[37]==67,"UDP DHCP client ports");
        check(request[42]==1 && (request[52]&255)==128,"BOOTREQUEST broadcast flag");
        check(EthernetFrames.matchingOffer(request,request.length,mac,xid)==null,"Request is not an offer");
        byte[] offer=request.clone();offer[42]=2;offer[35]=67;offer[37]=68;
        offer[58]=(byte)192;offer[59]=(byte)168;offer[60]=1;offer[61]=99;offer[284]=2;
        check("192.168.1.99".equals(EthernetFrames.matchingOffer(offer,offer.length,mac,xid)),"Matching server OFFER accepted");
        check(EthernetFrames.matchingOffer(offer,offer.length,mac,xid+1)==null,"Other transaction rejected");
        check(EthernetFrames.matchingOffer(offer,offer.length,new byte[6],xid)==null,"Other client rejected");
        for(int i=0;i<offer.length;i++) check(EthernetFrames.matchingOffer(Arrays.copyOf(offer,i),i,mac,xid)==null,"Truncated IP packet rejected "+i);
        byte[] bad=offer.clone();bad[24]^=1;
        check(EthernetFrames.matchingOffer(bad,bad.length,mac,xid)==null,"Bad IPv4 checksum rejected");
        bad=offer.clone();bad[38]=127;
        check(EthernetFrames.matchingOffer(bad,bad.length,mac,xid)==null,"Oversized UDP rejected");
        bad=offer.clone();bad[282]=1;bad[283]=(byte)255;
        check(EthernetFrames.matchingOffer(bad,bad.length,mac,xid)==null,"Out-of-bounds DHCP option rejected");
        System.out.println("PASS: DHCP discovery/OFFER validation, packet bounds and all 342 truncation cases");
    }
}
