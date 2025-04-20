package net.kaaass.zerotierfix.proxy;

import java.io.IOException;
import java.net.InetAddress;

/**
 * 代理客户端接口，定义与代理服务器通信的基本方法
 */
public interface ProxyClient {

    /**
     * 连接到代理服务器
     * 
     * @throws IOException 连接失败时抛出异常
     */
    void connect() throws IOException;

    /**
     * 通过代理连接到目标地址和端口
     * 
     * @param destAddress 目标地址
     * @param destPort 目标端口
     * @return true 如果连接成功，否则 false
     * @throws IOException 连接失败时抛出异常
     */
    boolean connectToDestination(InetAddress destAddress, int destPort) throws IOException;

    /**
     * 发送数据到代理服务器
     * 
     * @param data 要发送的数据
     * @throws IOException 发送失败时抛出异常
     */
    void sendData(byte[] data) throws IOException;

    /**
     * 接收来自代理服务器的数据
     * 
     * @return 接收到的数据
     * @throws IOException 接收失败时抛出异常
     */
    byte[] receiveData() throws IOException;

    /**
     * 关闭代理连接
     */
    void close();

    /**
     * 判断代理连接是否已关闭
     * 
     * @return true 如果连接已关闭，否则 false
     */
    boolean isClosed();
    
    /**
     * 获取代理类型
     * 
     * @return 代理类型字符串
     */
    String getProxyType();
}