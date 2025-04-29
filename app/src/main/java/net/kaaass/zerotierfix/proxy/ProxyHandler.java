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
     * 检查代理配置是否有效
     *
     * @return true 如果代理配置有效，false 否则
     */
    public boolean isProxyConfigValid() {
        // 检查代理主机和端口
        String host = proxyManager.getProxyHost();
        int port = proxyManager.getProxyPort();
        
        // 主机必须非空且端口必须有效
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            LogUtil.e(TAG, "无效的代理配置: 主机=" + host + ", 端口=" + port);
            return false;
        }
        
        // 判断是否配置了用户名，如果配置了用户名则认为需要验证
        String username = proxyManager.getProxyUsername();
        String password = proxyManager.getProxyPassword();
        if (username != null && !username.isEmpty()) {
            // 如果设置了用户名但没有密码
            if (password == null || password.isEmpty()) {
                LogUtil.e(TAG, "需要验证但凭据不完整");
                return false;
            }
        }
        
        return true;
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
        int sourcePort;
        
        // 处理IPv6
        if (destIP instanceof Inet6Address) {
            // IPv6头部是固定的40字节
            ipHeaderLength = 40;
            LogUtil.d(TAG, "处理IPv6数据包，头部长度=40字节");
        } else if (destIP instanceof Inet4Address) {
            LogUtil.d(TAG, "处理IPv4数据包，头部长度=" + ipHeaderLength + "字节");
        }
        
        // 提取端口号
        if (packetData.length >= ipHeaderLength + 4) {
            sourcePort = ((packetData[ipHeaderLength] & 0xFF) << 8) | (packetData[ipHeaderLength + 1] & 0xFF);
            destPort = ((packetData[ipHeaderLength + 2] & 0xFF) << 8) | (packetData[ipHeaderLength + 3] & 0xFF);
            LogUtil.d(TAG, "数据包端口信息: 源端口=" + sourcePort + ", 目标端口=" + destPort);
        } else {
            LogUtil.e(TAG, "数据包太短，无法提取端口信息: 数据包长度=" + packetData.length + ", 所需最小长度=" + (ipHeaderLength + 4));
            return false;
        }
        
        // 获取协议类型
        int protocol = -1;
        if (destIP instanceof Inet4Address && packetData.length > 9) {
            protocol = packetData[9] & 0xFF;
            LogUtil.d(TAG, "IPv4协议类型: " + protocol + (protocol == 6 ? " (TCP)" : protocol == 17 ? " (UDP)" : ""));
        } else if (destIP instanceof Inet6Address && packetData.length > 6) {
            protocol = packetData[6] & 0xFF;
            LogUtil.d(TAG, "IPv6下一报头: " + protocol);
        }
        
        // 过滤一些不需要代理的常用端口
        if (!shouldProxy(destIP, destPort)) {
            LogUtil.d(TAG, "跳过代理: 目标" + destIP.getHostAddress() + ":" + destPort + "无需代理");
            return false;
        }
        
        LogUtil.i(TAG, "尝试通过代理发送数据包: 源=" + sourceIP.getHostAddress() + ":" + sourcePort + 
                " -> 目标=" + destIP.getHostAddress() + ":" + destPort + 
                ", 协议=" + (isTCP ? "TCP" : "UDP/其他") +
                ", 数据包大小=" + packetData.length + "字节");
        
        // 获取或创建到目标的代理连接
        ProxyClient proxyClient = getOrCreateConnection(destIP, destPort);
        if (proxyClient == null) {
            LogUtil.e(TAG, "无法获取或创建代理连接: 目标=" + destIP.getHostAddress() + ":" + destPort);
            return false;
        }
        
        try {
            // 通过代理发送数据包
            proxyClient.sendData(packetData);
            LogUtil.d(TAG, "通过代理成功发送数据: 目标=" + destIP.getHostAddress() + ":" + destPort + 
                    ", 大小=" + packetData.length + "字节, 代理服务器=" + proxyManager.getProxyHost() + ":" + 
                    proxyManager.getProxyPort());
            return true;
        } catch (IOException e) {
            LogUtil.e(TAG, "通过代理发送数据失败: 目标=" + destIP.getHostAddress() + ":" + destPort + 
                    ", 错误=" + e.getMessage(), e);
            
            // 关闭失败的连接并从缓存中移除
            String connectionKey = destIP.getHostAddress() + ":" + destPort;
            connectionCache.remove(connectionKey);
            proxyClient.close();
            LogUtil.d(TAG, "已移除失败的代理连接: " + connectionKey);
            
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