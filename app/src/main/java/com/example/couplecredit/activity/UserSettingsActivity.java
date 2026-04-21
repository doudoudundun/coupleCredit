package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.MediaStore;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.app.Activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.api.AvatarUploadApi;
import com.example.couplecredit.utils.NicknameCache;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.utils.AvatarUpdateManager;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.config.DatabaseConfig;
import com.example.couplecredit.activity.LoginActivity;

public class UserSettingsActivity extends AppCompatActivity {

    // UI组件声明
    private TextView tvUserInfo;           // 用户信息显示
    private LinearLayout llCoupleBinding;  // 情侣绑定选项
    private LinearLayout llChangeNickname; // 修改昵称选项
    private LinearLayout llChangeProfile;  // 修改头像选项
    private LinearLayout llChangePassword; // 修改密码选项
    private LinearLayout llServerSettings; // 服务器设置选项
    private TextView tvServerUrl;          // 服务器地址显示
    private LinearLayout llSignOut;        // 退出登录选项
    private LinearLayout llLogout;         // 注销用户选项
    private LinearLayout llCoupleInfo, llUnbindCouple;
    private TextView tvCoupleInfo, tvCoupleHint;
    private ImageView ivUserAvatar, ivCoupleAvatar;
    
    // 用户信息
    private String username;
    private String userId;
    
    // 权限和图片选择相关常量
    private static final int REQUEST_PERMISSION_READ_EXTERNAL_STORAGE = 1001;
    private static final int REQUEST_IMAGE_PICK = 1002;
    // 使用统一的常量配置

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_settings);

        // 优先从Intent获取用户信息，否则从UserInfoManager获取
        Intent intent = getIntent();
        username = intent.getStringExtra("username");
        userId = intent.getStringExtra("id");
        
        // 如果Intent中没有用户信息，从UserInfoManager获取
        if (username == null || userId == null) {
            username = UserInfoManager.getCurrentUsername(this);
            int userIdInt = UserInfoManager.getCurrentUserId(this);
            if (userIdInt != -1) {
                userId = String.valueOf(userIdInt);
            }
        }
        
        // 检查用户是否已登录，如果未登录则直接返回登录页面
        if (username == null || userId == null || !UserInfoManager.isUserLoggedIn(this)) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
            finish();
            return;
        }
        
        // 初始化UI组件
        initViews();
        
        // 设置点击事件
        setupListeners();
        
        // 更新用户信息显示
        updateUserInfo();

        // 更新服务器地址显示
        updateServerUrlDisplay();

        // 加载情侣信息
        loadCoupleInfo();
        
        // 加载保存的头像
        loadSavedAvatar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCoupleInfo();
    }

    /**
     * 初始化UI组件
     */
    private void initViews() {
        tvUserInfo = findViewById(R.id.tv_user_info);
        ivUserAvatar = findViewById(R.id.iv_user_avatar);
        tvCoupleHint = findViewById(R.id.tv_couple_hint);
        llCoupleInfo = findViewById(R.id.ll_couple_info);
        tvCoupleInfo = findViewById(R.id.tv_couple_info);
        ivCoupleAvatar = findViewById(R.id.iv_couple_avatar);
        llUnbindCouple = findViewById(R.id.ll_unbind_couple);
        llCoupleBinding = findViewById(R.id.ll_couple_binding);
        llChangeNickname = findViewById(R.id.ll_change_nickname);
        llChangeProfile = findViewById(R.id.ll_change_profile);
        llChangePassword = findViewById(R.id.ll_change_password);
        llServerSettings = findViewById(R.id.ll_server_settings);
        tvServerUrl = findViewById(R.id.tv_server_url);
        llSignOut = findViewById(R.id.ll_sign_out);
        llLogout = findViewById(R.id.ll_logout);
    }

    /**
     * 设置点击事件监听器
     */
    private void setupListeners() {
        // 情侣绑定点击事件
        llCoupleBinding.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, CoupleBindingActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });
        
        // 修改昵称点击事件
        llChangeNickname.setOnClickListener(v -> showChangeNicknameDialog());
        
        // 修改头像点击事件
        llChangeProfile.setOnClickListener(v -> openImagePicker());
        
        // 修改密码点击事件
        llChangePassword.setOnClickListener(v -> {
            Intent intent = new Intent(UserSettingsActivity.this, ChangePasswordActivity.class);
            intent.putExtra("username", username);
            startActivity(intent);
        });

        // 服务器设置点击事件
        llServerSettings.setOnClickListener(v -> showServerSettingsDialog());

        // 退出登录点击事件
        llSignOut.setOnClickListener(v -> showSignOutDialog());
        
        // 解绑情侣关系点击事件
        llUnbindCouple.setOnClickListener(v -> showUnbindDialog());
        
        // 注销用户点击事件
        llLogout.setOnClickListener(v -> {
            showLogoutDialog();
        });
    }
    
    /**
     * 打开图片选择器
     */
    private void openImagePicker() {
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上使用新的媒体权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 
                        REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
                return;
            }
        } else {
            // Android 12及以下使用传统权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 
                        REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
                return;
            }
        }
        
        // 权限已授予，打开图片选择器
        launchImagePicker();
    }
    
    /**
     * 启动图片选择器
     */
    private void launchImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        
        // 检查是否有应用可以处理这个Intent
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_IMAGE_PICK);
        } else {
            Toast.makeText(this, "没有找到可用的图片选择应用", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSION_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，打开图片选择器
                launchImagePicker();
            } else {
                // 权限被拒绝
                Toast.makeText(this, "需要存储权限才能选择头像", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 处理Activity结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                uploadAvatarToServer(selectedImageUri);
                setUserAvatar(selectedImageUri);
            }
        }
    }
    
    /**
     * 保存头像URI到SharedPreferences
     * @param uri 图片URI
     */
    private void saveAvatarUri(Uri uri) {
        if (userId != null) {
            SharedPreferences prefs = getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
            prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, uri.toString()).apply();
        }
    }
    
    /**
     * 加载保存的头像
     */
    private void loadSavedAvatar() {
        if (userId != null) {
            try {
                int userIdInt = Integer.parseInt(userId);
                SharedPreferences prefs = getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                String savedUriString = prefs.getString(DatabaseConfig.PREF_AVATAR_URI + userId, null);
                AvatarCacheManager.getInstance(this).loadAvatar(this, ivUserAvatar, userIdInt, savedUriString);
            } catch (Exception e) {
                ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        }
    }
    
    /**
     * 更新用户信息显示
     */
    private void updateUserInfo() {
        if (username != null && userId != null) {
            // 通过 HTTP API 获取用户昵称，如果有昵称则显示昵称，否则显示用户名
            int userIdInt = Integer.parseInt(userId);
            AuthApiClient.getUserProfile(this, userIdInt, new AuthApiClient.ProfileCallback() {
                @Override
                public void onSuccess(AuthApiModels.UserProfileData profile) {
                    runOnUiThread(() -> {
                        String nickname = profile.nickname;
                        String displayName = (nickname != null && !nickname.trim().isEmpty()) ? nickname : username;
                        String userInfoText = displayName + "\nID: " + userId;
                        tvUserInfo.setText(userInfoText);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        // 查询昵称失败，使用用户名显示
                        String userInfoText = "用户名: " + username + "\nID: " + userId;
                        tvUserInfo.setText(userInfoText);
                    });
                }
            });
        }
    }
    
    /**
     * 加载情侣信息
     */
    private void loadCoupleInfo() {
        int userIdInt = Integer.parseInt(userId);
        AuthApiClient.queryCoupleInfo(this, userIdInt, new AuthApiClient.CoupleInfoCallback() {
            @Override
            public void onCoupleFound(int partnerId, String partnerName, String partnerNickname, String partnerAvatarUrl, int relationshipId) {
                runOnUiThread(() -> {
                    String displayName = (partnerNickname != null && !partnerNickname.trim().isEmpty()) ? partnerNickname : partnerName;
                    tvCoupleInfo.setText(displayName);
                    tvCoupleHint.setVisibility(View.VISIBLE);
                    llCoupleInfo.setVisibility(View.VISIBLE);
                    llUnbindCouple.setVisibility(View.VISIBLE);
                    llCoupleBinding.setVisibility(View.GONE);
                });
            }

            @Override
            public void onNoCoupleFound() {
                runOnUiThread(() -> {
                    tvCoupleHint.setVisibility(View.GONE);
                    llCoupleInfo.setVisibility(View.GONE);
                    llUnbindCouple.setVisibility(View.GONE);
                    llCoupleBinding.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvCoupleHint.setVisibility(View.GONE);
                    llCoupleInfo.setVisibility(View.GONE);
                    llUnbindCouple.setVisibility(View.GONE);
                    llCoupleBinding.setVisibility(View.VISIBLE);
                });
            }
        });
    }
    
    /**
     * 显示解绑情侣关系确认对话框
     */
    private void showUnbindDialog() {
        new AlertDialog.Builder(this)
                .setTitle("解绑情侣关系")
                .setMessage("确定要解绑情侣关系吗？解绑后将无法查看对方的账单记录。")
                .setPositiveButton("确定", (dialog, which) -> performUnbind())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示退出登录确认对话框
     */
    private void showSignOutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("确认退出")
                .setMessage("确定要退出登录吗？")
                .setPositiveButton("确定", (dialog, which) -> performSignOut())
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 显示注销确认对话框
     */
    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("确认注销")
                .setMessage("注销后将删除您的所有数据，此操作不可恢复。确定要注销吗？")
                .setPositiveButton("确定", (dialog, which) -> performLogout())
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 执行解绑情侣关系操作
     */
    private void performUnbind() {
        int userIdInt = Integer.parseInt(userId);
        AuthApiClient.unbindCouple(this, userIdInt, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "解绑成功", Toast.LENGTH_SHORT).show();
                    // 重新加载情侣信息，隐藏相关UI
                    loadCoupleInfo();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "解绑失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 执行退出登录操作
     */
    private void performSignOut() {
        // 使用UserInfoManager清除用户信息
        UserInfoManager.clearUserInfo(this);

        // 发送退出登录广播
        Intent broadcastIntent = new Intent("com.example.couplecredit.USER_LOGOUT");
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();

        // 跳转到登录界面
        Intent intent = new Intent(UserSettingsActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * 执行注销操作
     */
    private void performLogout() {
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "用户信息异常，无法注销", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示进度提示
        Toast.makeText(this, "正在注销账户...", Toast.LENGTH_SHORT).show();

        // 通过 HTTP API 删除用户账户
        int userIdInt = Integer.parseInt(userId);
        AuthApiClient.deleteAccount(this, userIdInt, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    // 使用UserInfoManager清空用户登录状态
                    UserInfoManager.clearUserInfo(UserSettingsActivity.this);

                    // 发送退出登录广播
                    Intent broadcastIntent = new Intent("com.example.couplecredit.USER_LOGOUT");
                    LocalBroadcastManager.getInstance(UserSettingsActivity.this).sendBroadcast(broadcastIntent);

                    Toast.makeText(UserSettingsActivity.this, "账户注销成功", Toast.LENGTH_SHORT).show();

                    // 返回登录界面
                    Intent intent = new Intent(UserSettingsActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "注销失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 显示修改昵称对话框
     */
    private void showChangeNicknameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("修改昵称");
        
        // 创建输入框
        final EditText input = new EditText(this);
        input.setHint("请输入新昵称");
        builder.setView(input);
        
        builder.setPositiveButton("确定", (dialog, which) -> {
            String newNickname = input.getText().toString().trim();
            if (newNickname.isEmpty()) {
                Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newNickname.length() > 20) {
                Toast.makeText(this, "昵称长度不能超过20个字符", Toast.LENGTH_SHORT).show();
                return;
            }
            updateNickname(newNickname);
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 更新用户昵称
     */
    private void updateNickname(String newNickname) {
        if (username == null || username.isEmpty()) {
            Toast.makeText(this, "用户信息异常，无法修改昵称", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(this, "正在更新昵称...", Toast.LENGTH_SHORT).show();

        int userIdInt = Integer.parseInt(userId);
        AuthApiClient.updateNickname(this, userIdInt, newNickname, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "昵称修改成功", Toast.LENGTH_SHORT).show();

                    // 清空昵称缓存，确保下次获取最新昵称
                    NicknameCache.clearNicknameCache(UserSettingsActivity.this, username);

                    // 刷新用户信息显示
                    updateUserInfo();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(UserSettingsActivity.this, "昵称修改失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 上传头像到服务器
     * @param imageUri 图片URI
     */
    private void uploadAvatarToServer(Uri imageUri) {
        if (userId == null) {
            Toast.makeText(this, "用户信息异常，无法上传头像", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int userIdInt = Integer.parseInt(userId);
            AvatarUploadApi.uploadAvatar(this, userIdInt, imageUri, new AvatarUploadApi.AvatarUploadCallback() {
                @Override
                 public void onUploadSuccess(String avatarUrl) {
                    runOnUiThread(() -> {
                        AvatarCacheManager.getInstance(UserSettingsActivity.this).clearUserAvatarCache(userIdInt);
                        SharedPreferences prefs = getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                        prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, avatarUrl).apply();
                        AvatarUpdateManager.notifyAvatarUpdated(UserSettingsActivity.this, userIdInt, avatarUrl);
                        AvatarCacheManager.getInstance(UserSettingsActivity.this).loadAvatar(UserSettingsActivity.this, ivUserAvatar, userIdInt, avatarUrl);
                        Toast.makeText(UserSettingsActivity.this, "头像上传成功", Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onUploadError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(UserSettingsActivity.this, "头像上传失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } catch (NumberFormatException e) {
            Log.e("UserSettingsActivity", "Invalid userId format: " + userId, e);
            Toast.makeText(this, "用户ID格式错误", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 设置用户头像
     * @param imageUri 图片URI
     */
    private void setUserAvatar(Uri imageUri) {
        if (imageUri != null && ivUserAvatar != null) {
            try {
                // 使用Glide加载并设置头像
                Glide.with(this)
                    .load(imageUri)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(ivUserAvatar);
            } catch (Exception e) {
                e.printStackTrace();
                // 如果加载失败，使用默认头像
                ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        }
    }

    /**
     * 显示服务器设置对话框
     */
    private void showServerSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("服务器设置");

        // 显示当前地址
        String currentUrl = ApiConfigManager.getBaseUrl(this);
        boolean isCustom = ApiConfigManager.hasCustomUrl(this);
        String defaultUrl = ApiConfigManager.getDefaultUrl();

        // 创建输入框
        final EditText input = new EditText(this);
        input.setText(currentUrl);
        input.setHint("输入服务器地址（默认：https://api.datafun.online）");

        // 创建提示文本
        TextView tvHint = new TextView(this);
        tvHint.setText("默认地址: " + defaultUrl + "\n优先使用稳定域名 https://api.datafun.online\n只有在你明确切换到其他服务器时，才需要手动修改这里。");
        tvHint.setTextSize(12);
        tvHint.setPadding(50, 10, 50, 10);

        // 创建容器
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(tvHint);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("保存", (dialog, which) -> {
            String newUrl = input.getText().toString().trim();
            if (newUrl.isEmpty()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            // 简单验证 URL 格式
            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                Toast.makeText(this, "地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
                return;
            }
            ApiConfigManager.setCustomBaseUrl(this, newUrl);
            updateServerUrlDisplay();
            Toast.makeText(this, "服务器地址已更新，建议测试连接", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", null);

        builder.setNeutralButton("测试连接", (dialog, which) -> {
            // 测试当前输入的地址
            String testUrl = input.getText().toString().trim();
            if (testUrl.isEmpty() || (!testUrl.startsWith("http://") && !testUrl.startsWith("https://"))) {
                Toast.makeText(this, "请输入有效的地址后再测试", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show();

            // 临时保存并测试
            ApiConfigManager.setCustomBaseUrl(this, testUrl);
            ApiConfigManager.testConnection(this, new ApiConfigManager.ConnectionTestCallback() {
                @Override
                public void onSuccess(String url) {
                    runOnUiThread(() -> {
                        updateServerUrlDisplay();
                        Toast.makeText(UserSettingsActivity.this, "连接成功！", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(UserSettingsActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        if (isCustom) {
            // 添加恢复默认选项
            builder.setNeutralButton("测试连接", null);
            // 在对话框显示后设置按钮点击
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        // 如果有自定义URL，添加第四个按钮
        if (isCustom) {
            Button restoreButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            // 我们需要重新设置对话框以包含三个按钮：保存、取消、恢复默认
            // 简化处理：不在这里添加恢复默认
        }
    }

    /**
     * 更新服务器地址显示
     */
    private void updateServerUrlDisplay() {
        String currentUrl = ApiConfigManager.getBaseUrl(this);
        boolean isCustom = ApiConfigManager.hasCustomUrl(this);
        if (isCustom) {
            tvServerUrl.setText("当前: " + currentUrl);
            tvServerUrl.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        } else {
            tvServerUrl.setText("当前: 默认地址");
            tvServerUrl.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
    }
}