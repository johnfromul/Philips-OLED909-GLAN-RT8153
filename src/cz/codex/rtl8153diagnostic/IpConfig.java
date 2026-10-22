package cz.codex.rtl8153diagnostic;

import java.util.*;

public final class IpConfig {
    public boolean dhcp=true;
    public int ip,gateway,prefix=24,server;
    public long leaseSeconds=3600;
    public final List<Integer> dns=new ArrayList<>();
    public static int parse(String text) {
        String[] a=text.trim().split("\\.",-1); if(a.length!=4) throw new IllegalArgumentException(L.t("IPv4 musí mít čtyři čísla oddělená tečkami."));
        int n=0; for(String s:a) { if(!s.matches("[0-9]{1,3}")) throw new IllegalArgumentException(L.t("Neplatná IPv4 adresa."));
            int v=Integer.parseInt(s);if(v>255) throw new IllegalArgumentException(L.t("Část IPv4 musí být 0 až 255."));n=(n<<8)|v;}return n;
    }
    public static String text(int n) {return (n>>>24)+"."+((n>>>16)&255)+"."+((n>>>8)&255)+"."+(n&255);}
    public static boolean unicast(int n) {return (n>>>24)>0 && (n>>>24)<224 && (n>>>24)!=127;}
    public int mask() {return -1<<(32-prefix);}
    public static int prefix(String s) {
        s=s.trim();
        if(s.contains(".")) {int mask=parse(s),n=Integer.bitCount(mask);if(n<1 || n>30 || mask!=(-1<<(32-n))) throw new IllegalArgumentException(L.t("Maska musí být souvislá (/1 až /30)."));return n;}
        try {int n=Integer.parseInt(s);if(n>=1 && n<=30)return n;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException(L.t("Prefix musí být 1 až 30, případně zadej masku 255.255.255.0."));
    }
    public boolean local(int n) {return (n&mask())==(ip&mask());}
    public void validate() {
        if(prefix<1 || prefix>30 || !unicast(ip) || !unicast(gateway)) throw new IllegalArgumentException(L.t("Neplatná IP adresa, prefix nebo brána."));
        if(ip==(ip&mask()) || ip==(ip|~mask())) throw new IllegalArgumentException(L.t("IP nesmí být adresa sítě nebo broadcast."));
        if(ip==gateway || !local(gateway) || gateway==(ip&mask()) || gateway==(ip|~mask())) throw new IllegalArgumentException(L.t("Brána musí být jiná adresa ve stejné podsíti."));
        if(dns.isEmpty()) throw new IllegalArgumentException(L.t("Chybí DNS server."));
        for(int d:dns) if(!unicast(d))throw new IllegalArgumentException(L.t("Neplatný DNS server."));
    }
    public static IpConfig manual(String ip,String prefix,String gateway,String dns) {
        IpConfig c=new IpConfig();c.dhcp=false;c.ip=parse(ip);c.prefix=prefix(prefix);c.gateway=parse(gateway);
        for(String d:dns.trim().split("[,; ]+")) if(!d.isEmpty())c.dns.add(parse(d));c.validate();return c;
    }
    public String summary() {return (dhcp?"DHCP ":L.t("Ručně "))+text(ip)+"/"+prefix+L.t(" · brána ")+text(gateway)+" · DNS "+dnsText();}
    public String dnsText() {List<String>s=new ArrayList<>();for(int d:dns)s.add(text(d));return String.join(", ",s);}
}
