package net.kaaass.zerotierfix.model;

import org.greenrobot.greendao.DaoException;
import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 应用路由配置
 * 存储每个应用是否通过 ZeroTier VPN 路由
 */
@Entity
public class AppRouting {
    @Id
    private Long id;

    /**
     * 网络 ID，关联到具体的 ZeroTier 网络
     */
    private Long networkId;

    /**
     * 应用包名
     */
    private String packageName;

    /**
     * 是否通过 VPN 路由
     * true: 通过 VPN
     * false: 不通过 VPN (直连)
     */
    private boolean routeViaVpn;

    /** Used to resolve relations */
    @Generated(hash = 2040040024)
    private transient DaoSession daoSession;

    /** Used for active entity operations. */
    @Generated(hash = 1859599726)
    private transient AppRoutingDao myDao;

    @Generated(hash = 1812155437)
    public AppRouting(Long id, Long networkId, String packageName, boolean routeViaVpn) {
        this.id = id;
        this.networkId = networkId;
        this.packageName = packageName;
        this.routeViaVpn = routeViaVpn;
    }

    @Generated(hash = 1414960172)
    public AppRouting() {
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNetworkId() {
        return this.networkId;
    }

    public void setNetworkId(Long networkId) {
        this.networkId = networkId;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public boolean getRouteViaVpn() {
        return this.routeViaVpn;
    }

    public void setRouteViaVpn(boolean routeViaVpn) {
        this.routeViaVpn = routeViaVpn;
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#delete(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 128553479)
    public void delete() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.delete(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#refresh(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 1942392019)
    public void refresh() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.refresh(this);
    }

    /**
     * Convenient call for {@link org.greenrobot.greendao.AbstractDao#update(Object)}.
     * Entity must attached to an entity context.
     */
    @Generated(hash = 713229351)
    public void update() {
        if (myDao == null) {
            throw new DaoException("Entity is detached from DAO context");
        }
        myDao.update(this);
    }

    /** called by internal mechanisms, do not call yourself. */
    @Generated(hash = 844808733)
    public void __setDaoSession(DaoSession daoSession) {
        this.daoSession = daoSession;
        myDao = daoSession != null ? daoSession.getAppRoutingDao() : null;
    }
}
