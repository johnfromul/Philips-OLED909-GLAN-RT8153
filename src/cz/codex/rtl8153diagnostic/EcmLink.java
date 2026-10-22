package cz.codex.rtl8153diagnostic;

import android.hardware.usb.*;
import java.io.*;

/** Owns the USB connection; never changes its configuration or forces a claim. */
public final class EcmLink implements AutoCloseable {
    private UsbDeviceConnection c;private UsbInterface control,data,restore;private UsbEndpoint in,out;
    private boolean cc,dc,filter,changed;
    public byte[] mac;
    public interface Log {void write(String s);}
    private final Log log;
    private final PerfStats metrics;
    public EcmLink(Log log){this(log,null);}
    public EcmLink(Log log,PerfStats metrics){this.log=log;this.metrics=metrics;}
    public void open(UsbManager manager,UsbDevice d)throws IOException{
        try{
            c=manager.openDevice(d);if(c==null)throw new IOException(L.t("Nelze otevřít USB adaptér."));
            byte[]v=new byte[1];if(c.controlTransfer(0x80,8,0,0,v,1,1000)!=1)throw new IOException(L.t("Nelze přečíst USB konfiguraci."));
            UsbConfiguration cfg=null;for(int k=0;k<d.getConfigurationCount();k++)if(d.getConfiguration(k).getId()==(v[0]&255))cfg=d.getConfiguration(k);
            if(cfg==null)throw new IOException(L.t("Aktivní konfigurace není dostupná."));
            for(int k=0;k<cfg.getInterfaceCount();k++){
                UsbInterface f=cfg.getInterface(k);if(f.getInterfaceClass()==2&&f.getInterfaceSubclass()==6)control=f;
                if(f.getInterfaceClass()!=10)continue;UsbEndpoint a=null,b=null;
                for(int j=0;j<f.getEndpointCount();j++){UsbEndpoint ep=f.getEndpoint(j);if(ep.getType()==2){if(ep.getDirection()==128)a=ep;else b=ep;}}
                if(a!=null&&b!=null){data=f;in=a;out=b;}
            }
            if(control==null||data==null)throw new IOException(L.t("Adaptér není v režimu CDC ECM."));
            if(c.controlTransfer(0x81,10,0,data.getId(),v,1,1000)!=1)throw new IOException(L.t("Nelze zjistit původní alternativní nastavení."));
            for(int k=0;k<cfg.getInterfaceCount();k++){UsbInterface f=cfg.getInterface(k);if(f.getId()==data.getId()&&f.getAlternateSetting()==(v[0]&255))restore=f;}
            if(restore==null)throw new IOException(L.t("Původní USB nastavení není popsáno."));
            mac=EcmProbe.readMac(c,EcmProbe.macStringIndex(c.getRawDescriptors(),cfg.getId(),control.getId()));if(mac==null)throw new IOException(L.t("Nelze přečíst MAC adaptéru."));
            cc=c.claimInterface(control,false);if(!cc)throw new IOException(L.t("Řídicí rozhraní již používá jiný ovladač."));
            dc=c.claimInterface(data,false);if(!dc)throw new IOException(L.t("Datové rozhraní již používá jiný ovladač."));
            changed=true;if(!c.setInterface(data))throw new IOException("Aktivace ECM selhala.");
            filter=true;if(c.controlTransfer(0x21,0x43,0x0c,control.getId(),null,0,1000)!=0)throw new IOException(L.t("Nastavení ECM filtru selhalo."));
            log.write(L.t("ECM aktivní; řídicí a datové USB rozhraní převzato."));
        }catch(Exception e){close();if(e instanceof IOException)throw (IOException)e;throw new IOException(e);}
    }
    public void send(byte[]frame)throws IOException{
        if(c==null)throw new IOException(L.t("USB spojení je zavřené."));
        long start=System.nanoTime();int result=c.bulkTransfer(out,frame,frame.length,1000);
        if(metrics!=null)metrics.usbOut(result,System.nanoTime()-start);
        if(result!=frame.length)throw new IOException(L.t("USB odeslání selhalo."));
    }
    public int receive(byte[]buffer,int timeout){
        long start=System.nanoTime();int result=c.bulkTransfer(in,buffer,buffer.length,timeout);
        if(metrics!=null)metrics.usbIn(result,System.nanoTime()-start);return result;
    }
    public int fileDescriptor(){return c.getFileDescriptor();}
    public int inputEndpoint(){return in.getAddress();}
    public int outputEndpoint(){return out.getAddress();}
    @Override public void close(){
        if(c==null)return;
        try{if(filter)log.write(L.t("Vypnutí ECM filtru: ")+c.controlTransfer(0x21,0x43,0,control.getId(),null,0,1000));}catch(Exception e){log.write(L.t("Chyba vypnutí filtru: ")+e);}
        try{if(changed&&restore!=null)log.write(L.t("Obnova USB alternativního nastavení: ")+c.setInterface(restore));}catch(Exception e){log.write("Chyba obnovy USB: "+e);}
        try{if(dc)c.releaseInterface(data);}catch(Exception ignored){}
        try{if(cc)c.releaseInterface(control);}catch(Exception ignored){}
        c.close();c=null;log.write(L.t("USB spojení uvolněno."));
    }
}
