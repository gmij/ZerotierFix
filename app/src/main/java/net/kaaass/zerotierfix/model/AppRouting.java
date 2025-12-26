package net.kaaass.zerotierfix.model;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 应用路由配置
 * 存储每个应用是否通过 ZeroTier VPN 路由
 */
@Entity
public class AppRouting {
    @Id
    private Long id;

    /**
     * 网络 ID，关联到具体的 ZeroTier 网络
     */
    private Long networkId;

    /**
     * 应用包名
     */
    private String packageName;

    /**
     * 是否通过 VPN 路由
     * true: 通过 VPN
     * false: 不通过 VPN (直连)
     */
    private boolean routeViaVpn;

    @Generated(hash = 1812155437)
    public AppRouting(Long id, Long networkId, String packageName, boolean routeViaVpn) {
        this.id = id;
        this.networkId = networkId;
        this.packageName = packageName;
        this.routeViaVpn = routeViaVpn;
    }

    @Generated(hash = 1414960172)
    public AppRouting() {
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNetworkId() {
        return this.networkId;
    }

    public void setNetworkId(Long networkId) {
        this.networkId = networkId;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public boolean getRouteViaVpn() {
        return this.routeViaVpn;
    }

    public void setRouteViaVpn(boolean routeViaVpn) {
        this.routeViaVpn = routeViaVpn;
    }
}
