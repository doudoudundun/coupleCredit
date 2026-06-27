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
import com.example.couplecredit.activity.AssetsActivity;
import com.example.couplecredit.activity.CalorieActivity;
import com.example.couplecredit.activity.ChatBackgroundActivity;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.activity.PeriodActivity;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.api.AvatarUploadApi;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.DatabaseConfig;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.utils.AvatarUpdateManager;
import com.example.couplecredit.utils.CalorieFormatUtils;
import com.example.couplecredit.utils.NicknameCache;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.Calendar;
import java.util.List;

/**
 * 我的页面Fragment
 * 提供用户个人设置和功能入口
 */
public class MyFragment extends Fragment {

    private LinearLayout llChatBackground;
    private LinearLayout llCalorieEntry;
    private LinearLayout llPeriodEntry;
    private LinearLayout llLogin;
    private LinearLayout llQuickAddBill;
    private LinearLayout llQuickReport;
    private LinearLayout llQuickTodo;
    private LinearLayout llQuickRecipe;
    private LinearLayout llQuickEatOut;
    private LinearLayout llOverviewBill;
    private LinearLayout llOverviewTodo;
    private LinearLayout llOverviewInventory;
    private LinearLayout llOverviewCouple;
    private LinearLayout llOverviewAsset;
    private LinearLayout llUserSettings;
    private LinearLayout llInventory;
    private LinearLayout llCoupleInfo;
    private TextView tvLoginText;
    private TextView tvCoupleInfo;
    private TextView tvCalorieEntrySummary;
    private TextView tvPeriodEntrySummary;
    private TextView tvOverviewBillSummary;
    private TextView tvOverviewTodoSummary;
    private TextView tvOverviewInventorySummary;
    private TextView tvOverviewCoupleSummary;
    private TextView tvAssetOverviewSummary;
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
        llCalorieEntry = view.findViewById(R.id.ll_calorie_entry);
        llPeriodEntry = view.findViewById(R.id.ll_period_entry);
        llLogin = view.findViewById(R.id.ll_login);
        llQuickAddBill = view.findViewById(R.id.ll_quick_add_bill);
        llQuickReport = view.findViewById(R.id.ll_quick_report);
        llQuickTodo = view.findViewById(R.id.ll_quick_todo);
        llQuickRecipe = view.findViewById(R.id.ll_quick_recipe);
        llQuickEatOut = view.findViewById(R.id.ll_quick_eat_out);
        llOverviewBill = view.findViewById(R.id.ll_overview_bill);
        llOverviewTodo = view.findViewById(R.id.ll_overview_todo);
        llOverviewInventory = view.findViewById(R.id.ll_overview_inventory);
        llOverviewCouple = view.findViewById(R.id.ll_overview_couple);
        llOverviewAsset = view.findViewById(R.id.ll_overview_asset);
        llUserSettings = view.findViewById(R.id.ll_user_settings);
        llInventory = view.findViewById(R.id.ll_inventory);
        llCoupleInfo = view.findViewById(R.id.ll_couple_info);
        tvLoginText = view.findViewById(R.id.tv_login_text);
        tvCoupleInfo = view.findViewById(R.id.tv_couple_info);
        tvCalorieEntrySummary = view.findViewById(R.id.tv_calorie_entry_summary);
        tvPeriodEntrySummary = view.findViewById(R.id.tv_period_entry_summary);
        tvOverviewBillSummary = view.findViewById(R.id.tv_overview_bill_summary);
        tvOverviewTodoSummary = view.findViewById(R.id.tv_overview_todo_summary);
        tvOverviewInventorySummary = view.findViewById(R.id.tv_overview_inventory_summary);
        tvOverviewCoupleSummary = view.findViewById(R.id.tv_overview_couple_summary);
        tvAssetOverviewSummary = view.findViewById(R.id.tv_asset_overview_summary);
        viewSettingsDivider = view.findViewById(R.id.view_settings_divider);
        ivUserAvatar = view.findViewById(R.id.iv_user_avatar);
    }

    private void setupListeners() {
        llChatBackground.setOnClickListener(v -> startActivity(new Intent(getActivity(), ChatBackgroundActivity.class)));

        llQuickAddBill.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToAddBillTab();
            }
        });

        llQuickReport.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToReportTab();
            }
        });

        llOverviewBill.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToReportTab();
            }
        });

        llCalorieEntry.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            } else {
                startActivity(new Intent(getActivity(), CalorieActivity.class));
            }
        });

        llPeriodEntry.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
            } else {
                startActivity(new Intent(getActivity(), PeriodActivity.class));
            }
        });

        llInventory.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToInventory();
            }
        });

        llQuickTodo.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToTodo();
            }
        });

        llOverviewTodo.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToTodo();
            }
        });

        llQuickRecipe.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToRecipe();
            }
        });

        llQuickEatOut.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showEatOutFragment();
            }
        });

        llOverviewInventory.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToInventory();
            }
        });

        llOverviewCouple.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
                return;
            }
            Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
            intent.putExtra("username", username);
            intent.putExtra("id", userId);
            startActivity(intent);
        });

        llOverviewAsset.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                loginLauncher.launch(intent);
                return;
            }
            startActivity(new Intent(getActivity(), AssetsActivity.class));
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
        loadSummaryFromCache();
        loadCalorieSummary();
        loadPeriodSummary();
        loadOverviewSummary();
    }

    private void loadSummaryFromCache() {
        if (!isLoggedIn || getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("my_fragment_cache", Context.MODE_PRIVATE);
        tvCalorieEntrySummary.setText(prefs.getString("calorie", null));
        tvPeriodEntrySummary.setText(prefs.getString("period", null));
        tvOverviewBillSummary.setText(prefs.getString("bill", null));
        tvOverviewTodoSummary.setText(prefs.getString("todo", null));
        tvOverviewInventorySummary.setText(prefs.getString("inventory", null));
        tvAssetOverviewSummary.setText(prefs.getString("asset", null));
        String couple = prefs.getString("couple", null);
        if (couple != null) {
            tvOverviewCoupleSummary.setText(couple);
            llCoupleInfo.setVisibility(View.VISIBLE);
        }
    }

    private void saveCache(String key, String value) {
        if (getContext() == null) return;
        requireContext().getSharedPreferences("my_fragment_cache", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
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
            tvCalorieEntrySummary.setText("登录后查看今日热量");
            tvPeriodEntrySummary.setText("登录后查看经期信息");
            resetOverviewSummary();
        }
    }

    private void loadCalorieSummary() {
        if (!isLoggedIn || getContext() == null) {
            tvCalorieEntrySummary.setText("登录后查看今日热量");
            return;
        }
        int currentUserId = UserInfoManager.getCurrentUserId(getContext());
        AuthApiClient.getTodayCalories(getContext(), currentUserId, new AuthApiClient.CalorieSummaryCallback() {
            @Override
            public void onSuccess(AuthApiModels.CalorieSummaryResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (response != null && response.data != null) {
                        String text = "今日 " + CalorieFormatUtils.formatNumber(response.data.totalCalories) + " / " + CalorieFormatUtils.formatNumber(response.data.dailyGoal) + " kcal";
                        tvCalorieEntrySummary.setText(text);
                        saveCache("calorie", text);
                    } else {
                        tvCalorieEntrySummary.setText("今日 0 / 2000 kcal");
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvCalorieEntrySummary.setText("今日 0 / 2000 kcal"));
            }
        });
    }

    private void loadPeriodSummary() {
        if (!isLoggedIn || getContext() == null) {
            tvPeriodEntrySummary.setText("登录后查看经期信息");
            return;
        }
        int currentUserId = UserInfoManager.getCurrentUserId(getContext());
        AuthApiClient.getPeriodRecords(getContext(), currentUserId, new AuthApiClient.PeriodListCallback() {
            @Override
            public void onSuccess(AuthApiModels.PeriodListResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (response != null && response.data != null) {
                        String text;
                        if (response.data.predictedNextStart != null) {
                            text = "预计下次: " + response.data.predictedNextStart;
                        } else if (!response.data.records.isEmpty()) {
                            text = "记录更多经期来预测下次日期";
                        } else {
                            text = "点击开始记录经期";
                        }
                        tvPeriodEntrySummary.setText(text);
                        saveCache("period", text);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvPeriodEntrySummary.setText("经期信息暂时无法获取"));
            }
        });
    }

    private void loadOverviewSummary() {
        if (!isLoggedIn || getContext() == null) {
            resetOverviewSummary();
            return;
        }
        int currentUserId = UserInfoManager.getCurrentUserId(getContext());
        loadBillOverview(currentUserId);
        loadTodoOverview(currentUserId);
        loadInventoryOverview(currentUserId);
        loadAssetOverview(currentUserId);
    }

    private void resetOverviewSummary() {
        tvOverviewBillSummary.setText("登录后查看本月收支");
        tvOverviewTodoSummary.setText("登录后查看待办状态");
        tvOverviewInventorySummary.setText("登录后查看库存变化");
        tvOverviewCoupleSummary.setText("登录后查看绑定关系");
        tvAssetOverviewSummary.setText("登录后查看资产统计");
    }

    private void loadBillOverview(int currentUserId) {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        AuthApiClient.queryBills(getContext(), currentUserId, year, month, new AuthApiClient.BillsQueryCallback() {
            @Override
            public void onSuccess(AuthApiModels.BillsQueryResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    List<AuthApiModels.BillData> bills = response != null && response.data != null ? response.data.bills : null;
                    if (bills == null || bills.isEmpty()) {
                        tvOverviewBillSummary.setText("本月暂无账单");
                        return;
                    }
                    double expense = 0;
                    double income = 0;
                    for (AuthApiModels.BillData bill : bills) {
                        if (bill.incomeType == 1) {
                            income += bill.amount;
                        } else {
                            expense += bill.amount;
                        }
                    }
                    String text = "支出 ¥" + CalorieFormatUtils.formatNumber(expense) + " · 收入 ¥" + CalorieFormatUtils.formatNumber(income);
                    tvOverviewBillSummary.setText(text);
                    saveCache("bill", text);
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvOverviewBillSummary.setText("本月收支暂时无法获取"));
            }
        });
    }

    private void loadTodoOverview(int currentUserId) {
        AuthApiClient.queryTodos(getContext(), currentUserId, new AuthApiClient.TodoListCallback() {
            @Override
            public void onSuccess(AuthApiModels.TodoListResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    List<AuthApiModels.TodoItemData> items = response != null && response.data != null ? response.data.items : null;
                    if (items == null || items.isEmpty()) {
                        tvOverviewTodoSummary.setText("暂无待办事项");
                        return;
                    }
                    int openCount = 0;
                    int doneCount = 0;
                    int missedCount = 0;
                    for (AuthApiModels.TodoItemData item : items) {
                        if ("done".equals(item.status)) {
                            doneCount++;
                        } else if ("missed".equals(item.status)) {
                            missedCount++;
                        } else {
                            openCount++;
                        }
                    }
                    String suffix = missedCount > 0 ? " · 逾期 " + missedCount : "";
                    String text = "待处理 " + openCount + " · 已完成 " + doneCount + suffix;
                    tvOverviewTodoSummary.setText(text);
                    saveCache("todo", text);
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvOverviewTodoSummary.setText("待办状态暂时无法获取"));
            }
        });
    }

    private void loadInventoryOverview(int currentUserId) {
        AuthApiClient.queryInventory(getContext(), currentUserId, new AuthApiClient.InventoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.InventoryListResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    List<AuthApiModels.InventoryItemData> items = response != null && response.data != null ? response.data.items : null;
                    if (items == null || items.isEmpty()) {
                        tvOverviewInventorySummary.setText("家里还没有库存记录");
                        return;
                    }
                    int lowStockCount = 0;
                    int expiringCount = 0;
                    for (AuthApiModels.InventoryItemData item : items) {
                        if (item.isLowStock) {
                            lowStockCount++;
                        }
                        if (item.isExpired || item.isExpiring) {
                            expiringCount++;
                        }
                    }
                    StringBuilder summary = new StringBuilder();
                    summary.append(items.size()).append(" 项物资");
                    if (lowStockCount > 0) {
                        summary.append(" · ").append(lowStockCount).append(" 项偏低");
                    }
                    if (expiringCount > 0) {
                        summary.append(" · ").append(expiringCount).append(" 项临期");
                    }
                    String text = summary.toString();
                    tvOverviewInventorySummary.setText(text);
                    saveCache("inventory", text);
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvOverviewInventorySummary.setText("库存状态暂时无法获取"));
            }
        });
    }

    private void loadAssetOverview(int currentUserId) {
        if (!isLoggedIn || getContext() == null) {
            tvAssetOverviewSummary.setText("登录后查看资产统计");
            return;
        }
        AuthApiClient.getAssetStats(getContext(), currentUserId, new AuthApiClient.AssetStatsCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetStatsResponse response) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (response.data != null) {
                        String text = "总资产 ¥" + String.format("%.0f", response.data.totalValue) +
                                " · " + response.data.totalCount + " 件物品";
                        tvAssetOverviewSummary.setText(text);
                        saveCache("asset", text);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> tvAssetOverviewSummary.setText("资产数据暂时无法获取"));
            }
        });
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
                    String overview = "已与 " + display + " 绑定";
                    tvOverviewCoupleSummary.setText(overview);
                    saveCache("couple", overview);
                    llCoupleInfo.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onNoCoupleFound() {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    tvCoupleInfo.setText("未绑定情侣");
                    tvOverviewCoupleSummary.setText("还没有绑定情侣关系");
                    llCoupleInfo.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String message) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    tvOverviewCoupleSummary.setText("绑定关系暂时无法获取");
                    llCoupleInfo.setVisibility(View.GONE);
                });
            }
        });
    }
}
