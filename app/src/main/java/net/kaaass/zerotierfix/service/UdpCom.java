package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.PacketSender;
import com.zerotier.sdk.ResultCode;

import net.kaaass.zerotierfix.util.DebugLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

// TODO: clear up
public class UdpCom implements PacketSender, Runnable {
    private static final String TAG = "UdpCom";
    private Node node;
    private final DatagramSocket svrSocket;
    private final ZeroTierOneService ztService;
    private volatile boolean running = true;

    UdpCom(ZeroTierOneService zeroTierOneService, DatagramSocket datagramSocket) {
        this.svrSocket = datagramSocket;
        this.ztService = zeroTierOneService;
    }

    public void setNode(Node node2) {
        this.node = node2;
    }

    @Override // com.zerotier.sdk.PacketSender
    public int onSendPacketRequested(long j, InetSocketAddress inetSocketAddress, byte[] bArr, int i) {
        if (this.svrSocket == null) {
            Log.e(TAG, "Attempted to send packet on a null socket");
            return -1;
        }
        try {
            DatagramPacket datagramPacket = new DatagramPacket(bArr, bArr.length, inetSocketAddress);
            DebugLog.d(TAG, "onSendPacketRequested: Sent " + datagramPacket.getLength() + " bytes to " + inetSocketAddress.toString());
            this.svrSocket.send(datagramPacket);
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error sending packet: " + e.getMessage());
            return -1;
        }
    }

    public void stopRunning() {
        running = false;
    }

    public void run() {
        Log.d(TAG, "UDP Listen Thread Started.");
        try {
            long[] jArr = new long[1];
            byte[] bArr = new byte[16384];
            while (!Thread.interrupted() && running) {
                jArr[0] = 0;
                DatagramPacket datagramPacket = new DatagramPacket(bArr, 16384);
                try {
                    this.svrSocket.receive(datagramPacket);
                    if (datagramPacket.getLength() > 0) {
                        byte[] bArr2 = new byte[datagramPacket.getLength()];
                        System.arraycopy(datagramPacket.getData(), 0, bArr2, 0, datagramPacket.getLength());
                        DebugLog.d(TAG, "Got " + datagramPacket.getLength() + " Bytes From: " + datagramPacket.getAddress().toString() + ":" + datagramPacket.getPort());
                        
                        // 确保 node 不为空
                        if (this.node != null) {
                            ResultCode processWirePacket = this.node.processWirePacket(System.currentTimeMillis(), -1, 
                                new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()), 
                                bArr2, jArr);
                            
                            if (processWirePacket != ResultCode.RESULT_OK) {
                                Log.e(TAG, "processWirePacket returned: " + processWirePacket.toString());
                                // 不要直接调用 shutdown，通过服务来处理
                                if (this.ztService != null) {
                                    this.ztService.setNextBackgroundTaskDeadline(jArr[0]);
                                    if (processWirePacket != ResultCode.RESULT_FATAL_ERROR_ALREADY_EXISTS) {
                                        this.ztService.shutdown();
                                    }
                                }
                            } else if (this.ztService != null) {
                                this.ztService.setNextBackgroundTaskDeadline(jArr[0]);
                            }
                        } else {
                            Log.e(TAG, "Node is null, cannot process packet");
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // 超时是正常的，不需要处理
                } catch (Exception e) {
                    Log.e(TAG, "Error receiving packet: " + e.getMessage());
                    // 不终止循环，继续尝试接收数据包
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in UDP thread: ", e);
        } finally {
            Log.d(TAG, "UDP Listen Thread Ended.");
        }
    }
}
