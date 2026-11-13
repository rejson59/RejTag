package pl.rejtag.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class UwbProtocolTest {
    @Test public void peerCodeRoundTrip() {
        byte[] address = {0x12, (byte) 0xAB};
        assertEquals("RTUWB1:P:12AB", UwbProtocol.peerCode(address));
        assertArrayEquals(address, UwbProtocol.parsePeer("RTUWB1:P:12ab"));
    }
    @Test public void configRoundTrip() {
        byte[] address = {1, 2}, key = new byte[16];
        key[0] = (byte) 0xFE;
        String code = UwbProtocol.configCode(address, 9, 10, 123, key);
        UwbProtocol.Config result = UwbProtocol.parseConfig(code);
        assertArrayEquals(address, result.peer); assertArrayEquals(key, result.key);
        assertEquals(9, result.channel); assertEquals(10, result.preamble); assertEquals(123, result.sessionId);
    }
    @Test public void rejectsMalformedAndOutOfRangeCodes() {
        String key = "0123456789ABCDEF0123456789ABCDEF";
        String[] invalid = {"", "RTUWB1:C:0000:0:1:1:"+key, "RTUWB1:C:0000:16:1:1:"+key,
            "RTUWB1:C:0000:9:0:1:"+key, "RTUWB1:C:0000:9:33:1:"+key,
            "RTUWB1:C:0000:9:1:0:"+key, "RTUWB1:C:0000:9:1:2147483648:"+key,
            "RTUWB1:C:0000:9:1:1:AB", "RTUWB1:C:0000:9:1:1:"+key+"junk"};
        for (String s : invalid) { try { UwbProtocol.parseConfig(s); fail("accepted malformed code"); } catch (IllegalArgumentException expected) { } }
        try { UwbProtocol.parsePeer("RTUWB1:P:000000"); fail("accepted malformed peer"); } catch (IllegalArgumentException expected) { }
    }
}
