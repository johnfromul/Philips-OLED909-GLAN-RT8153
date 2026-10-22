package cz.codex.rtl8153diagnostic;

import android.hardware.usb.*;
import android.os.SystemClock;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Bounded ECM test using only standard USB CDC requests and DHCP DISCOVER. */
public final class EcmProbe {
    private static void put(JSONObject j,String k,Object v) { try {j.put(k,v);}catch(JSONException ignored){} }
    private static int readAlt(UsbDeviceConnection c,int id) {
        byte[] b=new byte[1]; return c.controlTransfer(0x81,10,0,id,b,1,1000)==1 ? b[0]&255 : -1;
    }
    static int macStringIndex(byte[] raw,int configId,int controlId) {
        int config=-1,face=-1;
        for(int p=0;raw!=null && p+2<=raw.length;) {
            int n=raw[p]&255,t=raw[p+1]&255; if(n<2 || p+n>raw.length) break;
            if(t==2 && n>=9) {config=raw[p+5]&255; face=-1;}
            if(t==4 && n>=9) face=raw[p+2]&255;
            if(config==configId && face==controlId && t==0x24 && n>=13 && raw[p+2]==15) return raw[p+3]&255;
            p+=n;
        }
        return 0;
    }
    static byte[] readMac(UsbDeviceConnection c,int index) {
        if(index==0) return null;
        byte[] lang=new byte[255]; int r=c.controlTransfer(0x80,6,0x0300,0,lang,lang.length,1000);
        if(r<4 || lang[1]!=3) return null;
        int language=(lang[2]&255)|((lang[3]&255)<<8);
        byte[] s=new byte[255]; r=c.controlTransfer(0x80,6,0x0300|index,language,s,s.length,1000);
        if(r<2 || s[1]!=3 || (s[0]&255)>r || (s[0]&255)<2 || ((s[0]&255)&1)!=0) return null;
        String hex=new String(s,2,(s[0]&255)-2,StandardCharsets.UTF_16LE).trim();
        if(!hex.matches("[0-9A-Fa-f]{12}")) return null;
        byte[] mac=new byte[6]; for(int i=0;i<6;i++) mac[i]=(byte)Integer.parseInt(hex.substring(2*i,2*i+2),16);
        boolean any=false; for(byte v:mac) if(v!=0) any=true;
        return any && (mac[0]&1)==0 ? mac : null;
    }
    public static String run(UsbDevice d,UsbDeviceConnection c,int active,JSONObject result,StringBuilder log) {
        UsbConfiguration cfg=null; UsbInterface ctrl=null,data=null,restore=null;
        UsbEndpoint in=null,out=null;
        for(int k=0;k<d.getConfigurationCount();k++) if(d.getConfiguration(k).getId()==active) cfg=d.getConfiguration(k);
        if(cfg==null) return L.t("ECM test: aktivní konfigurace není dostupná.");
        for(int k=0;k<cfg.getInterfaceCount();k++) {
            UsbInterface f=cfg.getInterface(k);
            if(f.getInterfaceClass()==2 && f.getInterfaceSubclass()==6) ctrl=f;
            if(f.getInterfaceClass()!=10) continue;
            UsbEndpoint bi=null,bo=null;
            for(int e=0;e<f.getEndpointCount();e++) { UsbEndpoint ep=f.getEndpoint(e);
                if(ep.getType()==2) {if(ep.getDirection()==128) bi=ep; else bo=ep;} }
            if(bi!=null && bo!=null) {data=f;in=bi;out=bo;}
        }
        if(ctrl==null || data==null) return L.t("ECM test: aktivní konfigurace nenabízí řídicí a datové ECM rozhraní.");
        int original=readAlt(c,data.getId()); put(result,"original_alt",original);
        for(int k=0;k<cfg.getInterfaceCount();k++) {UsbInterface f=cfg.getInterface(k);
            if(f.getId()==data.getId() && f.getAlternateSetting()==original) restore=f;}
        if(restore==null) return L.t("ECM test: původní alternativní nastavení nelze zjistit; žádná změna neproběhla.");
        byte[] mac=readMac(c,macStringIndex(c.getRawDescriptors(),active,ctrl.getId()));
        put(result,"mac_read",mac!=null);
        if(mac==null) return L.t("ECM test: nepodařilo se přečíst MAC z ECM deskriptoru.");
        boolean cc=false,dc=false,filterAttempted=false,altAttempted=false;
        int txOk=0,txErr=0,rx=0,rxErrors=0; String offer=null;
        try {
            cc=c.claimInterface(ctrl,false); put(result,"control_claim",cc);
            if(!cc) return L.t("ECM test: nelze převzít řídicí rozhraní bez vynucení.");
            dc=c.claimInterface(data,false); put(result,"data_claim",dc);
            if(!dc) return L.t("ECM test: nelze převzít datové rozhraní bez vynucení.");
            altAttempted=true; boolean selected=c.setInterface(data); put(result,"select_alt",selected);
            int actual=readAlt(c,data.getId());put(result,"selected_alt_readback",actual);
            log.append("ECM SET_INTERFACE alt ").append(data.getAlternateSetting()).append(": ").append(selected).append("; GET_INTERFACE = ").append(actual).append('\n');
            if(!selected || actual!=data.getAlternateSetting()) return L.t("ECM test: aktivace datového rozhraní selhala.");
            filterAttempted=true;
            int filter=c.controlTransfer(0x21,0x43,0x0c,ctrl.getId(),null,0,1000);
            put(result,"set_packet_filter",filter);log.append("ECM packet filter (directed+broadcast): ").append(filter).append('\n');
            if(filter!=0) return L.t("ECM test: nastavení přijímacího filtru selhalo.");
            int xid=new SecureRandom().nextInt(); byte[] discover=EthernetFrames.discover(mac,xid);
            byte[] buffer=new byte[16384];
            long started=SystemClock.elapsedRealtime(),deadline=started+12000,nextSend=started+500;
            while(SystemClock.elapsedRealtime()<deadline && !Thread.currentThread().isInterrupted()) {
                long now=SystemClock.elapsedRealtime();
                if(now>=nextSend && txOk+txErr<3) {
                    int sent=c.bulkTransfer(out,discover,discover.length,800);
                    if(sent==discover.length) txOk++; else txErr++;
                    log.append("DHCP DISCOVER USB OUT: ").append(sent).append('/').append(discover.length).append(L.t(" bajtů\n"));
                    nextSend=now+3000;
                }
                int n=c.bulkTransfer(in,buffer,buffer.length,200);
                if(n>0) {rx++;offer=EthernetFrames.matchingOffer(buffer,n,mac,xid); if(offer!=null) break;}
                else if(n<0) rxErrors++;
            }
            put(result,"tx_complete",txOk);put(result,"tx_failed",txErr);
            put(result,"rx_transfers",rx);put(result,"rx_negative_or_timeout",rxErrors);
            put(result,"matching_dhcp_offer",offer!=null); if(offer!=null) put(result,"offered_ip",offer);
            log.append("ECM: USB OUT OK=").append(txOk).append(L.t(", příchozí přenosy=")).append(rx)
                .append(L.t(", odpovídající DHCP OFFER=")).append(offer!=null).append('\n');
            if(offer!=null) return L.t("ECM přenos funguje v obou směrech: server odpověděl na náš DHCP DISCOVER. IP nebyla převzata a VPN nevznikla.");
            if(rx>0) return L.t("ECM přijímá data, ale odpověď na DHCP DISCOVER nebyla potvrzena. Funkční síť zatím neprokázána.");
            return L.t("ECM bylo aktivováno, ale během 12 s nepřišla data ani DHCP OFFER. USB OUT samo nepotvrzuje provoz na kabelu.");
        } catch(Exception e) {put(result,"error",e.toString());return L.t("ECM test skončil chybou: ")+e;}
        finally {
            if(filterAttempted) {int r=c.controlTransfer(0x21,0x43,0,ctrl.getId(),null,0,1000);
                put(result,"clear_packet_filter",r);log.append(L.t("Vypnutí ECM filtru: ")).append(r).append('\n');}
            if(altAttempted) {boolean ok=c.setInterface(restore);int back=readAlt(c,data.getId());
                put(result,"restore_alt",ok);put(result,"restored_alt_readback",back);
                log.append(L.t("Obnova alt ")).append(original).append(": ").append(ok).append(L.t("; ověřeno ")).append(back).append('\n');}
            if(dc) put(result,"release_data",c.releaseInterface(data));
            if(cc) put(result,"release_control",c.releaseInterface(ctrl));
        }
    }
}
