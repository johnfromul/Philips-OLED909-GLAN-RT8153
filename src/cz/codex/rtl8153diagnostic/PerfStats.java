package cz.codex.rtl8153diagnostic;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Measurement only: forwarding thread owns timings, reader publishes counters. */
public final class PerfStats {
    private final File file;
    private long since=System.nanoTime(),inCalls,inFrames,inBytes,inPositiveNs,inEmptyNs,inEmptyCalls,outCalls,outBytes,outNs,tunCalls,tunBytes,tunNs;
    private int inMaxBytes;
    private final long[] inHistogram=new long[7],outHistogram=new long[7];
    public volatile long readerPackets,readerBytes,queueFullDrops;
    public volatile int queueHighWater;
    private long lastReaderPackets,lastReaderBytes,lastQueueDrops;
    public PerfStats(File directory) {file=directory==null?null:new File(directory,"usb-ethernet-perf.jsonl");}
    public void begin() {reset();if(file!=null)try(FileOutputStream f=new FileOutputStream(file)){f.write(new byte[0]);}catch(IOException ignored){}}
    private void reset(){since=System.nanoTime();inCalls=inFrames=inBytes=inPositiveNs=inEmptyNs=inEmptyCalls=outCalls=outBytes=outNs=tunCalls=tunBytes=tunNs=0;
        inMaxBytes=0;java.util.Arrays.fill(inHistogram,0);java.util.Arrays.fill(outHistogram,0);}
    private static int bucket(long ns){if(ns<10000)return 0;if(ns<50000)return 1;if(ns<100000)return 2;if(ns<500000)return 3;if(ns<1000000)return 4;if(ns<10000000)return 5;return 6;}
    public void usbIn(int bytes,long ns){inCalls++;if(bytes>0){inFrames++;inBytes+=bytes;inPositiveNs+=ns;inMaxBytes=Math.max(inMaxBytes,bytes);inHistogram[bucket(ns)]++;}else{inEmptyCalls++;inEmptyNs+=ns;}}
    public void usbOut(int bytes,long ns){outCalls++;if(bytes>0)outBytes+=bytes;outNs+=ns;outHistogram[bucket(ns)]++;}
    public void tunWrite(int bytes,long ns){tunCalls++;tunBytes+=bytes;tunNs+=ns;}
    public void maybeWrite(boolean force){
        long now=System.nanoTime();if(!force&&now-since<2000000000L)return;
        try{
            JSONObject j=new JSONObject();j.put("uptime_seconds",SystemClock.elapsedRealtimeNanos()/1e9);j.put("interval_seconds",(now-since)/1e9);
            j.put("usb_in_calls",inCalls);j.put("usb_in_frames",inFrames);j.put("usb_in_bytes",inBytes);j.put("usb_in_positive_ns",inPositiveNs);
            j.put("usb_in_empty_calls",inEmptyCalls);j.put("usb_in_empty_ns",inEmptyNs);j.put("usb_in_max_bytes",inMaxBytes);
            j.put("usb_out_calls",outCalls);j.put("usb_out_bytes",outBytes);j.put("usb_out_ns",outNs);
            j.put("tun_write_calls",tunCalls);j.put("tun_write_bytes",tunBytes);j.put("tun_write_ns",tunNs);
            long rp=readerPackets,rb=readerBytes,qd=queueFullDrops;
            j.put("tun_reader_packets",rp-lastReaderPackets);j.put("tun_reader_bytes",rb-lastReaderBytes);j.put("queue_full_drops",qd-lastQueueDrops);j.put("queue_high_water_cumulative",queueHighWater);
            lastReaderPackets=rp;lastReaderBytes=rb;lastQueueDrops=qd;
            JSONArray ih=new JSONArray(),oh=new JSONArray();for(long n:inHistogram)ih.put(n);for(long n:outHistogram)oh.put(n);
            j.put("usb_in_positive_duration_buckets",ih);j.put("usb_out_duration_buckets",oh);
            j.put("bucket_upper_bounds_us","10,50,100,500,1000,10000,infinity");
            if(file!=null)try(FileOutputStream f=new FileOutputStream(file,true)){f.write((j.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}finally{reset();}
    }
}
