package pl.rejtag.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only pairing codes. These values include a session secret; never log or persist them. */
final class UwbProtocol {
    private static final Pattern PEER = Pattern.compile("RTUWB1:P:([0-9A-Fa-f]{4})");
    private static final Pattern CONFIG = Pattern.compile("RTUWB1:C:([0-9A-Fa-f]{4}):([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,10}):([0-9A-Fa-f]{32})");
    private UwbProtocol() { }
    static final class Config {
        final byte[] peer, key;
        final int channel, preamble, sessionId;
        Config(byte[] peer, int channel, int preamble, int sessionId, byte[] key) {
            this.peer = peer.clone(); this.channel = channel; this.preamble = preamble; this.sessionId = sessionId; this.key = key.clone();
        }
    }
    static String peerCode(byte[] address) {
        if (address == null || address.length != 2) throw new IllegalArgumentException();
        return "RTUWB1:P:" + hex(address);
    }
    static byte[] parsePeer(String raw) {
        Matcher match = PEER.matcher(raw); if (!match.matches()) throw new IllegalArgumentException();
        return unhex(match.group(1));
    }
    static String configCode(byte[] address, int channel, int preamble, int sid, byte[] key) {
        if (address == null || address.length != 2 || key == null || key.length != 16 || channel < 1 || channel > 15 || preamble < 1 || preamble > 32 || sid < 1) throw new IllegalArgumentException();
        return String.format(Locale.US, "RTUWB1:C:%s:%d:%d:%d:%s", hex(address), channel, preamble, sid, hex(key));
    }
    static Config parseConfig(String raw) {
        Matcher match = CONFIG.matcher(raw); if (!match.matches()) throw new IllegalArgumentException();
        int channel = Integer.parseInt(match.group(2)), preamble = Integer.parseInt(match.group(3));
        long sid = Long.parseLong(match.group(4));
        if (channel < 1 || channel > 15 || preamble < 1 || preamble > 32 || sid < 1 || sid > Integer.MAX_VALUE) throw new IllegalArgumentException();
        return new Config(unhex(match.group(1)), channel, preamble, (int) sid, unhex(match.group(5)));
    }
    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte v : bytes) b.append(String.format(Locale.US, "%02X", v & 255)); return b.toString(); }
    private static byte[] unhex(String value) { byte[] out = new byte[value.length()/2]; for (int i=0; i<out.length; i++) out[i]=(byte) Integer.parseInt(value.substring(i*2,i*2+2),16); return out; }
}
