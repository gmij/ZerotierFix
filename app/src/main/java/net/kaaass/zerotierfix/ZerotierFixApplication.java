package net.kaaass.zerotierfix;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.multidex.MultiDexApplication;

import net.kaaass.zerotierfix.model.DaoMaster;
import net.kaaass.zerotierfix.model.DaoSession;
import net.kaaass.zerotierfix.model.ZTOpenHelper;
import net.kaaass.zerotierfix.util.LogManager;

/**
 * 主程序入口
 *
 * @author kaaass
 */
public class ZerotierFixApplication extends MultiDexApplication {
    private static final String TAG = "ZerotierFixApplication";
    private DaoSession mDaoSession;
    private int activeActivities = 0;
    private ActivityLifecycleCallbacks activityLifecycleCallbacks;

    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Starting Application");
        
        // 创建 DAO 会话
        this.mDaoSession = new DaoMaster(
                new ZTOpenHelper(this, "ztfixdb", null)
                        .getWritableDatabase()
        ).newSession();
        
        // 注册活动生命周期回调，用于跟踪应用状态
        activityLifecycleCallbacks = new AppActivityLifecycleCallbacks();
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks);
    }
    
    @Override
    public void onTerminate() {
        // 应用终止时执行清理
        Log.i(TAG, "Application terminating, performing cleanup");
        cleanupResources();
        super.onTerminate();
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
    
    /**
     * 清理应用资源
     */
    private void cleanupResources() {
        try {
            // 关闭日志管理器
            LogManager.getInstance().shutdown();
            Log.i(TAG, "LogManager shutdown completed");
        } catch (Exception e) {
            Log.e(TAG, "Error shutting down LogManager", e);
        }
    }
    
    /**
     * 应用程序活动生命周期回调
     */
    private class AppActivityLifecycleCallbacks implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            // 记录当前活动的活动数量
            activeActivities++;
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            // 不需要处理
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            // 不需要处理
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            // 不需要处理
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            // 不需要处理
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            // 不需要处理
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            // 活动销毁时，减少活动计数
            activeActivities--;
            
            // 如果没有活跃的活动了，可能是应用进入后台，执行部分资源清理
            if (activeActivities == 0) {
                Log.i(TAG, "No active activities, app may be going to background");
                // 如果应用可能进入后台，可以在这里执行部分资源释放
                // 但不要完全关闭LogManager，因为应用可能会重新回到前台
            }
        }
    }
}
