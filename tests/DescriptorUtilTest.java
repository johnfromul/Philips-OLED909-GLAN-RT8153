import cz.codex.rtl8153diagnostic.DescriptorUtil;
public final class DescriptorUtilTest {
    private static void check(boolean value,String msg) { if(!value) throw new AssertionError(msg); }
    public static void main(String[] args) {
        check(DescriptorUtil.isTarget(0x0bda,0x8153),"RTL8153 target");
        check(!DescriptorUtil.isTarget(0x046d,0xc31c),"Never claim keyboard");
        check(!DescriptorUtil.isTarget(0x0bda,0x8152),"Restrict to supported PID");
        byte[] device={18,1,0,3,0,0,0,9,(byte)0xda,11,83,(byte)0x81,0,49,1,2,3,2};
        check(DescriptorUtil.summarize(device).get(0).contains("0bda:8153"),"Little endian descriptor VID/PID");
        check(DescriptorUtil.summarize(device).get(0).contains("USB-bcd=0300"),"USB bcd");
        check(DescriptorUtil.summarize(new byte[]{0,1}).size()==1,"Zero length terminates");
        check(DescriptorUtil.summarize(new byte[]{18,1,0}).get(0).contains("Neplatná"),"Truncated descriptor");
        check(DescriptorUtil.summarize(new byte[]{1}).get(0).contains("Neúplná"),"One-byte tail");
        check(DescriptorUtil.summarize(null).size()==1,"Null descriptors");
        check(DescriptorUtil.hex(new byte[]{0,(byte)0xff,16}).equals("00ff10"),"Hex serialization");
        System.out.println("PASS: 10 descriptor and device-filter checks");
    }
}
