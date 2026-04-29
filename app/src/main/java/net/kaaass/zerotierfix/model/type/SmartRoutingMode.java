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
     * 国内直连模式：中国 IP 走物理网络，境外 IP 走 ZeroTier
     * 需配合"全部路由走 ZeroTier"开启使用
     */
    CHINA_DIRECT(SmartRoutingManager.MODE_CHINA_DIRECT),

    /**
     * GFW 列表模式：GFW 封锁的域名走 ZeroTier，其余直连
     * 无需开启全部路由；已知 GFW IP 作为显式路由通过 ZT
     */
    GFW_LIST(SmartRoutingManager.MODE_GFW_LIST);

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
            default:
                return R.string.smart_routing_mode_off;
        }
    }
}
