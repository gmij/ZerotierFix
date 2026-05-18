package net.kaaass.zerotierfix.util.smartroute;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LearnedRoutePolicyStoreTest {

    @Test
    public void subtract_removesViaZtExceptionFromChinaBlock() {
        List<CidrBlock> result = CidrBlock.subtract(
                Arrays.asList(CidrBlock.parse("1.0.0.0/24")),
                Arrays.asList(CidrBlock.parse("1.0.0.8/32")));

        assertFalse(contains(result, "1.0.0.8"));
        assertTrue(contains(result, "1.0.0.7"));
        assertTrue(contains(result, "1.0.0.9"));
    }

    @Test
    public void observe_directNeedsThreshold_thenPromotesPrefix() throws Exception {
        LearnedRoutePolicyStore store = new LearnedRoutePolicyStore(
                60_000L, 2, 1, 3, 8, 8);

        InetAddress ip = InetAddress.getByName("43.128.12.34");

        assertFalse(store.observe(ip, LearnedRoutePolicyStore.Preference.DIRECT,
                "cdn.example", "live-domain", 1_000L, true).routingChanged);
        assertTrue(store.observe(ip, LearnedRoutePolicyStore.Preference.DIRECT,
                "cdn.example", "live-domain", 2_000L, true).routingChanged);
        assertTrue(store.matchesActivePolicy(ip, LearnedRoutePolicyStore.Preference.DIRECT, 2_000L));

        // 第三次命中触发 /24 热点提升。
        assertTrue(store.observe(ip, LearnedRoutePolicyStore.Preference.DIRECT,
                "cdn.example", "live-domain", 3_000L, true).routingChanged);
        String description = store.describeActivePolicy(ip, 3_000L);
        assertNotNull(description);
        assertTrue(description.contains("direct"));

        List<CidrBlock> directCidrs = store.getActiveCidrs(LearnedRoutePolicyStore.Preference.DIRECT, 3_000L);
        assertTrue(contains(directCidrs, "43.128.12.34"));
        assertTrue(contains(directCidrs, "43.128.12.200"));
    }

    @Test
    public void observe_viaZtActivatesImmediately_andExpiresByTtl() throws Exception {
        LearnedRoutePolicyStore store = new LearnedRoutePolicyStore(
                1_000L, 2, 1, 3, 8, 8);
        InetAddress ip = InetAddress.getByName("1.1.1.1");

        assertTrue(store.observe(ip, LearnedRoutePolicyStore.Preference.VIA_ZT,
                "google.com", "gfw-domain", 100L, false).routingChanged);
        assertTrue(store.matchesActivePolicy(ip, LearnedRoutePolicyStore.Preference.VIA_ZT, 100L));

        List<String> serialized = store.serializeLines(2_000L);
        assertEquals(0, serialized.size());
        assertFalse(store.matchesActivePolicy(ip, LearnedRoutePolicyStore.Preference.VIA_ZT, 2_000L));
    }

    @Test
    public void observe_viaZtPromotedPrefixCollapsesCoveredHostEntries() throws Exception {
        LearnedRoutePolicyStore store = new LearnedRoutePolicyStore(
                60_000L, 2, 1, 3, 8, 16);

        InetAddress ip1 = InetAddress.getByName("172.217.194.100");
        InetAddress ip2 = InetAddress.getByName("172.217.194.101");
        InetAddress ip3 = InetAddress.getByName("172.217.194.102");
        InetAddress ip4 = InetAddress.getByName("172.217.194.138");

        assertTrue(store.observe(ip1, LearnedRoutePolicyStore.Preference.VIA_ZT,
                "play-fe.googleapis.com", "google-service", 1_000L, true).routingChanged);
        assertTrue(store.observe(ip2, LearnedRoutePolicyStore.Preference.VIA_ZT,
                "play-fe.googleapis.com", "google-service", 2_000L, true).routingChanged);
        assertTrue(store.observe(ip3, LearnedRoutePolicyStore.Preference.VIA_ZT,
                "play-fe.googleapis.com", "google-service", 3_000L, true).routingChanged);

        String description = store.describeActivePolicy(ip4, 3_000L);
        assertNotNull(description);
        assertTrue(description.contains("172.217.194.0/24"));

        assertFalse(store.observe(ip4, LearnedRoutePolicyStore.Preference.VIA_ZT,
                "play-fe.googleapis.com", "google-service", 4_000L, true).routingChanged);
        assertEquals(1, store.serializeLines(4_000L).size());
    }

    private static boolean contains(List<CidrBlock> cidrs, String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            for (CidrBlock cidr : cidrs) {
                if (cidr.contains(address)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
