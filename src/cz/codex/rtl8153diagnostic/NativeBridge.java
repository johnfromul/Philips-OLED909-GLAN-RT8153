package cz.codex.rtl8153diagnostic;

import java.io.IOException;

/** Bounded native USB/TUN data plane; control/configuration remains in Java. */
public final class NativeBridge {
    static { System.loadLibrary("ecmfast"); }
    private NativeBridge() {}
    public static native long create(int usb,int tun,int in,int out,byte[]mac,int ip,int prefix,int gateway) throws IOException;
    public static native String run(long handle,EthernetVpnService owner);
    public static native void stop(long handle);
    public static native boolean send(long handle,byte[]frame);
    public static native long[] stats(long handle);
    public static native void release(long handle);
}
