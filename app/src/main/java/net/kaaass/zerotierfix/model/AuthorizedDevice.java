package net.kaaass.zerotierfix.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 已授权设备模型
 */
@Data
public class AuthorizedDevice {
    /**
     * 设备节点ID
     */
    private String nodeId;
    
    /**
     * 设备名称（如果有）
     */
    private String deviceName;
    
    /**
     * 设备IP地址列表
     */
    private List<String> ipAddresses = new ArrayList<>();
    
    /**
     * 设备是否在线
     */
    private boolean online;
    
    /**
     * 最后一次在线时间戳
     */
    private long lastOnline;
    
    /**
     * 设备所属网络ID
     */
    private String networkId;
}
