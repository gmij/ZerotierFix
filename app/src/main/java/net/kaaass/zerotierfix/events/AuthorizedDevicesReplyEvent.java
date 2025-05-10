package net.kaaass.zerotierfix.events;

import net.kaaass.zerotierfix.model.AuthorizedDevice;

import java.util.List;

import lombok.Data;

/**
 * 已授权设备列表回复事件
 */
@Data
public class AuthorizedDevicesReplyEvent {
    private final List<AuthorizedDevice> devices;
    private final Long networkId;
    private final boolean success;
    private final String errorMessage;

    public AuthorizedDevicesReplyEvent(List<AuthorizedDevice> devices, Long networkId, boolean success, String errorMessage) {
        this.devices = devices;
        this.networkId = networkId;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static AuthorizedDevicesReplyEvent success(List<AuthorizedDevice> devices, Long networkId) {
        return new AuthorizedDevicesReplyEvent(devices, networkId, true, null);
    }

    public static AuthorizedDevicesReplyEvent error(Long networkId, String errorMessage) {
        return new AuthorizedDevicesReplyEvent(null, networkId, false, errorMessage);
    }
}
