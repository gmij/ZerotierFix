package net.kaaass.zerotierfix.proxy;

import net.kaaass.zerotierfix.util.LogUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * SOCKS5 代理客户端
 */
public class Socks5ProxyClient implements ProxyClient {
    private static final String TAG = "Socks5ProxyClient";
    private static final int CONNECTION_TIMEOUT = 5000; // 5秒连接超时
    private static final int SOCKET_TIMEOUT = 10000; // 10秒读写超时
    private static final int MAX_RETRIES = 2; // 最大重试次数

    private final String proxyHost;
    private final int proxyPort;
    private final String username;
    private final String password;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    /**
     * 构造函数
     *
     * @param proxyHost 代理服务器地址
     * @param proxyPort 代理服务器端口
     */
    public Socks5ProxyClient(String proxyHost, int proxyPort) {
        this(proxyHost, proxyPort, null, null);
    }
    
    /**
     * 构造函数，带身份验证
     *
     * @param proxyHost 代理服务器地址
     * @param proxyPort 代理服务器端口
     * @param username  用户名（如果不需要验证可为null）
     * @param password  密码（如果不需要验证可为null）
     */
    public Socks5ProxyClient(String proxyHost, int proxyPort, String username, String password) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username = username;
        this.password = password;
    }

    /**
     * 连接到SOCKS5代理
     *
     * @throws IOException 如果连接失败
     */
    @Override
    public void connect() throws IOException {
        int retries = 0;
        IOException lastException = null;

        while (retries <= MAX_RETRIES) {
            try {
                LogUtil.d(TAG, "连接到SOCKS5代理: " + proxyHost + ":" + proxyPort + " (尝试 " + (retries + 1) + "/" + (MAX_RETRIES + 1) + ")");
                
                socket = new Socket();
                socket.setSoTimeout(SOCKET_TIMEOUT);
                socket.connect(new java.net.InetSocketAddress(proxyHost, proxyPort), CONNECTION_TIMEOUT);
                
                in = socket.getInputStream();
                out = socket.getOutputStream();
                
                // SOCKS5 初始握手
                if (username != null && !username.isEmpty()) {
                    // 有用户名密码的认证
                    out.write(new byte[]{5, 2, 0, 2}); // 支持无认证和用户名/密码认证
                } else {
                    // 无认证
                    out.write(new byte[]{5, 1, 0}); // 仅支持无认证
                }
                out.flush();
                
                // 读取服务器响应
                byte[] response = new byte[2];
                int bytesRead = in.read(response);
                
                if (bytesRead != 2 || response[0] != 5) {
                    throw new IOException("SOCKS5握手失败，服务器协议不匹配");
                }
                
                // 如果需要认证
                if (response[1] == 2 && username != null && !username.isEmpty()) {
                    // 用户名/密码认证
                    performUsernamePasswordAuth();
                } else if (response[1] != 0) {
                    throw new IOException("SOCKS5握手失败，服务器不接受提供的认证方式");
                }
                
                LogUtil.d(TAG, "成功连接到SOCKS5代理: " + proxyHost + ":" + proxyPort);
                return; // 连接成功
            } catch (SocketTimeoutException e) {
                lastException = e;
                LogUtil.w(TAG, "连接SOCKS5代理超时: " + e.getMessage() + "，尝试重试 " + (retries + 1) + "/" + MAX_RETRIES);
            } catch (IOException e) {
                lastException = e;
                LogUtil.w(TAG, "连接SOCKS5代理失败: " + e.getMessage() + "，尝试重试 " + (retries + 1) + "/" + MAX_RETRIES);
            }
            
            // 清理失败的连接
            closeQuietly();
            retries++;
            
            // 重试前等待一段时间
            if (retries <= MAX_RETRIES) {
                try {
                    Thread.sleep(1000 * retries); // 递增等待时间
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // 所有重试都失败了
        throw new IOException("连接SOCKS5代理失败，已达最大重试次数: " + (lastException != null ? lastException.getMessage() : "未知错误"));
    }
    
    /**
     * 执行用户名/密码认证
     */
    private void performUsernamePasswordAuth() throws IOException {
        // 根据RFC 1929构建认证包
        byte[] usernameBytes = username.getBytes();
        byte[] passwordBytes = password.getBytes();
        
        byte[] authRequest = new byte[3 + usernameBytes.length + passwordBytes.length];
        authRequest[0] = 1; // 版本号
        authRequest[1] = (byte) usernameBytes.length;
        System.arraycopy(usernameBytes, 0, authRequest, 2, usernameBytes.length);
        authRequest[2 + usernameBytes.length] = (byte) passwordBytes.length;
        System.arraycopy(passwordBytes, 0, authRequest, 3 + usernameBytes.length, passwordBytes.length);
        
        // 发送认证请求
        out.write(authRequest);
        out.flush();
        
        // 读取认证响应
        byte[] response = new byte[2];
        int bytesRead = in.read(response);
        
        if (bytesRead != 2 || response[0] != 1 || response[1] != 0) {
            throw new IOException("SOCKS5用户名/密码认证失败");
        }
    }

    /**
     * 通过代理连接到目标
     */
    @Override
    public boolean connectToDestination(InetAddress destAddr, int destPort) throws IOException {
        if (socket == null || !socket.isConnected()) {
            connect(); // 重连如果需要
        }

        try {
            // SOCKS5 TCP 连接请求
            byte[] destAddrBytes = destAddr.getAddress();
            byte addrType = (byte) (destAddr.getAddress().length == 4 ? 1 : 4); // IPv4=1, IPv6=4
            
            // 发送连接请求
            byte[] request = new byte[4 + 1 + destAddrBytes.length + 2];
            request[0] = 5;  // SOCKS版本
            request[1] = 1;  // 命令：建立TCP连接
            request[2] = 0;  // 保留位
            request[3] = addrType;  // 地址类型
            
            // 复制目标地址
            System.arraycopy(destAddrBytes, 0, request, 4, destAddrBytes.length);
            
            // 设置目标端口（网络字节序）
            request[4 + destAddrBytes.length] = (byte) ((destPort >> 8) & 0xFF);
            request[4 + destAddrBytes.length + 1] = (byte) (destPort & 0xFF);
            
            // 发送请求
            out.write(request);
            out.flush();
            
            // 读取响应
            byte[] response = new byte[4];
            int bytesRead = in.read(response);
            
            if (bytesRead < 4 || response[0] != 5) {
                LogUtil.e(TAG, "SOCKS5协议错误: 返回版本不是5");
                return false;
            }
            
            if (response[1] != 0) {
                LogUtil.e(TAG, "SOCKS5连接目标失败: 状态码=" + response[1]);
                return false;
            }
            
            // 读取剩余响应信息
            int addrLen = 0;
            if (response[3] == 1) { // IPv4
                addrLen = 4;
            } else if (response[3] == 3) { // 域名
                addrLen = in.read() & 0xFF;
            } else if (response[3] == 4) { // IPv6
                addrLen = 16;
            } else {
                LogUtil.e(TAG, "SOCKS5返回未知的地址类型: " + response[3]);
                return false;
            }
            
            // 读取和跳过地址和端口
            byte[] skipBuffer = new byte[addrLen + 2]; // 地址和端口
            in.read(skipBuffer);
            
            LogUtil.d(TAG, "成功通过SOCKS5代理连接到目标: " + destAddr.getHostAddress() + ":" + destPort);
            return true;
        } catch (IOException e) {
            LogUtil.e(TAG, "通过SOCKS5代理连接目标失败: " + e.getMessage(), e);
            closeQuietly();
            throw e;
        }
    }

    /**
     * 发送数据
     */
    @Override
    public void sendData(byte[] data) throws IOException {
        if (socket == null || !socket.isConnected()) {
            throw new IOException("代理连接未建立");
        }
        
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            LogUtil.e(TAG, "发送数据失败: " + e.getMessage(), e);
            closeQuietly();
            throw e;
        }
    }
    
    /**
     * 接收数据
     */
    @Override
    public byte[] receiveData() throws IOException {
        if (socket == null || !socket.isConnected()) {
            throw new IOException("代理连接未建立");
        }
        
        try {
            byte[] buffer = new byte[4096];
            int bytesRead = in.read(buffer);
            
            if (bytesRead <= 0) {
                return new byte[0];
            }
            
            byte[] result = new byte[bytesRead];
            System.arraycopy(buffer, 0, result, 0, bytesRead);
            return result;
        } catch (IOException e) {
            LogUtil.e(TAG, "接收数据失败: " + e.getMessage(), e);
            closeQuietly();
            throw e;
        }
    }

    /**
     * 通过代理发送UDP数据包
     *
     * @param data      UDP数据包内容
     * @param destAddr  目标地址
     * @param destPort  目标端口
     * @throws IOException 如果发送失败
     */
    public void sendUdpPacket(byte[] data, InetAddress destAddr, int destPort) throws IOException {
        if (socket == null || !socket.isConnected()) {
            connect(); // 重连如果需要
        }

        try {
            // SOCKS5 UDP 关联请求
            byte[] destAddrBytes = destAddr.getAddress();
            byte addrType = (byte) (destAddr.getAddress().length == 4 ? 1 : 4); // IPv4=1, IPv6=4
            
            // 发送UDP关联请求
            byte[] request = new byte[4 + 1 + destAddrBytes.length + 2];
            request[0] = 5;  // SOCKS版本
            request[1] = 3;  // 命令：UDP关联
            request[2] = 0;  // 保留位
            request[3] = addrType;  // 地址类型
            
            // 复制目标地址
            System.arraycopy(destAddrBytes, 0, request, 4, destAddrBytes.length);
            
            // 设置目标端口（网络字节序）
            request[4 + destAddrBytes.length] = (byte) ((destPort >> 8) & 0xFF);
            request[4 + destAddrBytes.length + 1] = (byte) (destPort & 0xFF);
            
            // 发送请求
            out.write(request);
            out.flush();
            
            // 读取响应
            byte[] response = new byte[4 + 1 + destAddrBytes.length + 2];
            int bytesRead = in.read(response);
            
            if (bytesRead < 4 || response[0] != 5 || response[1] != 0) {
                throw new IOException("SOCKS5 UDP关联请求失败: 状态码=" + response[1]);
            }
            
            // 获取UDP中继服务器地址和端口
            byte[] udpRelayAddr;
            int udpRelayPort;
            
            if (response[3] == 1) {  // IPv4
                udpRelayAddr = new byte[4];
                System.arraycopy(response, 4, udpRelayAddr, 0, 4);
                udpRelayPort = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
            } else if (response[3] == 4) {  // IPv6
                udpRelayAddr = new byte[16];
                System.arraycopy(response, 4, udpRelayAddr, 0, 16);
                udpRelayPort = ((response[20] & 0xFF) << 8) | (response[21] & 0xFF);
            } else {
                throw new IOException("SOCKS5服务器返回了不支持的地址类型: " + response[3]);
            }
            
            // 构建UDP头部
            byte[] udpHeader = new byte[4 + 1 + destAddrBytes.length + 2];
            udpHeader[0] = 0;  // 保留字段
            udpHeader[1] = 0;  // 保留字段
            udpHeader[2] = 0;  // 分段号
            udpHeader[3] = addrType;  // 地址类型
            
            // 复制目标地址
            System.arraycopy(destAddrBytes, 0, udpHeader, 4, destAddrBytes.length);
            
            // 设置目标端口（网络字节序）
            udpHeader[4 + destAddrBytes.length] = (byte) ((destPort >> 8) & 0xFF);
            udpHeader[4 + destAddrBytes.length + 1] = (byte) (destPort & 0xFF);
            
            // 创建UDP包
            byte[] udpPacket = new byte[udpHeader.length + data.length];
            System.arraycopy(udpHeader, 0, udpPacket, 0, udpHeader.length);
            System.arraycopy(data, 0, udpPacket, udpHeader.length, data.length);
            
            // 创建UDP socket发送数据
            try (java.net.DatagramSocket udpSocket = new java.net.DatagramSocket()) {
                java.net.DatagramPacket packet = new java.net.DatagramPacket(
                        udpPacket,
                        udpPacket.length,
                        InetAddress.getByAddress(udpRelayAddr),
                        udpRelayPort);
                udpSocket.send(packet);
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "发送UDP数据包失败: " + e.getMessage(), e);
            closeQuietly();  // 关闭可能已损坏的连接
            throw e;
        }
    }

    /**
     * 通过代理发送TCP数据包
     *
     * @param data      TCP数据包内容
     * @param destAddr  目标地址
     * @param destPort  目标端口
     * @throws IOException 如果发送失败
     */
    public void sendTcpPacket(byte[] data, InetAddress destAddr, int destPort) throws IOException {
        if (socket == null || !socket.isConnected()) {
            connect(); // 重连如果需要
        }

        try {
            // SOCKS5 TCP 连接请求
            byte[] destAddrBytes = destAddr.getAddress();
            byte addrType = (byte) (destAddr.getAddress().length == 4 ? 1 : 4); // IPv4=1, IPv6=4
            
            // 发送连接请求
            byte[] request = new byte[4 + 1 + destAddrBytes.length + 2];
            request[0] = 5;  // SOCKS版本
            request[1] = 1;  // 命令：建立TCP连接
            request[2] = 0;  // 保留位
            request[3] = addrType;  // 地址类型
            
            // 复制目标地址
            System.arraycopy(destAddrBytes, 0, request, 4, destAddrBytes.length);
            
            // 设置目标端口（网络字节序）
            request[4 + destAddrBytes.length] = (byte) ((destPort >> 8) & 0xFF);
            request[4 + destAddrBytes.length + 1] = (byte) (destPort & 0xFF);
            
            // 发送请求
            out.write(request);
            out.flush();
            
            // 读取响应
            byte[] response = new byte[4 + 1 + destAddrBytes.length + 2];
            int bytesRead = in.read(response);
            
            if (bytesRead < 4 || response[0] != 5 || response[1] != 0) {
                throw new IOException("SOCKS5 TCP连接请求失败: 状态码=" + response[1]);
            }
            
            // TCP连接已建立，发送数据
            out.write(data);
            out.flush();
        } catch (IOException e) {
            LogUtil.e(TAG, "发送TCP数据包失败: " + e.getMessage(), e);
            closeQuietly();  // 关闭可能已损坏的连接
            throw e;
        }
    }

    /**
     * 判断连接是否已关闭
     */
    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed() || !socket.isConnected();
    }
    
    /**
     * 获取代理类型
     */
    @Override
    public String getProxyType() {
        return "SOCKS5";
    }

    /**
     * 关闭连接
     */
    @Override
    public void close() {
        closeQuietly();
    }
    
    /**
     * 安静地关闭连接，不抛出异常
     */
    private void closeQuietly() {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {}
        
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {}
        
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        
        in = null;
        out = null;
        socket = null;
    }
}