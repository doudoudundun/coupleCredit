package com.example.couplecredit.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * 网络状态管理器
 * 负责监听网络连接状态变化，提供网络可用性检测功能
 */
public class NetworkStateManager {
    
    private static NetworkStateManager instance;
    private final Context context;
    private final ConnectivityManager connectivityManager;
    private final MutableLiveData<Boolean> isNetworkAvailable = new MutableLiveData<>(false);
    private final MutableLiveData<String> networkType = new MutableLiveData<>("NONE");
    private NetworkCallback networkCallback;
    
    private NetworkStateManager(Context context) {
        this.context = context.getApplicationContext();
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        initializeNetworkCallback();
        checkInitialNetworkState();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized NetworkStateManager getInstance(Context context) {
        if (instance == null) {
            instance = new NetworkStateManager(context);
        }
        return instance;
    }
    
    /**
     * 获取网络可用性LiveData
     */
    public LiveData<Boolean> getNetworkAvailability() {
        return isNetworkAvailable;
    }
    
    /**
     * 获取网络类型LiveData
     */
    public LiveData<String> getNetworkType() {
        return networkType;
    }
    
    /**
     * 检查当前网络是否可用
     */
    public boolean isNetworkCurrentlyAvailable() {
        if (connectivityManager == null) {
            return false;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null && 
                   (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
    }
    
    /**
     * 获取当前网络类型
     */
    public String getCurrentNetworkType() {
        if (connectivityManager == null) {
            return "NONE";
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return "NONE";
            }
            
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) {
                return "NONE";
            }
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WIFI";
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "CELLULAR";
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ETHERNET";
            }
        } else {
            android.net.NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            if (activeNetworkInfo != null && activeNetworkInfo.isConnected()) {
                switch (activeNetworkInfo.getType()) {
                    case ConnectivityManager.TYPE_WIFI:
                        return "WIFI";
                    case ConnectivityManager.TYPE_MOBILE:
                        return "CELLULAR";
                    case ConnectivityManager.TYPE_ETHERNET:
                        return "ETHERNET";
                }
            }
        }
        
        return "NONE";
    }
    
    /**
     * 初始化网络回调
     */
    private void initializeNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new NetworkCallback();
        }
    }
    
    /**
     * 检查初始网络状态
     */
    private void checkInitialNetworkState() {
        boolean isAvailable = isNetworkCurrentlyAvailable();
        String type = getCurrentNetworkType();
        
        isNetworkAvailable.postValue(isAvailable);
        networkType.postValue(type);
    }
    
    /**
     * 开始监听网络状态变化
     */
    public void startNetworkMonitoring() {
        if (connectivityManager == null) {
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            connectivityManager.registerNetworkCallback(builder.build(), networkCallback);
        }
    }
    
    /**
     * 停止监听网络状态变化
     */
    public void stopNetworkMonitoring() {
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                // 忽略取消注册时的异常
            }
        }
    }
    
    /**
     * 网络状态回调类
     */
    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            updateNetworkState();
        }
        
        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            updateNetworkState();
        }
        
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            updateNetworkState();
        }
        
        private void updateNetworkState() {
            boolean isAvailable = isNetworkCurrentlyAvailable();
            String type = getCurrentNetworkType();
            
            isNetworkAvailable.postValue(isAvailable);
            networkType.postValue(type);
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stopNetworkMonitoring();
    }
}