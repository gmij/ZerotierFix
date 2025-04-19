package net.kaaass.zerotierfix.proxy;

import net.kaaass.zerotierfix.util.LogUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * SOCKS5代理客户端实现
 * 符合RFC 1928规范
 */
public class Socks5ProxyClient implements ProxyClient {
    private static final String TAG = "Socks5ProxyClient";
    
    // SOCKS5协议常量
    private static final byte SOCKS_VERSION = 5;
    private static final byte NO_AUTH = 0;
    private static final byte USERNAME_PASSWORD_AUTH = 2;
    private static final byte CONNECT_COMMAND = 1;
    private static final byte RESERVED = 0;
    private static final byte IPV4_ADDRESS = 1;
    private static final byte IPV6_ADDRESS = 4;
    private static final byte REQUEST_GRANTED = 0;
    
    // 代理服务器信息
    private final String proxyHost;
    private final int proxyPort;
    private final String username;
    private final String password;
    
    // 连接对象
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    
    /**
     * 创建SOCKS5代理客户端
     * 
     * @param proxyHost 代理服务器地址
     * @param proxyPort 代理服务器端口
     * @param username 用户名（可为null表示无需认证）
     * @param password 密码（可为null表示无需认证）
     */
    public Socks5ProxyClient(String proxyHost, int proxyPort, String username, String password) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username = username;
        this.password = password;
    }
    
    @Override
    public void connect() throws IOException {
        LogUtil.d(TAG, "连接到SOCKS5代理: " + proxyHost + ":" + proxyPort);
        
        // 建立TCP连接
        socket = new Socket(proxyHost, proxyPort);
        socket.setSoTimeout(10000); // 10秒超时
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        
        // SOCKS5握手 - 发送支持的认证方法
        byte[] authRequest;
        if (username != null && !username.isEmpty() && password != null) {
            // 支持无认证和用户名密码认证
            authRequest = new byte[]{SOCKS_VERSION, 2, NO_AUTH, USERNAME_PASSWORD_AUTH};
        } else {
            // 仅支持无认证
            authRequest = new byte[]{SOCKS_VERSION, 1, NO_AUTH};
        }
        
        outputStream.write(authRequest);
        outputStream.flush();
        
        // 接收服务器选择的认证方法
        byte[] authResponse = new byte[2];
        if (inputStream.read(authResponse) != 2) {
            throw new IOException("代理服务器认证协商失败");
        }
        
        // 检查SOCKS5版本号
        if (authResponse[0] != SOCKS_VERSION) {
            throw new IOException("不支持的SOCKS协议版本: " + authResponse[0]);
        }
        
        // 处理认证方法
        byte selectedAuth = authResponse[1];
        switch (selectedAuth) {
            case NO_AUTH:
                // 无需认证，继续处理
                LogUtil.d(TAG, "代理服务器不需要认证");
                break;
            case USERNAME_PASSWORD_AUTH:
                // 用户名密码认证
                if (username == null || username.isEmpty() || password == null) {
                    throw new IOException("代理服务器需要认证，但未提供凭据");
                }
                performUserPassAuth();
                break;
            case (byte) 0xFF:
                throw new IOException("代理服务器不接受提供的认证方法");
            default:
                throw new IOException("不支持的认证方法: " + selectedAuth);
        }
        
        LogUtil.d(TAG, "成功连接到SOCKS5代理服务器");
    }
    
    /**
     * 执行用户名密码认证
     */
    private void performUserPassAuth() throws IOException {
        LogUtil.d(TAG, "执行SOCKS5用户名密码认证");
        
        // 构建认证请求 - 子协商版本1
        ByteBuffer authBuffer = ByteBuffer.allocate(3 + username.length() + password.length());
        authBuffer.put((byte) 1); // 子协商版本1
        
        // 添加用户名
        authBuffer.put((byte) username.length());
        authBuffer.put(username.getBytes());
        
        // 添加密码
        authBuffer.put((byte) password.length());
        authBuffer.put(password.getBytes());
        
        // 发送认证请求
        outputStream.write(authBuffer.array());
        outputStream.flush();
        
        // 接收认证结果
        byte[] authResult = new byte[2];
        if (inputStream.read(authResult) != 2) {
            throw new IOException("代理服务器认证响应接收失败");
        }
        
        // 检查版本和状态码
        if (authResult[0] != 1) {
            throw new IOException("认证子协商版本错误: " + authResult[0]);
        }
        if (authResult[1] != 0) {
            throw new IOException("认证失败，状态码: " + authResult[1]);
        }
        
        LogUtil.d(TAG, "SOCKS5用户名密码认证成功");
    }
    
    @Override
    public boolean connectToDestination(InetAddress destAddress, int destPort) throws IOException {
        LogUtil.d(TAG, "通过SOCKS5代理连接到目标: " + destAddress.getHostAddress() + ":" + destPort);
        
        // 构建连接请求
        byte[] addressBytes = destAddress.getAddress();
        ByteBuffer requestBuffer = ByteBuffer.allocate(6 + addressBytes.length);
        
        requestBuffer.put(SOCKS_VERSION); // SOCKS版本5
        requestBuffer.put(CONNECT_COMMAND); // 连接命令
        requestBuffer.put(RESERVED); // 保留字段
        
        // 判断IP类型
        if (addressBytes.length == 4) {
            requestBuffer.put(IPV4_ADDRESS); // IPv4
        } else if (addressBytes.length == 16) {
            requestBuffer.put(IPV6_ADDRESS); // IPv6
        } else {
            throw new IOException("不支持的地址类型");
        }
        
        // 添加目标IP地址和端口
        requestBuffer.put(addressBytes);
        requestBuffer.putShort((short) destPort);
        
        // 发送连接请求
        outputStream.write(requestBuffer.array());
        outputStream.flush();
        
        // 接收响应
        byte[] responseHeader = new byte[4];
        if (inputStream.read(responseHeader) != 4) {
            throw new IOException("代理服务器响应接收失败");
        }
        
        // 检查SOCKS版本
        if (responseHeader[0] != SOCKS_VERSION) {
            throw new IOException("无效的SOCKS版本响应: " + responseHeader[0]);
        }
        
        // 检查响应状态
        if (responseHeader[1] != REQUEST_GRANTED) {
            String errorMessage = getErrorMessage(responseHeader[1]);
            LogUtil.e(TAG, "代理连接失败: " + errorMessage);
            return false;
        }
        
        // 跳过绑定地址和端口信息
        int addressType = responseHeader[3];
        int addressLength;
        if (addressType == IPV4_ADDRESS) {
            addressLength = 4;
        } else if (addressType == IPV6_ADDRESS) {
            addressLength = 16;
        } else {
            throw new IOException("不支持的地址类型响应: " + addressType);
        }
        
        byte[] skipBytes = new byte[addressLength + 2]; // 地址 + 2字节端口
        inputStream.read(skipBytes);
        
        LogUtil.d(TAG, "SOCKS5代理成功连接到目标");
        return true;
    }
    
    /**
     * 根据SOCKS5错误代码获取错误信息
     */
    private String getErrorMessage(byte code) {
        switch (code) {
            case 1:
                return "常规性失败";
            case 2:
                return "规则不允许连接";
            case 3:
                return "网络不可达";
            case 4:
                return "主机不可达";
            case 5:
                return "连接被拒绝";
            case 6:
                return "TTL过期";
            case 7:
                return "不支持的命令";
            case 8:
                return "不支持的地址类型";
            default:
                return "未知错误: " + code;
        }
    }
    
    @Override
    public void sendData(byte[] data) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("代理连接已关闭");
        }
        outputStream.write(data);
        outputStream.flush();
    }
    
    @Override
    public byte[] receiveData() throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("代理连接已关闭");
        }
        
        byte[] buffer = new byte[4096];
        int bytesRead = inputStream.read(buffer);
        
        if (bytesRead <= 0) {
            return new byte[0];
        }
        
        return Arrays.copyOf(buffer, bytesRead);
    }
    
    @Override
    public void close() {
        LogUtil.d(TAG, "关闭SOCKS5代理连接");
        try {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "关闭代理连接时出错: " + e.getMessage(), e);
        } finally {
            inputStream = null;
            outputStream = null;
            socket = null;
        }
    }
    
    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }
    
    @Override
    public String getProxyType() {
        return "SOCKS5";
    }
}