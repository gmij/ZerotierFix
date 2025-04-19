package net.kaaass.zerotierfix.service;

import android.util.Log;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// TODO: clear up
public class ARPTable {
    public static final String TAG = "ARPTable";
    private static final long ENTRY_TIMEOUT = 120000;
    //private static final int REPLY = 2;
    private static final int REQUEST = 1;
    private final Map<Long, ARPEntry> entriesMap = new ConcurrentHashMap<>();
    private final Map<InetAddress, Long> inetAddressToMacAddress = new ConcurrentHashMap<>();
    private final Map<InetAddress, ARPEntry> ipEntriesMap = new ConcurrentHashMap<>();
    private final Map<Long, InetAddress> macAddressToInetAdddress = new ConcurrentHashMap<>();
    private final Thread timeoutThread;
    private volatile boolean running = true;

    public ARPTable() {
        timeoutThread = new Thread("ARP Timeout Thread") {
            public void run() {
                Log.d(TAG, "ARP Timeout Thread Started.");
                while (!isInterrupted() && running) {
                    try {
                        // 使用临时集合来避免并发修改异常
                        Map<Long, ARPEntry> tempEntries = new HashMap<>(entriesMap);
                        for (ARPEntry arpEntry : tempEntries.values()) {
                            if (arpEntry.getTime() + ENTRY_TIMEOUT < System.currentTimeMillis()) {
                                Log.d(TAG, "Removing " + arpEntry.getAddress().toString() + " from ARP cache");
                                // 移除过期条目
                                macAddressToInetAdddress.remove(arpEntry.getMac());
                                inetAddressToMacAddress.remove(arpEntry.getAddress());
                                entriesMap.remove(arpEntry.getMac());
                                ipEntriesMap.remove(arpEntry.getAddress());
                            }
                        }
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "ARP Timeout Thread Interrupted");
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in ARP Timeout Thread: " + e.getMessage(), e);
                        // 继续执行，不要让单个异常终止整个线程
                    }
                }
                Log.d(TAG, "ARP Timeout Thread Ended.");
            }
        };
        timeoutThread.start();
    }

    public static byte[] longToBytes(long j) {
        ByteBuffer allocate = ByteBuffer.allocate(8);
        allocate.putLong(j);
        return allocate.array();
    }

    public void stop() {
        running = false;
        try {
            if (timeoutThread != null && timeoutThread.isAlive()) {
                timeoutThread.interrupt();
                timeoutThread.join(1000); // 等待最多1秒
                if (timeoutThread.isAlive()) {
                    Log.w(TAG, "ARP Timeout Thread did not terminate gracefully");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); // 保持中断状态
        } catch (Exception e) {
            Log.e(TAG, "Error stopping ARP Timeout Thread: " + e.getMessage(), e);
        }
        
        // 清理集合
        entriesMap.clear();
        inetAddressToMacAddress.clear();
        ipEntriesMap.clear();
        macAddressToInetAdddress.clear();
    }

    /* access modifiers changed from: package-private */
    public void setAddress(InetAddress inetAddress, long j) {
        if (inetAddress == null) {
            return; // 避免空指针异常
        }
        inetAddressToMacAddress.put(inetAddress, j);
        macAddressToInetAdddress.put(j, inetAddress);
        ARPEntry arpEntry = new ARPEntry(j, inetAddress);
        entriesMap.put(j, arpEntry);
        ipEntriesMap.put(inetAddress, arpEntry);
    }

    private void updateArpEntryTime(long j) {
        ARPEntry arpEntry = entriesMap.get(j);
        if (arpEntry != null) {
            arpEntry.updateTime();
        }
    }

    private void updateArpEntryTime(InetAddress inetAddress) {
        if (inetAddress == null) {
            return; // 避免空指针异常
        }
        ARPEntry arpEntry = ipEntriesMap.get(inetAddress);
        if (arpEntry != null) {
            arpEntry.updateTime();
        }
    }

    /* access modifiers changed from: package-private */
    public long getMacForAddress(InetAddress inetAddress) {
        if (inetAddress == null) {
            return -1; // 避免空指针异常
        }
        
        if (!inetAddressToMacAddress.containsKey(inetAddress)) {
            return -1;
        }
        
        Log.d(TAG, "Returning MAC for " + inetAddress.toString());
        var longValue = inetAddressToMacAddress.get(inetAddress);
        if (longValue != null) {
            updateArpEntryTime(longValue);
            return longValue;
        }
        return -1;
    }

    /* access modifiers changed from: package-private */
    public InetAddress getAddressForMac(long j) {
        if (!macAddressToInetAdddress.containsKey(j)) {
            return null;
        }
        
        InetAddress inetAddress = macAddressToInetAdddress.get(j);
        if (inetAddress != null) {
            updateArpEntryTime(inetAddress);
        }
        return inetAddress;
    }

    public boolean hasMacForAddress(InetAddress inetAddress) {
        if (inetAddress == null) {
            return false;
        }
        return inetAddressToMacAddress.containsKey(inetAddress);
    }

    public boolean hasAddressForMac(long j) {
        return macAddressToInetAdddress.containsKey(j);
    }

    public byte[] getRequestPacket(long j, InetAddress inetAddress, InetAddress inetAddress2) {
        return getARPPacket(1, j, 0, inetAddress, inetAddress2);
    }

    public byte[] getReplyPacket(long j, InetAddress inetAddress, long j2, InetAddress inetAddress2) {
        return getARPPacket(2, j, j2, inetAddress, inetAddress2);
    }

    public byte[] getARPPacket(int i, long j, long j2, InetAddress inetAddress, InetAddress inetAddress2) {
        if (inetAddress == null || inetAddress2 == null) {
            Log.e(TAG, "Invalid addresses for ARP packet");
            return new byte[28]; // 返回空包而不是空引用
        }
        
        byte[] bArr = new byte[28];
        bArr[0] = 0;
        bArr[1] = 1;
        bArr[2] = 8;
        bArr[3] = 0;
        bArr[4] = 6;
        bArr[5] = 4;
        bArr[6] = 0;
        bArr[7] = (byte) i;
        
        try {
            System.arraycopy(longToBytes(j), 2, bArr, 8, 6);
            System.arraycopy(inetAddress.getAddress(), 0, bArr, 14, 4);
            System.arraycopy(longToBytes(j2), 2, bArr, 18, 6);
            System.arraycopy(inetAddress2.getAddress(), 0, bArr, 24, 4);
        } catch (Exception e) {
            Log.e(TAG, "Error creating ARP packet: " + e.getMessage(), e);
        }
        
        return bArr;
    }

    public ARPReplyData processARPPacket(byte[] packetData) {
        if (packetData == null || packetData.length < 28) {
            Log.e(TAG, "Invalid ARP packet");
            return null;
        }
        
        Log.d(TAG, "Processing ARP packet");

        InetAddress srcAddress = null;
        InetAddress dstAddress = null;
        
        try {
            // 解析包内 IP、MAC 地址
            byte[] rawSrcMac = new byte[8];
            System.arraycopy(packetData, 8, rawSrcMac, 2, 6);
            byte[] rawSrcAddress = new byte[4];
            System.arraycopy(packetData, 14, rawSrcAddress, 0, 4);
            byte[] rawDstMac = new byte[8];
            System.arraycopy(packetData, 18, rawDstMac, 2, 6);
            byte[] rawDstAddress = new byte[4];
            System.arraycopy(packetData, 24, rawDstAddress, 0, 4);
            
            try {
                srcAddress = InetAddress.getByAddress(rawSrcAddress);
            } catch (Exception e) {
                Log.e(TAG, "Error creating source address: " + e.getMessage(), e);
            }
            
            try {
                dstAddress = InetAddress.getByAddress(rawDstAddress);
            } catch (Exception e) {
                Log.e(TAG, "Error creating destination address: " + e.getMessage(), e);
            }
            
            long srcMac = ByteBuffer.wrap(rawSrcMac).getLong();
            long dstMac = ByteBuffer.wrap(rawDstMac).getLong();

            // 更新 ARP 表项
            if (srcMac != 0 && srcAddress != null) {
                setAddress(srcAddress, srcMac);
            }
            
            if (dstMac != 0 && dstAddress != null) {
                setAddress(dstAddress, dstMac);
            }

            // 处理响应行为
            var packetType = packetData[7];
            if (packetType == REQUEST) {
                // ARP 请求，返回应答数据
                Log.d(TAG, "Reply needed");
                return new ARPReplyData(srcMac, srcAddress);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing ARP packet: " + e.getMessage(), e);
        }
        
        return null;
    }
}
