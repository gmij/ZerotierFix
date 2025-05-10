package net.kaaass.zerotierfix.events;

/**
 * 请求获取已授权设备列表事件
 */
public class AuthorizedDevicesRequestEvent {
    private final Long networkId;

    public AuthorizedDevicesRequestEvent(Long networkId) {
        this.networkId = networkId;
    }

    public Long getNetworkId() {
        return networkId;
    }
}
