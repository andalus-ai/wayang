package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UrlGuardHardeningTest {

    private static boolean internal(String literal) throws Exception {
        return UrlGuard.isInternal(InetAddress.getByName(literal));
    }

    @Test
    void everyInternalFormIsRefused() throws Exception {
        for (String a : new String[] {
                "127.0.0.1", "127.1", "2130706433", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "100.100.100.200", "0.0.0.0", "192.0.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "240.0.0.1",
                "192.0.2.1", "198.51.100.1", "203.0.113.1", "192.88.99.1",
                "::1", "::", "fe80::1", "fc00::1", "fd00:ec2::254", "ff02::1",
                "::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:10.0.0.1", "::ffff:a9fe:a9fe",
                "::7f00:1", "::a00:1", "::a9fe:a9fe",                      // IPv4-compatible (deprecated) forms
                "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1",   // NAT64 to internal / local-use prefix
                "2002:7f00:1::", "2002:a9fe:a9fe::1", "2002:c0a8:101::",   // 6to4 around loopback / metadata / private
                "2001::1", "2001:db8::1", "100::1"}) {
            assertTrue(internal(a), a + " should be refused");
        }
    }

    @Test
    void publicAddressesStillPass() throws Exception {
        for (String a : new String[] {"8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.63.0.1", "100.128.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888",
                "64:ff9b::808:808", "2002:808:808::1", "::ffff:8.8.8.8"}) {
            assertFalse(internal(a), a + " should pass");
        }
    }

    @Test
    void urlsWithTrickyHostsAreRefusedByTheGuardWithoutADnsLookup() {
        UrlGuard g = UrlGuard.strict();
        for (String u : new String[] {"http://127.0.0.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://[::7f00:1]/", "http://2130706433/",
                "http://0x7f000001/", "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::]/", "http://169.254.169.254/latest/meta-data/",
                "http://[fd00:ec2::254]/"}) {
            assertThrows(IllegalArgumentException.class, () -> g.check(u), u);
        }
        for (String u : new String[] {"file:///etc/passwd", "ftp://example.com/", "gopher://127.0.0.1/", "jar:http://example.com/!/", "http://user:pw@example.com/", "http:///x", "//example.com/",
                "http://exa mple.com/", "javascript:alert(1)"}) {
            assertThrows(IllegalArgumentException.class, () -> g.check(u), u);
        }
    }

    @Test
    void theOperatorSwitchAndAllowListStillWork() {
        assertNotNull(new UrlGuard(true, Set.of()).check("http://127.0.0.1:8080/x"));
        assertNotNull(new UrlGuard(false, Set.of("localhost")).check("http://localhost:8080/x"));
        assertThrows(IllegalArgumentException.class, () -> new UrlGuard(false, Set.of("localhost")).check("http://127.0.0.1:8080/x"), "the allow-list is by host name, not by what it resolves to");
    }

    @Test
    void dnsPinningNeedsTheJvmAddressCache() {
        assertTrue(UrlGuard.dnsAnswersArePinned(null, null), "default: 30 seconds");
        assertTrue(UrlGuard.dnsAnswersArePinned(null, "30"));
        assertTrue(UrlGuard.dnsAnswersArePinned(null, "-1"), "forever");
        assertFalse(UrlGuard.dnsAnswersArePinned(null, "0"), "cache off: the connect resolves again");
        assertFalse(UrlGuard.dnsAnswersArePinned("0", "30"), "the system property wins");
        assertTrue(UrlGuard.dnsAnswersArePinned("60", "0"));
        assertTrue(UrlGuard.dnsAnswersArePinned("soon", null), "a bad value is ignored by the JDK");
    }
}
