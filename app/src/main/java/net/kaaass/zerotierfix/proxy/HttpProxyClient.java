package net.kaaass.zerotierfix.proxy;

import net.kaaass.zerotierfix.util.LogUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * HTTP代理客户端实现
 */
public class HttpProxyClient implements ProxyClient {
    private static final String TAG = "HttpProxyClient";
    
    // 代理服务器信息
    private final String proxyHost;
    private final int proxyPort;
    private final String username;
    private final String password;
    
    // 连接对象
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean connected = false;
    private InetAddress currentDestination;
    private int currentDestPort;
    
    /**
     * 创建HTTP代理客户端
     * 
     * @param proxyHost 代理服务器地址
     * @param proxyPort 代理服务器端口
     * @param username 用户名（可为null表示无需认证）
     * @param password 密码（可为null表示无需认证）
     */
    public HttpProxyClient(String proxyHost, int proxyPort, String username, String password) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username = username;
        this.password = password;
    }
    
    @Override
    public void connect() throws IOException {
        LogUtil.d(TAG, "连接到HTTP代理: " + proxyHost + ":" + proxyPort);
        
        // 建立TCP连接
        socket = new Socket(proxyHost, proxyPort);
        socket.setSoTimeout(10000); // 10秒超时
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        connected = true;
        
        LogUtil.d(TAG, "成功连接到HTTP代理服务器");
    }
    
    @Override
    public boolean connectToDestination(InetAddress destAddress, int destPort) throws IOException {
        LogUtil.d(TAG, "通过HTTP代理连接到目标: " + destAddress.getHostAddress() + ":" + destPort);
        
        if (!connected) {
            throw new IOException("代理服务器连接尚未建立");
        }
        
        // 记录当前连接目标，HTTP代理每次建立新连接要重新CONNECT
        this.currentDestination = destAddress;
        this.currentDestPort = destPort;
        
        // 构建CONNECT请求
        StringBuilder connectRequest = new StringBuilder();
        connectRequest.append("CONNECT ")
                .append(destAddress.getHostAddress())
                .append(":")
                .append(destPort)
                .append(" HTTP/1.1\r\n");
        connectRequest.append("Host: ")
                .append(destAddress.getHostAddress())
                .append(":")
                .append(destPort)
                .append("\r\n");
        
        // 添加认证信息（如果有）
        if (username != null && !username.isEmpty() && password != null) {
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            connectRequest.append("Proxy-Authorization: Basic ")
                    .append(encodedAuth)
                    .append("\r\n");
        }
        
        // 添加其他必要的HTTP头
        connectRequest.append("Proxy-Connection: Keep-Alive\r\n");
        connectRequest.append("Connection: Keep-Alive\r\n");
        connectRequest.append("\r\n");
        
        // 发送连接请求
        outputStream.write(connectRequest.toString().getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        
        // 接收HTTP响应
        byte[] responseBuffer = new byte[1024];
        int bytesRead = inputStream.read(responseBuffer);
        
        if (bytesRead <= 0) {
            throw new IOException("无法从代理服务器读取响应");
        }
        
        // 将响应转为字符串以解析状态码
        String response = new String(responseBuffer, 0, bytesRead, StandardCharsets.UTF_8);
        LogUtil.d(TAG, "代理响应: " + response);
        
        // 解析HTTP状态码
        if (response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200")) {
            LogUtil.d(TAG, "HTTP代理连接成功建立");
            return true;
        } else {
            LogUtil.e(TAG, "HTTP代理连接失败: " + response.split("\\r\\n")[0]);
            return false;
        }
    }
    
    @Override
    public void sendData(byte[] data) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("代理连接已关闭");
        }
        
        // HTTP代理在CONNECT后的数据传输是透明的，直接发送
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
        LogUtil.d(TAG, "关闭HTTP代理连接");
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
            connected = false;
        }
    }
    
    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed() || !connected;
    }
    
    @Override
    public String getProxyType() {
        return "HTTP";
    }
}