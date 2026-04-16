package com.example.couplecredit.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Html;
import android.text.Spanned;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.activity.ChatBackgroundActivity;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.InventoryActivity;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.activity.ToastDemoActivity;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.api.AvatarUploadApi;
import com.example.couplecredit.database.MySQLDatabaseHelper;
import com.example.couplecredit.utils.NicknameCache;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.utils.AvatarUpdateManager;
import android.util.Log;
import com.example.couplecredit.config.DatabaseConfig;

/**
 * 我的页面Fragment
 * 提供用户个人设置和功能入口
 */
public class MyFragment extends Fragment {
    
    // UI组件声明
    private LinearLayout llChatBackground;  // 聊天背景设置选项容器
    private LinearLayout llToastDemo;       // Toast演示选项容器
    private LinearLayout llLogin;           // 登录选项容器
    private LinearLayout llUserSettings;    // 个人设置选项容器
    private LinearLayout llInventory;       // 存货清单选项容器
    private TextView tvLoginText;           // 登录文本
    private View viewSettingsDivider;       // 设置分割线
    private ImageView ivUserAvatar;         // 用户头像
    
    // 用户信息
    private String username;
    private String userId;
    private boolean isLoggedIn = false;
    
    // 权限和图片选择相关常量
    private static final int REQUEST_PERMISSION_READ_EXTERNAL_STORAGE = 1001;
    private static final int REQUEST_IMAGE_PICK = 1002;
    private final BroadcastReceiver loginStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkUserLoginStatus();
        }
    };
    private final ActivityResultLauncher<Intent> loginLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK) {
                    checkUserLoginStatus();
                    return;
                }

                Intent data = result.getData();
                if (data != null) {
                    username = data.getStringExtra("username");
                    userId = data.getStringExtra("id");
                    isLoggedIn = (username != null && userId != null);
                }

                checkUserLoginStatus();
            });
    // 使用统一的常量配置

    /**
     * 创建Fragment视图
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 填充布局文件
        return inflater.inflate(R.layout.fragment_my, container, false);
    }
    
    /**
     * 上传头像到服务器
     * @param imageUri 图片URI
     */
    private void uploadAvatarToServer(Uri imageUri) {
        if (isLoggedIn && userId != null) {
            try {
                int userIdInt = Integer.parseInt(userId);
                
                AvatarUploadApi.uploadAvatar(getContext(), userIdInt, imageUri, new AvatarUploadApi.UploadCallback() {
                    @Override
                    public void onSuccess(String avatarUrl) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "头像上传成功", Toast.LENGTH_SHORT).show();
                                // 发送头像更新广播
                                AvatarUpdateManager.notifyAvatarUpdated(getContext(), userIdInt, imageUri.toString());
                            });
                        }
                    }
                    
                    @Override
                    public void onError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "头像上传失败: " + error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                });
            } catch (NumberFormatException e) {
                Log.e("MyFragment", "Invalid userId format: " + userId, e);
                Toast.makeText(getContext(), "用户ID格式错误", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 视图创建完成后的初始化工作
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 获取传递的用户信息
        Bundle args = getArguments();
        if (args != null) {
            username = args.getString("username");
            userId = args.getString("id");
            isLoggedIn = (username != null && userId != null);
        }
        
        // 初始化UI组件
        initViews(view);
        // 设置事件监听器
        setupListeners();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                loginStateReceiver,
                new IntentFilter("com.example.couplecredit.USER_LOGIN")
        );
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                loginStateReceiver,
                new IntentFilter("com.example.couplecredit.USER_LOGOUT")
        );

        checkUserLoginStatus();
    }

    /**
     * 初始化UI组件
     * @param view 根视图
     */
    private void initViews(View view) {
        llChatBackground = view.findViewById(R.id.ll_chat_background);
        llToastDemo = view.findViewById(R.id.ll_toast_demo);
        llLogin = view.findViewById(R.id.ll_login);
        llUserSettings = view.findViewById(R.id.ll_user_settings);
        llInventory = view.findViewById(R.id.ll_inventory);
        tvLoginText = view.findViewById(R.id.tv_login_text);
        viewSettingsDivider = view.findViewById(R.id.view_settings_divider);
        ivUserAvatar = view.findViewById(R.id.iv_user_avatar);
    }
    
    /**
     * 设置各种事件监听器
     */
    private void setupListeners() {
        // 设置聊天背景选项点击事件
        llChatBackground.setOnClickListener(v -> {
            // 启动聊天背景设置Activity
            Intent intent = new Intent(getActivity(), ChatBackgroundActivity.class);
            startActivity(intent);
        });
        
        // 设置Toast演示选项点击事件
        llToastDemo.setOnClickListener(v -> {
            // 启动Toast演示Activity
            Intent intent = new Intent(getActivity(), ToastDemoActivity.class);
            startActivity(intent);
        });

        // 设置存货清单选项点击事件
        llInventory.setOnClickListener(v -> {
            // 启动存货清单Activity
            Intent intent = new Intent(getActivity(), InventoryActivity.class);
            startActivity(intent);
        });
        
        // 设置登录选项点击事件
        llLogin.setOnClickListener(v -> {
            if (!isLoggedIn) {
                // 未登录，启动登录Activity
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            } else {
                // 已登录，跳转到个人设置界面
                Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                intent.putExtra("username", username);
                intent.putExtra("id", userId);
                startActivity(intent);
            }
        });
        
        // 设置个人设置选项点击事件
        llUserSettings.setOnClickListener(v -> {
            // 跳转到个人设置界面
            Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });
        
        // 设置头像点击事件
        ivUserAvatar.setOnClickListener(v -> {
            if (isLoggedIn) {
                openImagePicker();
            } else {
                // 未登录时点击头像跳转到登录页面
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            }
        });
    }
    
    /**
     * 打开图片选择器
     */
    private void openImagePicker() {
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13及以上使用新的媒体权限
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), 
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 
                        REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
                return;
            }
        } else {
            // Android 12及以下使用传统权限
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), 
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
        if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_IMAGE_PICK);
        } else {
            Toast.makeText(getContext(), "没有找到可用的图片选择应用", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSION_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，打开图片选择器
                launchImagePicker();
            } else {
                // 权限被拒绝
                Toast.makeText(getContext(), "需要存储权限才能选择头像", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 处理Activity结果
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                // 上传头像到服务器
                uploadAvatarToServer(selectedImageUri);
                // 设置本地头像显示
                setUserAvatar(selectedImageUri);
                // 保存头像URI
                saveAvatarUri(selectedImageUri);
            }
        }
    }
    
    /**
     * 设置用户头像
     * @param imageUri 图片URI
     * @param showToast 是否显示Toast提示
     */
    private void setUserAvatar(Uri imageUri, boolean showToast) {
        if (imageUri != null && ivUserAvatar != null) {
            try {
                // 使用Glide加载并设置头像
                Glide.with(this)
                    .load(imageUri)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .into(ivUserAvatar);
                
                // 只有用户主动设置时才保存和发送广播
                if (showToast) {
                    // 保存头像URI到SharedPreferences
                    saveAvatarUri(imageUri);
                    
                    // 发送头像更新广播
                    if (isLoggedIn && userId != null) {
                        try {
                            int userIdInt = Integer.parseInt(userId);
                            AvatarUpdateManager.notifyAvatarUpdated(getContext(), userIdInt, imageUri.toString());
                        } catch (NumberFormatException e) {
                            e.printStackTrace();
                        }
                    }
                    
                    Toast.makeText(getContext(), "头像设置成功", Toast.LENGTH_SHORT).show();
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                if (showToast) {
                    Toast.makeText(getContext(), "头像设置失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    
    /**
     * 设置用户头像（用户主动设置时调用）
     * @param imageUri 图片URI
     */
    private void setUserAvatar(Uri imageUri) {
        setUserAvatar(imageUri, true);
    }
    
    /**
     * 保存头像URI到SharedPreferences
     * @param uri 图片URI
     */
    private void saveAvatarUri(Uri uri) {
        if (getContext() != null && userId != null) {
            SharedPreferences prefs = getContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
            prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, uri.toString()).apply();
        }
    }
    
    /**
     * 加载保存的头像
     */
    private void loadSavedAvatar() {
        if (getContext() != null && userId != null && isLoggedIn) {
            try {
                int userIdInt = Integer.parseInt(userId);
                
                // 获取本地保存的头像URI
                SharedPreferences prefs = getContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                String savedUri = prefs.getString(DatabaseConfig.PREF_AVATAR_URI + userId, null);
                
                // 使用AvatarCacheManager加载头像（优先服务器，其次本地）
                AvatarCacheManager.getInstance(getContext()).loadAvatar(
                    getContext(), ivUserAvatar, userIdInt, savedUri
                );
            } catch (NumberFormatException e) {
                // 如果userId格式错误，显示默认头像
                ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        } else {
            // 未登录或用户信息不完整，显示默认头像
            ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次Fragment可见时检查用户登录状态
        checkUserLoginStatus();
    }

    @Override
    public void onDestroyView() {
        Context context = getContext();
        if (context != null) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(loginStateReceiver);
        }
        super.onDestroyView();
    }

    /**
     * 检查用户登录状态
     */
    private void checkUserLoginStatus() {
        if (UserInfoManager.isUserLoggedIn(requireContext())) {
            String currentUsername = UserInfoManager.getCurrentUsername(requireContext());
            int currentUserIdInt = UserInfoManager.getCurrentUserId(requireContext());
            String currentUserId = currentUserIdInt != -1 ? String.valueOf(currentUserIdInt) : null;

            if (currentUsername != null && currentUserId != null) {
                username = currentUsername;
                userId = currentUserId;
                isLoggedIn = true;
            } else {
                username = null;
                userId = null;
                isLoggedIn = false;
            }
        } else {
            username = null;
            userId = null;
            isLoggedIn = false;
        }
        
        // 更新UI
        updateLoginUI();
        // 重新加载头像
        loadSavedAvatar();
    }
    
    /**
     * 更新登录相关UI显示
     */
    private void updateLoginUI() {
        if (isLoggedIn && username != null && userId != null) {
            if (getContext() != null) {
                String htmlText = "<big><b>" + username + "</b></big><br><small>ID: " + userId + "</small>";
                Spanned spannedText = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY);
                tvLoginText.setText(spannedText);
                llUserSettings.setVisibility(View.VISIBLE);
                viewSettingsDivider.setVisibility(View.VISIBLE);

                String cachedNickname = NicknameCache.getCachedNickname(getContext(), username);
                if (cachedNickname != null) {
                    String cachedHtmlText = "<big><b>" + cachedNickname + "</b></big><br><small>ID: " + userId + "</small>";
                    Spanned cachedSpannedText = Html.fromHtml(cachedHtmlText, Html.FROM_HTML_MODE_LEGACY);
                    tvLoginText.setText(cachedSpannedText);
                } else {
                    MySQLDatabaseHelper.getUserNickname(username, new MySQLDatabaseHelper.UserNicknameCallback() {
                        @Override
                        public void onSuccess(String nickname) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    String displayName = (nickname != null && !nickname.trim().isEmpty()) ? nickname : username;
                                    NicknameCache.cacheNickname(getContext(), username, displayName);
                                    String nicknameHtmlText = "<big><b>" + displayName + "</b></big><br><small>ID: " + userId + "</small>";
                                    Spanned nicknameSpannedText = Html.fromHtml(nicknameHtmlText, Html.FROM_HTML_MODE_LEGACY);
                                    tvLoginText.setText(nicknameSpannedText);
                                    llUserSettings.setVisibility(View.VISIBLE);
                                    viewSettingsDivider.setVisibility(View.VISIBLE);
                                });
                            }
                        }

                        @Override
                        public void onError(String error) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    NicknameCache.cacheNickname(getContext(), username, username);
                                    String fallbackHtmlText = "<big><b>" + username + "</b></big><br><small>ID: " + userId + "</small>";
                                    Spanned fallbackSpannedText = Html.fromHtml(fallbackHtmlText, Html.FROM_HTML_MODE_LEGACY);
                                    tvLoginText.setText(fallbackSpannedText);
                                    llUserSettings.setVisibility(View.VISIBLE);
                                    viewSettingsDivider.setVisibility(View.VISIBLE);
                                });
                            }
                        }
                    });
                }
            }
        } else if (tvLoginText != null) {
            // 未登录，显示登录提示
            tvLoginText.setText("点我立即登录");

            // 隐藏个人设置选项
            llUserSettings.setVisibility(View.GONE);
            viewSettingsDivider.setVisibility(View.GONE);

            // 重置头像为默认头像
            ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }
}
