package com.example.couplecredit.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.GridView;
import android.widget.ImageView;  // 添加这行导入语句
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BackgroundImageAdapter;
import com.example.couplecredit.function.CustomToast;
import com.example.couplecredit.utils.BackgroundUpdateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天背景设置Activity
 * 功能：
 * 1. 提供从手机相册选择背景图片的功能
 * 2. 提供预设背景图片选择功能
 * 3. 保存用户选择的背景设置到SharedPreferences
 */
public class ChatBackgroundActivity extends AppCompatActivity {
    
    // 请求码常量定义
    private static final int REQUEST_GALLERY = 1001;      // 相册选择请求码
    private static final int REQUEST_PERMISSION = 1002;   // 权限请求码
    
    // UI组件声明
    private LinearLayout llSelectFromGallery;  // 从相册选择容器
    private GridView gvBackgroundImages;       // 背景图片网格视图
    private BackgroundImageAdapter adapter;    // 背景图片适配器
    private List<Integer> backgroundImages;    // 预设背景图片资源ID列表
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_background);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        
        // 初始化各个组件
        initViews();
        initData();
        setupListeners();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews() {
        llSelectFromGallery = findViewById(R.id.ll_select_from_gallery);
        gvBackgroundImages = findViewById(R.id.gv_background_images);
        
        // 添加返回按钮点击事件
        ImageView ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 返回上一页面
                finish();
            }
        });
        
        // 设置标题栏和返回按钮
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("设置聊天背景");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }
    
    /**
     * 初始化数据
     * 设置预设背景图片列表和适配器
     */
    private void initData() {
        // 初始化预设背景图片列表
        backgroundImages = new ArrayList<>();
        // 添加各种颜色的圆形背景
        backgroundImages.add(R.drawable.circle_bg_pink);    // 粉色背景
        backgroundImages.add(R.drawable.circle_bg_blue);    // 蓝色背景
        backgroundImages.add(R.drawable.circle_bg_green);   // 绿色背景
        backgroundImages.add(R.drawable.circle_bg_purple);  // 紫色背景
        backgroundImages.add(R.drawable.circle_bg_orange);  // 橙色背景
        backgroundImages.add(R.drawable.circle_bg_teal);    // 青色背景
        backgroundImages.add(R.drawable.img_chat_background1);
        backgroundImages.add(R.drawable.img_chat_background2);
        backgroundImages.add(R.drawable.img_chat_background3);
        backgroundImages.add(R.drawable.img_chat_background4);
        backgroundImages.add(R.drawable.img_chat_background5);
        
        // 创建并设置适配器
        adapter = new BackgroundImageAdapter(this, backgroundImages);
        gvBackgroundImages.setAdapter(adapter);
    }
    
    /**
     * 设置各种监听器
     */
    private void setupListeners() {
        // 从相册选择点击事件
        llSelectFromGallery.setOnClickListener(v -> {
            // 检查是否有存储权限
            if (checkPermission()) {
                openGallery();  // 有权限直接打开相册
            } else {
                requestPermission();  // 没有权限先请求权限
            }
        });
        
        // 预设背景图片点击事件
        gvBackgroundImages.setOnItemClickListener((parent, view, position, id) -> {
            try {
                // 添加调试日志
                android.util.Log.d("ChatBackground", "GridView item clicked: position=" + position);
                
                // 检查位置是否有效
                if (position < 0 || position >= backgroundImages.size()) {
                    CustomToast.show(this, "选择的背景无效", Toast.LENGTH_SHORT);
                    return;
                }
                
                // 获取选中的背景资源ID
                int selectedBackground = backgroundImages.get(position);
                android.util.Log.d("ChatBackground", "Selected background resource ID: " + selectedBackground);
                
                // 保存选择的背景
                saveChatBackground(selectedBackground);
                
                // 发送背景更新广播
                BackgroundUpdateManager.notifyBackgroundUpdated(this, selectedBackground);
                
                // 显示成功提示
                CustomToast.show(this, "背景设置成功", Toast.LENGTH_SHORT);
                
                // 关闭当前页面
                finish();
                
            } catch (Exception e) {
                android.util.Log.e("ChatBackground", "Error setting background", e);
                CustomToast.show(this, "设置背景失败: " + e.getMessage(), Toast.LENGTH_LONG);
            }
        });
    }
    
    /**
     * 检查是否有读取外部存储的权限
     * @return true表示有权限，false表示没有权限
     */
    private boolean checkPermission() {
        // Android 13 (API 33) 及以上版本使用 READ_MEDIA_IMAGES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            // Android 13 以下版本使用 READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 请求读取外部存储权限
     */
    private void requestPermission() {
        // Android 13 (API 33) 及以上版本请求 READ_MEDIA_IMAGES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQUEST_PERMISSION);
        } else {
            // Android 13 以下版本请求 READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION);
        }
    }
    
    /**
     * 打开系统相册选择图片
     */
    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_GALLERY);
    }
    
    /**
     * 保存预设背景资源ID到SharedPreferences
     * @param backgroundResId 背景资源ID
     */
    private void saveChatBackground(int backgroundResId) {
        android.util.Log.d("ChatBackground", "开始保存预设背景，资源ID: " + backgroundResId);
        SharedPreferences prefs = getSharedPreferences("chat_settings", MODE_PRIVATE);
        boolean success = prefs.edit().putInt("chat_background", backgroundResId).commit();
        android.util.Log.d("ChatBackground", "保存结果: " + success);
        
        // 验证保存是否成功
        int savedValue = prefs.getInt("chat_background", -1);
        android.util.Log.d("ChatBackground", "验证保存的值: " + savedValue);
    }
    
    /**
     * 保存自定义背景图片URI到SharedPreferences
     * @param backgroundUri 背景图片URI字符串
     */
    private void saveChatBackground(String backgroundUri) {
        SharedPreferences prefs = getSharedPreferences("chat_settings", MODE_PRIVATE);
        prefs.edit().putString("chat_background_uri", backgroundUri).apply();
        prefs.edit().remove("chat_background").apply(); // 清除预设背景设置
    }
    
    /**
     * 处理Activity返回结果
     * 主要处理相册选择图片的结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 处理相册选择结果
        if (requestCode == REQUEST_GALLERY && resultCode == RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                // 保存选择的图片URI
                saveChatBackground(selectedImageUri.toString());
                // 发送背景更新广播
                BackgroundUpdateManager.notifyBackgroundUpdated(this, selectedImageUri.toString());
                Toast.makeText(this, "背景设置成功", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
    
    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限获取成功，打开相册
                openGallery();
            } else {
                // 权限被拒绝，显示提示
                CustomToast.show(this, "需要存储权限才能选择图片", Toast.LENGTH_SHORT);
            }
        }
    }
    
    /**
     * 处理标题栏返回按钮点击事件
     */
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}