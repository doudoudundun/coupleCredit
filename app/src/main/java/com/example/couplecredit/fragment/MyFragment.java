package com.example.couplecredit.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.ChatBackgroundActivity;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.activity.ToastDemoActivity;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.api.AvatarUploadApi;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.DatabaseConfig;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.utils.AvatarUpdateManager;
import com.example.couplecredit.utils.NicknameCache;
import com.example.couplecredit.utils.UserInfoManager;

/**
 * 我的页面Fragment
 * 提供用户个人设置和功能入口
 */
public class MyFragment extends Fragment {

    private LinearLayout llChatBackground;
    private LinearLayout llToastDemo;
    private LinearLayout llLogin;
    private LinearLayout llUserSettings;
    private LinearLayout llInventory;
    private LinearLayout llCoupleInfo;
    private TextView tvLoginText;
    private TextView tvCoupleInfo;
    private View viewSettingsDivider;
    private ImageView ivUserAvatar;

    private String username;
    private String userId;
    private boolean isLoggedIn = false;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            username = args.getString("username");
            userId = args.getString("id");
            isLoggedIn = (username != null && userId != null);
        }

        initViews(view);
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

    private void initViews(View view) {
        llChatBackground = view.findViewById(R.id.ll_chat_background);
        llToastDemo = view.findViewById(R.id.ll_toast_demo);
        llLogin = view.findViewById(R.id.ll_login);
        llUserSettings = view.findViewById(R.id.ll_user_settings);
        llInventory = view.findViewById(R.id.ll_inventory);
        llCoupleInfo = view.findViewById(R.id.ll_couple_info);
        tvLoginText = view.findViewById(R.id.tv_login_text);
        tvCoupleInfo = view.findViewById(R.id.tv_couple_info);
        viewSettingsDivider = view.findViewById(R.id.view_settings_divider);
        ivUserAvatar = view.findViewById(R.id.iv_user_avatar);
    }

    private void setupListeners() {
        llChatBackground.setOnClickListener(v -> startActivity(new Intent(getActivity(), ChatBackgroundActivity.class)));

        llToastDemo.setOnClickListener(v -> startActivity(new Intent(getActivity(), ToastDemoActivity.class)));

        llInventory.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToInventory();
            }
        });

        llLogin.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            } else {
                Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                intent.putExtra("username", username);
                intent.putExtra("id", userId);
                startActivity(intent);
            }
        });

        llUserSettings.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });

        ivUserAvatar.setOnClickListener(v -> {
            if (isLoggedIn) {
                openImagePicker();
            } else {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            }
        });
    }

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
                                SharedPreferences prefs = requireContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                                prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, avatarUrl).apply();
                                AvatarCacheManager.getInstance(requireContext()).clearUserAvatarCache(userIdInt);
                                AvatarUpdateManager.notifyAvatarUpdated(getContext(), userIdInt, avatarUrl);
                                AvatarCacheManager.getInstance(requireContext()).loadAvatar(requireContext(), ivUserAvatar, userIdInt, avatarUrl);
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "头像上传失败: " + error, Toast.LENGTH_SHORT).show());
                        }
                    }
                });
            } catch (NumberFormatException e) {
                Log.e("MyFragment", "Invalid userId format: " + userId, e);
                Toast.makeText(getContext(), "用户ID格式错误", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openImagePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
                return;
            }
        }

        launchImagePicker();
    }

    private void launchImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");

        if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_IMAGE_PICK);
        } else {
            Toast.makeText(getContext(), "没有找到可用的图片选择应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchImagePicker();
            } else {
                Toast.makeText(getContext(), "需要存储权限才能选择头像", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                uploadAvatarToServer(selectedImageUri);
            }
        }
    }

    private void setUserAvatar(Uri imageUri, boolean showToast) {
        if (imageUri != null && ivUserAvatar != null) {
            try {
                Glide.with(this)
                        .load(imageUri)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_profile)
                        .error(R.drawable.ic_profile)
                        .into(ivUserAvatar);

                if (showToast) {
                    Toast.makeText(getContext(), "头像设置成功", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e("MyFragment", "设置头像失败", e);
                if (showToast) {
                    Toast.makeText(getContext(), "头像设置失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void setUserAvatar(Uri imageUri) {
        setUserAvatar(imageUri, true);
    }

    private void saveAvatarUri(Uri uri) {
        if (getContext() != null && userId != null) {
            SharedPreferences prefs = getContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
            prefs.edit().putString(DatabaseConfig.PREF_AVATAR_URI + userId, uri.toString()).apply();
        }
    }

    private void loadSavedAvatar() {
        if (getContext() != null && userId != null && isLoggedIn) {
            try {
                int userIdInt = Integer.parseInt(userId);
                SharedPreferences prefs = getContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                String savedUri = prefs.getString(DatabaseConfig.PREF_AVATAR_URI + userId, null);
                AvatarCacheManager.getInstance(getContext()).loadAvatar(getContext(), ivUserAvatar, userIdInt, savedUri);
            } catch (NumberFormatException e) {
                ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        } else {
            ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
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

        updateLoginUI();
        loadSavedAvatar();
    }

    private void updateLoginUI() {
        if (isLoggedIn && username != null && userId != null) {
            if (getContext() != null) {
                String htmlText = "<big><b>" + username + "</b></big><br><small>ID: " + userId + "</small>";
                Spanned spannedText = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY);
                tvLoginText.setText(spannedText);
                llUserSettings.setVisibility(View.VISIBLE);
                viewSettingsDivider.setVisibility(View.VISIBLE);
                loadCoupleInfo();

                String cachedNickname = NicknameCache.getCachedNickname(getContext(), username);
                if (cachedNickname != null) {
                    String cachedHtmlText = "<big><b>" + cachedNickname + "</b></big><br><small>ID: " + userId + "</small>";
                    Spanned cachedSpannedText = Html.fromHtml(cachedHtmlText, Html.FROM_HTML_MODE_LEGACY);
                    tvLoginText.setText(cachedSpannedText);
                } else {
                    int profileUserId = UserInfoManager.getCurrentUserId(getContext());
                    AuthApiClient.getUserProfile(getContext(), profileUserId, new AuthApiClient.ProfileCallback() {
                        @Override
                        public void onSuccess(AuthApiModels.UserProfileData profile) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    String nickname = profile.nickname;
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
                        public void onError(String e) {
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
            tvLoginText.setText("点我立即登录");
            llUserSettings.setVisibility(View.GONE);
            viewSettingsDivider.setVisibility(View.GONE);
            ivUserAvatar.setImageResource(R.drawable.ic_default_avatar);
            llCoupleInfo.setVisibility(View.GONE);
        }
    }

    private void loadCoupleInfo() {
        if (!isLoggedIn || getContext() == null) {
            llCoupleInfo.setVisibility(View.GONE);
            return;
        }
        int uid = UserInfoManager.getCurrentUserId(getContext());
        AuthApiClient.queryCoupleInfo(getContext(), uid, new AuthApiClient.CoupleInfoCallback() {
            @Override
            public void onCoupleFound(int partnerId, String partnerName, String partnerNickname, String partnerAvatarUrl, int relationshipId) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    String display = partnerNickname != null && !partnerNickname.isEmpty() ? partnerNickname : partnerName;
                    tvCoupleInfo.setText("已绑定: " + display);
                    llCoupleInfo.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onNoCoupleFound() {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    tvCoupleInfo.setText("未绑定情侣");
                    llCoupleInfo.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> llCoupleInfo.setVisibility(View.GONE));
            }
        });
    }
}
