package net.kaaass.zerotierfix.proxy;

import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.LogUtil;
import net.kaaass.zerotierfix.util.ProxyManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代理处理器，负责管理代理连接和处理数据包转发
 */
public class ProxyHandler {
    private static final String TAG = "ProxyHandler";
    
    // 代理服务器配置
    private final ProxyManager proxyManager;
    
    // 连接缓存 - 键为目标地址:端口，值为对应的代理连接
    private final Map<String, ProxyClient> connectionCache = new ConcurrentHashMap<>();
    
    /**
     * 创建代理处理器
     * 
     * @param proxyManager 代理管理器
     */
    public ProxyHandler(ProxyManager proxyManager) {
        this.proxyManager = proxyManager;
        LogUtil.i(TAG, "初始化代理处理器，代理类型: " + proxyManager.getProxyTypeString());
    }
    
    /**
     * 获取或创建到指定目标的代理连接
     * 
     * @param destAddress 目标地址
     * @param destPort 目标端口
     * @return 代理连接实例，如果无法创建则返回null
     */
    public synchronized ProxyClient getOrCreateConnection(InetAddress destAddress, int destPort) {
        String connectionKey = destAddress.getHostAddress() + ":" + destPort;
        
        // 检查缓存中是否有可用连接
        ProxyClient existingClient = connectionCache.get(connectionKey);
        if (existingClient != null && !existingClient.isClosed()) {
            return existingClient;
        }
        
        // 创建新连接
        try {
            ProxyClient client = createProxyClient();
            // 先连接到代理服务器
            client.connect();
            
            // 然后通过代理连接到目标
            boolean success = client.connectToDestination(destAddress, destPort);
            if (success) {
                // 缓存成功的连接
                connectionCache.put(connectionKey, client);
                LogUtil.d(TAG, "成功建立到 " + connectionKey + " 的代理连接");
                return client;
            } else {
                // 连接失败，关闭连接
                client.close();
                LogUtil.e(TAG, "无法通过代理连接到 " + connectionKey);
                return null;
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "创建代理连接时出错: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 根据配置创建合适的代理客户端
     */
    private ProxyClient createProxyClient() {
        String host = proxyManager.getProxyHost();
        int port = proxyManager.getProxyPort();
        String username = proxyManager.getProxyUsername();
        String password = proxyManager.getProxyPassword();
        
        if (proxyManager.getProxyType() == Constants.PROXY_TYPE_SOCKS5) {
            return new Socks5ProxyClient(host, port, username, password);
        } else {
            return new HttpProxyClient(host, port, username, password);
        }
    }
    
    /**
     * 通过代理发送IP数据包
     * 
     * @param packetData IP数据包
     * @param sourceIP 源IP
     * @param destIP 目标IP
     * @param isTCP 是否是TCP数据包
     * @return true如果发送成功，false如果失败
     */
    public boolean sendPacketViaProxy(byte[] packetData, InetAddress sourceIP, InetAddress destIP, boolean isTCP) {
        // 提取目标端口（TCP/UDP头部的前两个字节为源端口，接下来两个字节为目标端口）
        int ipHeaderLength = (packetData[0] & 0x0F) * 4; // IPv4头部长度
        int destPort;
        
        // 处理IPv6
        if (destIP instanceof Inet6Address) {
            // IPv6头部是固定的40字节
            ipHeaderLength = 40;
        }
        
        // 提取端口号
        if (packetData.length >= ipHeaderLength + 4) {
            destPort = ((packetData[ipHeaderLength + 2] & 0xFF) << 8) | (packetData[ipHeaderLength + 3] & 0xFF);
        } else {
            LogUtil.e(TAG, "数据包太短，无法提取端口信息");
            return false;
        }
        
        // 过滤一些不需要代理的常用端口（可选）
        if (!shouldProxy(destIP, destPort)) {
            return false;
        }
        
        // 获取或创建到目标的代理连接
        ProxyClient proxyClient = getOrCreateConnection(destIP, destPort);
        if (proxyClient == null) {
            return false;
        }
        
        try {
            // 通过代理发送数据包
            proxyClient.sendData(packetData);
            LogUtil.d(TAG, "通过代理成功发送 " + packetData.length + " 字节到 " + 
                    destIP.getHostAddress() + ":" + destPort);
            return true;
        } catch (IOException e) {
            LogUtil.e(TAG, "通过代理发送数据失败: " + e.getMessage(), e);
            
            // 关闭失败的连接并从缓存中移除
            String connectionKey = destIP.getHostAddress() + ":" + destPort;
            connectionCache.remove(connectionKey);
            proxyClient.close();
            
            return false;
        }
    }
    
    /**
     * 判断指定目标是否应该通过代理
     */
    private boolean shouldProxy(InetAddress destIP, int destPort) {
        // 防止循环，不代理到代理服务器本身的连接
        try {
            InetAddress proxyAddr = InetAddress.getByName(proxyManager.getProxyHost());
            if (destIP.equals(proxyAddr) && destPort == proxyManager.getProxyPort()) {
                return false;
            }
        } catch (Exception ignored) { }
        
        // 可以在这里添加更多的规则，例如：
        // - 不代理内网IP
        // - 不代理特定的端口（DNS等）
        
        // 默认通过代理
        return true;
    }
    
    /**
     * 关闭所有代理连接
     */
    public synchronized void closeAllConnections() {
        LogUtil.i(TAG, "关闭所有代理连接, 数量: " + connectionCache.size());
        for (ProxyClient client : connectionCache.values()) {
            try {
                client.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "关闭代理连接时出错: " + e.getMessage(), e);
            }
        }
        connectionCache.clear();
    }
}