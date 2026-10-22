package cz.codex.rtl8153diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure Java: bounds-checked parsing of untrusted USB descriptor bytes. */
public final class DescriptorUtil {
    private DescriptorUtil() {}
    public static boolean isTarget(int vendor, int product) {
        return vendor == 0x0bda && product == 0x8153;
    }
    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder s = new StringBuilder(data.length * 2);
        for (byte b : data) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    public static List<String> summarize(byte[] data) {
        List<String> lines = new ArrayList<>();
        if (data == null || data.length == 0) {
            lines.add("Deskriptory nejsou k dispozici.");
            return lines;
        }
        int pos = 0;
        while (pos < data.length) {
            if (data.length - pos < 2) {
                lines.add(L.t("Neúplná hlavička deskriptoru na offsetu ") + pos); break;
            }
            int length = data[pos] & 255, type = data[pos + 1] & 255;
            if (length < 2 || length > data.length - pos) {
                lines.add(L.t("Neplatná délka deskriptoru ") + length + " na offsetu " + pos); break;
            }
            String line = "offset=" + pos + " typ=" + type + L.t(" délka=") + length;
            if (type == 1 && length >= 18) {
                int usb = (data[pos+2] & 255) | ((data[pos+3] & 255) << 8);
                int vid = (data[pos+8] & 255) | ((data[pos+9] & 255) << 8);
                int pid = (data[pos+10] & 255) | ((data[pos+11] & 255) << 8);
                line += String.format(Locale.ROOT, L.t(" USB-bcd=%04x VID:PID=%04x:%04x konfigurací=%d"),
                        usb, vid, pid, data[pos+17] & 255);
            }
            lines.add(line); pos += length;
        }
        return lines;
    }
}
