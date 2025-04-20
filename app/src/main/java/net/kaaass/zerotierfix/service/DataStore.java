package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.zerotier.sdk.DataStoreGetListener;
import com.zerotier.sdk.DataStorePutListener;

import net.kaaass.zerotierfix.util.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Zerotier 文件数据源
 */
public class DataStore implements DataStoreGetListener, DataStorePutListener {

    private static final String TAG = "DataStore";

    private final Context context;

    public DataStore(Context context) {
        this.context = context;
    }

    @Override
    public int onDataStorePut(String name, byte[] buffer, boolean secure) {
        if (name == null || buffer == null) {
            Log.e(TAG, "写入文件时参数为空: " + (name == null ? "文件名为空" : "缓冲区为空"));
            return -3;
        }

        Log.d(TAG, "Writing File: " + name + ", to: " + this.context.getFilesDir());
        // 保护自定义 Planet 文件
        if (hookPlanetFile(name)) {
            return 0;
        }
        try {
            if (name.contains("/")) {
                // 处理路径中的 ".." 和 "." 避免路径遍历漏洞
                if (name.contains("..")) {
                    Log.e(TAG, "文件路径不安全: " + name);
                    return -3;
                }
                
                // 创建目录
                File directory = new File(this.context.getFilesDir(), name.substring(0, name.lastIndexOf('/')));
                if (!directory.exists()) {
                    boolean created = directory.mkdirs();
                    if (!created) {
                        Log.e(TAG, "无法创建目录: " + directory.getAbsolutePath());
                        return -4;
                    }
                }
                
                // 写入文件
                File targetFile = new File(directory, name.substring(name.lastIndexOf('/') + 1));
                try (FileOutputStream fileOutputStream = new FileOutputStream(targetFile)) {
                    fileOutputStream.write(buffer);
                    fileOutputStream.flush();
                }
                return 0;
            } else {
                // 写入到应用私有存储区
                try (FileOutputStream openFileOutput = this.context.openFileOutput(name, 0)) {
                    openFileOutput.write(buffer);
                    openFileOutput.flush();
                }
                return 0;
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "文件未找到: " + name, e);
            return -1;
        } catch (IOException e2) {
            Log.e(TAG, "IO异常: " + name, e2);
            return -2;
        } catch (IllegalArgumentException | SecurityException e3) {
            Log.e(TAG, "参数或权限错误: " + name, e3);
            return -3;
        } catch (Exception e4) {
            Log.e(TAG, "未知错误: " + name, e4);
            return -5;
        }
    }

    @Override
    public int onDelete(String name) {
        if (name == null) {
            Log.e(TAG, "删除文件失败: 文件名为空");
            return 1;
        }

        boolean deleted;
        Log.d(TAG, "Deleting File: " + name);
        
        // 保护自定义 Planet 文件
        if (hookPlanetFile(name)) {
            return 0;
        }
        
        try {
            if (name.contains("/")) {
                // 处理路径中的 ".." 和 "." 避免路径遍历漏洞
                if (name.contains("..")) {
                    Log.e(TAG, "文件路径不安全: " + name);
                    return 1;
                }
                
                File file = new File(this.context.getFilesDir(), name);
                if (!file.exists()) {
                    deleted = true;
                } else {
                    deleted = file.delete();
                    if (!deleted) {
                        Log.e(TAG, "无法删除文件: " + file.getAbsolutePath());
                    }
                }
            } else {
                deleted = this.context.deleteFile(name);
                if (!deleted) {
                    Log.e(TAG, "无法删除文件: " + name);
                }
            }
            return !deleted ? 1 : 0;
        } catch (SecurityException e) {
            Log.e(TAG, "删除文件时权限错误: " + name, e);
            return 1;
        }
    }

    @Override
    public long onDataStoreGet(String name, byte[] out_buffer) {
        if (name == null || out_buffer == null) {
            Log.e(TAG, "读取文件时参数为空: " + (name == null ? "文件名为空" : "缓冲区为空"));
            return -3;
        }
        
        Log.d(TAG, "Reading File: " + name);
        if (hookPlanetFile(name)) {
            name = Constants.FILE_CUSTOM_PLANET;
        }
        
        // 读入文件
        try {
            if (name.contains("/")) {
                // 处理路径中的 ".." 和 "." 避免路径遍历漏洞
                if (name.contains("..")) {
                    Log.e(TAG, "文件路径不安全: " + name);
                    return -3;
                }
                
                // 确保目录存在
                File directory = new File(this.context.getFilesDir(), name.substring(0, name.lastIndexOf('/')));
                if (!directory.exists()) {
                    boolean created = directory.mkdirs();
                    if (!created) {
                        Log.e(TAG, "无法创建目录: " + directory.getAbsolutePath());
                        return -4;
                    }
                }
                
                File file = new File(directory, name.substring(name.lastIndexOf('/') + 1));
                if (!file.exists() || !file.isFile() || !file.canRead()) {
                    Log.w(TAG, "文件不存在或无法读取: " + file.getAbsolutePath());
                    return 0;
                }
                
                try (FileInputStream fileInputStream = new FileInputStream(file)) {
                    int read = fileInputStream.read(out_buffer);
                    return read;
                }
            } else {
                try (FileInputStream openFileInput = this.context.openFileInput(name)) {
                    int read = openFileInput.read(out_buffer);
                    return read;
                }
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "文件未找到: " + name);
            return -1;
        } catch (IOException e) {
            Log.e(TAG, "读取文件时IO异常: " + name, e);
            return -2;
        } catch (Exception e) {
            Log.e(TAG, "读取文件时未知错误: " + name, e);
            return -3;
        }
    }

    /**
     * 判断自定义 Planet 文件
     */
    boolean hookPlanetFile(String name) {
        if (Constants.FILE_PLANET.equals(name)) {
            return PreferenceManager
                    .getDefaultSharedPreferences(this.context)
                    .getBoolean(Constants.PREF_PLANET_USE_CUSTOM, false);
        }
        return false;
    }
}
