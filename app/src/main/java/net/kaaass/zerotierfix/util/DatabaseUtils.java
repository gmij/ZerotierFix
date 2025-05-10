package net.kaaass.zerotierfix.util;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.kaaass.zerotierfix.model.AssignedAddress;
import net.kaaass.zerotierfix.model.AssignedAddressDao;
import net.kaaass.zerotierfix.model.DaoSession;

/**
 * 数据库访问工具类
 */
public class DatabaseUtils {
    public static final Lock readLock;
    public static final ReadWriteLock readWriteLock;
    public static final Lock writeLock;

    static {
        var reentrantReadWriteLock = new ReentrantReadWriteLock();
        readWriteLock = reentrantReadWriteLock;
        writeLock = reentrantReadWriteLock.writeLock();
        readLock = reentrantReadWriteLock.readLock();
    }
    
    /**
     * 从数据库中获取指定网络的分配地址列表
     * 
     * @param daoSession 数据访问会话
     * @param networkId 网络ID
     * @return 地址列表
     */
    public static List<AssignedAddress> getAddressesForNetwork(DaoSession daoSession, Long networkId) {
        if (daoSession == null || networkId == null) {
            return new ArrayList<>();
        }
        
        // 获取分配地址DAO
        AssignedAddressDao addressDao = daoSession.getAssignedAddressDao();
        
        // 查询指定网络ID的地址列表
        return addressDao.queryBuilder()
                .where(AssignedAddressDao.Properties.NetworkId.eq(networkId))
                .list();
    }
}
