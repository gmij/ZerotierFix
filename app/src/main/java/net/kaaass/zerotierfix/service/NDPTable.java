package net.kaaass.zerotierfix.service;

import android.util.Log;

import net.kaaass.zerotierfix.util.IPPacketUtils;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// TODO: clear up
public class NDPTable {
    public static final String TAG = "NDPTable";
    private static final long ENTRY_TIMEOUT = 120000;
    private final Map<Long, NDPEntry> entriesMap = new ConcurrentHashMap<>();
    private final Map<InetAddress, Long> inetAddressToMacAddress = new ConcurrentHashMap<>();
    private final Map<InetAddress, NDPEntry> ipEntriesMap = new ConcurrentHashMap<>();
    private final Map<Long, InetAddress> macAddressToInetAddress = new ConcurrentHashMap<>();
    private final Thread timeoutThread;
    private volatile boolean running = true;

    public NDPTable() {
        timeoutThread = new Thread("NDP Timeout Thread") {
            @Override
            public void run() {
                Log.d(TAG, "NDP Timeout Thread Started.");
                while (!isInterrupted() && running) {
                    try {
                        // 使用临时集合避免并发修改异常
                        Map<Long, NDPEntry> tempEntries = new HashMap<>(entriesMap);
                        for (NDPEntry ndpEntry : tempEntries.values()) {
                            if (ndpEntry.getTime() + ENTRY_TIMEOUT < System.currentTimeMillis()) {
                                Log.d(TAG, "Removing " + ndpEntry.getAddress().toString() + " from NDP cache");
                                // 移除过期表项
                                macAddressToInetAddress.remove(ndpEntry.getMac());
                                inetAddressToMacAddress.remove(ndpEntry.getAddress());
                                entriesMap.remove(ndpEntry.getMac());
                                ipEntriesMap.remove(ndpEntry.getAddress());
                            }
                        }
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "NDP Timeout Thread Interrupted");
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in NDP Timeout Thread: " + e.getMessage(), e);
                        // 继续执行，不要让单个异常终止整个线程
                    }
                }
                Log.d(TAG, "NDP Timeout Thread Ended.");
            }
        };
        timeoutThread.start();
    }

    /* access modifiers changed from: protected */
    public void stop() {
        running = false;
        try {
            if (timeoutThread != null && timeoutThread.isAlive()) {
                timeoutThread.interrupt();
                timeoutThread.join(1000); // 等待最多1秒
                if (timeoutThread.isAlive()) {
                    Log.w(TAG, "NDP Timeout Thread did not terminate gracefully");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); // 保持中断状态
        } catch (Exception e) {
            Log.e(TAG, "Error stopping NDP Timeout Thread: " + e.getMessage(), e);
        }
        
        // 清理集合
        entriesMap.clear();
        inetAddressToMacAddress.clear();
        ipEntriesMap.clear();
        macAddressToInetAddress.clear();
    }

    /* access modifiers changed from: package-private */
    public void setAddress(InetAddress inetAddress, long j) {
        if (inetAddress == null) {
            return; // 避免空指针异常
        }
        inetAddressToMacAddress.put(inetAddress, j);
        macAddressToInetAddress.put(j, inetAddress);
        NDPEntry ndpEntry = new NDPEntry(j, inetAddress);
        entriesMap.put(j, ndpEntry);
        ipEntriesMap.put(inetAddress, ndpEntry);
    }

    /* access modifiers changed from: package-private */
    public boolean hasMacForAddress(InetAddress inetAddress) {
        if (inetAddress == null) {
            return false;
        }
        return inetAddressToMacAddress.containsKey(inetAddress);
    }

    /* access modifiers changed from: package-private */
    public boolean hasAddressForMac(long j) {
        return macAddressToInetAddress.containsKey(j);
    }

    /* access modifiers changed from: package-private */
    public long getMacForAddress(InetAddress inetAddress) {
        if (inetAddress == null) {
            return -1;
        }
        
        if (!inetAddressToMacAddress.containsKey(inetAddress)) {
            return -1;
        }
        
        Long macAddress = inetAddressToMacAddress.get(inetAddress);
        if (macAddress != null) {
            updateNDPEntryTime(macAddress);
            return macAddress;
        }
        return -1;
    }

    /* access modifiers changed from: package-private */
    public InetAddress getAddressForMac(long j) {
        if (!macAddressToInetAddress.containsKey(j)) {
            return null;
        }
        
        InetAddress inetAddress = macAddressToInetAddress.get(j);
        if (inetAddress != null) {
            updateNDPEntryTime(inetAddress);
        }
        return inetAddress;
    }

    private void updateNDPEntryTime(InetAddress inetAddress) {
        if (inetAddress == null) {
            return;
        }
        
        NDPEntry ndpEntry = ipEntriesMap.get(inetAddress);
        if (ndpEntry != null) {
            ndpEntry.updateTime();
        }
    }

    private void updateNDPEntryTime(long j) {
        NDPEntry ndpEntry = entriesMap.get(j);
        if (ndpEntry != null) {
            ndpEntry.updateTime();
        }
    }

    /* access modifiers changed from: package-private */
    public byte[] getNeighborSolicitationPacket(InetAddress inetAddress, InetAddress inetAddress2, long j) {
        if (inetAddress == null || inetAddress2 == null) {
            Log.e(TAG, "Invalid addresses for Neighbor Solicitation packet");
            return new byte[72]; // 返回空包而不是空引用
        }
        
        byte[] bArr = new byte[72];
        
        try {
            System.arraycopy(inetAddress.getAddress(), 0, bArr, 0, 16);
            System.arraycopy(inetAddress2.getAddress(), 0, bArr, 16, 16);
            System.arraycopy(ByteBuffer.allocate(4).putInt(32).array(), 0, bArr, 32, 4);
            bArr[39] = 58;
            bArr[40] = -121;
            System.arraycopy(inetAddress2.getAddress(), 0, bArr, 48, 16);
            byte[] array = ByteBuffer.allocate(8).putLong(j).array();
            bArr[64] = 1;
            bArr[65] = 1;
            System.arraycopy(array, 2, bArr, 66, 6);
            
            // 计算校验和
            System.arraycopy(ByteBuffer.allocate(2).putShort((short) ((int) IPPacketUtils.calculateChecksum(bArr, 0, 0, 72))).array(), 0, bArr, 42, 2);
            
            // 重置前40字节
            for (int i = 0; i < 40; i++) {
                bArr[i] = 0;
            }
            
            bArr[0] = 96;
            System.arraycopy(ByteBuffer.allocate(2).putShort((short) 32).array(), 0, bArr, 4, 2);
            bArr[6] = 58;
            bArr[7] = -1;
            System.arraycopy(inetAddress.getAddress(), 0, bArr, 8, 16);
            System.arraycopy(inetAddress2.getAddress(), 0, bArr, 24, 16);
        } catch (Exception e) {
            Log.e(TAG, "Error creating Neighbor Solicitation packet: " + e.getMessage(), e);
        }
        
        return bArr;
    }
}
