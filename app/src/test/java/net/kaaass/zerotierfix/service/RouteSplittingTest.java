package net.kaaass.zerotierfix.service;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the CIDR route-splitting helpers in {@link ZeroTierOneService}.
 *
 * <p>The algorithm computes a minimal set of IPv4 routes that covers 0.0.0.0/0
 * minus a list of excluded subnets, used to prevent local network interfaces
 * (Bluetooth PAN, WiFi LAN, USB tethering, etc.) from being captured by the VPN.
 */
public class RouteSplittingTest {

    // ---- helpers ----

    private static long parseCidrNet(String cidr) {
        String ip = cidr.split("/")[0];
        int pfx = Integer.parseInt(cidr.split("/")[1]);
        byte[] bytes = parseIp(ip);
        long ipLong = ZeroTierOneService.ipv4BytesToLong(bytes);
        long mask = pfx == 0 ? 0L : ((pfx == 32) ? 0xFFFFFFFFL : (~0L << (32 - pfx)) & 0xFFFFFFFFL);
        return ipLong & mask;
    }

    private static int parseCidrPfx(String cidr) {
        return Integer.parseInt(cidr.split("/")[1]);
    }

    private static byte[] parseIp(String ip) {
        String[] p = ip.split("\\.");
        return new byte[]{(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]),
                (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])};
    }

    private static long[] cidr(String s) {
        return new long[]{parseCidrNet(s), parseCidrPfx(s)};
    }

    /** True if ipLong is covered by at least one route in the list. */
    private static boolean coveredBy(List<long[]> routes, String ip) {
        long target = ZeroTierOneService.ipv4BytesToLong(parseIp(ip));
        for (long[] r : routes) {
            long net = r[0]; int pfx = (int) r[1];
            if (pfx == 0) return true;
            long mask = (~0L << (32 - pfx)) & 0xFFFFFFFFL;
            if ((net & mask) == (target & mask)) return true;
        }
        return false;
    }

    // ---- tests ----

    @Test
    public void noExclusions_returnsSingleDefaultRoute() {
        List<long[]> routes = ZeroTierOneService.computeGlobalRoutesExcluding(
                Collections.emptyList());
        assertEquals(1, routes.size());
        assertArrayEquals(new long[]{0L, 0L}, routes.get(0));
    }

    @Test
    public void excludeBluetoothPan_subnet_notCapturedByVpn() {
        List<long[]> routes = ZeroTierOneService.computeGlobalRoutesExcluding(
                Collections.singletonList(cidr("192.168.44.0/24")));

        // Addresses in the excluded subnet must NOT be captured
        assertFalse("192.168.44.1 should not be in VPN routes",
                coveredBy(routes, "192.168.44.1"));
        assertFalse("192.168.44.255 should not be in VPN routes",
                coveredBy(routes, "192.168.44.255"));

        // All other public addresses must still be captured
        assertTrue("8.8.8.8 must be in VPN routes", coveredBy(routes, "8.8.8.8"));
        assertTrue("1.1.1.1 must be in VPN routes", coveredBy(routes, "1.1.1.1"));
        assertTrue("10.147.20.3 (ZeroTier range) must be in VPN routes",
                coveredBy(routes, "10.147.20.3"));

        // Route count: excluding one /24 from /0 produces 24 routes
        assertEquals(24, routes.size());
    }

    @Test
    public void excludeMultipleSubnets_allExcludedAndRestCovered() {
        List<long[]> routes = ZeroTierOneService.computeGlobalRoutesExcluding(
                Arrays.asList(
                        cidr("192.168.1.0/24"),   // typical WiFi LAN
                        cidr("192.168.44.0/24"),  // Bluetooth PAN
                        cidr("192.168.43.0/24")   // WiFi hotspot
                ));

        assertFalse(coveredBy(routes, "192.168.1.100"));
        assertFalse(coveredBy(routes, "192.168.44.5"));
        assertFalse(coveredBy(routes, "192.168.43.200"));

        assertTrue(coveredBy(routes, "8.8.8.8"));
        assertTrue(coveredBy(routes, "10.147.0.1"));
        assertTrue(coveredBy(routes, "172.16.5.5"));
        assertTrue(coveredBy(routes, "192.168.2.1")); // different /24 still covered
    }

    @Test
    public void excludeHostRoute_slash32() {
        List<long[]> routes = ZeroTierOneService.computeGlobalRoutesExcluding(
                Collections.singletonList(cidr("10.0.0.1/32")));

        assertFalse(coveredBy(routes, "10.0.0.1"));
        assertTrue(coveredBy(routes, "10.0.0.2"));
        assertTrue(coveredBy(routes, "10.0.0.0"));
        assertEquals(32, routes.size()); // excluding /32 from /0 = 32 routes
    }

    @Test
    public void ipv4BytesToLong_convertsCorrectly() {
        assertEquals(0x08080808L,
                ZeroTierOneService.ipv4BytesToLong(new byte[]{8, 8, 8, 8}));
        assertEquals(0xC0A82C05L,  // 192.168.44.5
                ZeroTierOneService.ipv4BytesToLong(new byte[]{(byte)192, (byte)168, 44, 5}));
        assertEquals(0L,
                ZeroTierOneService.ipv4BytesToLong(new byte[]{0, 0, 0, 0}));
        assertEquals(0xFFFFFFFFL,  // 255.255.255.255
                ZeroTierOneService.ipv4BytesToLong(new byte[]{(byte)255, (byte)255, (byte)255, (byte)255}));
    }
}
