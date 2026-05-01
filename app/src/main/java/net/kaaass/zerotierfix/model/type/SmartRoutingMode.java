package net.kaaass.zerotierfix.model.type;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.smartroute.SmartRoutingManager;

/**
 * 智能路由模式枚举
 */
public enum SmartRoutingMode {
    /** 关闭智能路由 */
    OFF(SmartRoutingManager.MODE_OFF),

    /**
     * 国内直连模式：尽量通过系统路由表让中国 IP 走物理网络、非中国 IP 走 ZeroTier
     */
    CHINA_DIRECT(SmartRoutingManager.MODE_CHINA_DIRECT),

    /**
     * GFW 列表模式：基于 DNS 嗅探将已知 GFW IP 作为显式路由通过 ZT
     */
    GFW_LIST(SmartRoutingManager.MODE_GFW_LIST),

    /**
     * 组合模式：结合 GFW DNS 嗅探与中国 IP 列表做增强分流；受 DNS 时序影响
     */
    COMBINED(SmartRoutingManager.MODE_COMBINED);

    private final int id;

    SmartRoutingMode(int id) {
        this.id = id;
    }

    public int toInt() {
        return id;
    }

    public static SmartRoutingMode fromInt(int i) {
        switch (i) {
            case SmartRoutingManager.MODE_CHINA_DIRECT:
                return CHINA_DIRECT;
            case SmartRoutingManager.MODE_GFW_LIST:
                return GFW_LIST;
            case SmartRoutingManager.MODE_COMBINED:
                return COMBINED;
            default:
                return OFF;
        }
    }

    public int toStringId() {
        switch (this) {
            case CHINA_DIRECT:
                return R.string.smart_routing_mode_china_direct;
            case GFW_LIST:
                return R.string.smart_routing_mode_gfw_list;
            case COMBINED:
                return R.string.smart_routing_mode_combined;
            default:
                return R.string.smart_routing_mode_off;
        }
    }
}
