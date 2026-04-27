package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.InventoryAdapter;
import com.example.couplecredit.adapter.RecentActivityAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.InventoryUtils;
import com.example.couplecredit.utils.DateTimeUtils;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;
import com.example.couplecredit.viewmodel.InventoryViewModel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class InventoryFragment extends Fragment implements InventoryAdapter.InventoryActionListener {

    private LinearLayout llAlertBanner;
    private TextView tvAlertMessage;
    private TextView tvViewAlert;
    private EditText etSearch;
    private LinearLayout llCategoryTags;
    private LinearLayout flexActiveFilters;
    private RecyclerView rvRecentActivity;
    private RecyclerView rvInventoryList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private LinearLayout layoutContent;
    private LinearLayout layoutRecentActivitySection;
    private LinearLayout layoutInventorySection;
    private TextView btnLoginPrompt;
    private TextView tvTotalCount;
    private LinearLayout cardLowStock;
    private TextView tvLowStockCount;
    private LinearLayout cardExpiration;
    private TextView tvExpirationCount;
    private LinearLayout cardBeadInventory;
    private TextView tvBeadInventorySummary;
    private View fabAddInventory;
    private View fabRefreshInventory;

    private InventoryAdapter inventoryAdapter;
    private RecentActivityAdapter recentActivityAdapter;
    private final List<InventoryItem> inventoryList = new ArrayList<>();
    private final List<InventoryItem> lowStockList = new ArrayList<>();
    private final List<InventoryItem> recentActivityList = new ArrayList<>();
    private final List<InventoryItem> filteredInventoryList = new ArrayList<>();
    private InventoryViewModel viewModel;
    private BeadInventoryViewModel beadViewModel;

    private enum ExpirationFilterMode {
        ALL,
        EXPIRING,
        EXPIRED
    }

    private ExpirationFilterMode expirationFilterMode = ExpirationFilterMode.ALL;

    // 筛选状态
    private final Set<String> activeCategories = new HashSet<>();
    private boolean filterLowStock = false;
    private boolean filterExpiring = false;
    private boolean filterExpired = false;
    private boolean showRecentActivity = false;

    private boolean isLoggedIn;

    private final String[] defaultCategories = {"食材", "日用品", "调料", "饮品", "药品", "其他"};
    private static final String CUSTOM_UNIT_OPTION = "+ 自定义单位";
    private final List<String> dynamicCategories = new ArrayList<>();
    private final String[] defaultUnits = {"个", "包", "瓶", "盒", "袋", "斤", "克", "升", "毫升"};

    private ImageView pendingImageView;
    private String pendingImageUrl;
    private Uri cameraImageUri;
    private String originalImageUrl;
    private boolean imageChanged = false;
    private AlertDialog currentDialog;

    private final ActivityResultLauncher<Intent> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && pendingImageView != null) {
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        Glide.with(this).load(uri).placeholder(R.drawable.ic_inventory_placeholder).into(pendingImageView);
                        pendingImageUrl = uri.toString();
                        imageChanged = true;
                    }
                }
            }
    );

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted && pendingImageView != null) {
                    openImagePicker();
                } else {
                    Toast.makeText(getContext(), "需要存储权限才能选择图片", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ActivityResultLauncher<Intent> captureImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    if (cameraImageUri != null && pendingImageView != null) {
                        Glide.with(this).load(cameraImageUri).placeholder(R.drawable.ic_inventory_placeholder).into(pendingImageView);
                        pendingImageUrl = cameraImageUri.toString();
                        imageChanged = true;
                    }
                }
            }
    );

    private final ActivityResultLauncher<String> requestCameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    openCamera();
                } else {
                    Toast.makeText(getContext(), "需要相机权限才能拍照", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final DataRefreshBus.Listener refreshListener = () -> refreshInventoryData();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inventory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(InventoryViewModel.class);
        beadViewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        initViews(view);
        setupRecyclerViews();
        setupFilters();
        setupActions();
        setupSummaryCards();

        viewModel.getDataVersion().observe(getViewLifecycleOwner(), version -> {
            if (viewModel.hasData()) {
                restoreFromViewModel();
            }
        });

        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "加载物资失败: " + error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });

        beadViewModel.getDataVersion().observe(getViewLifecycleOwner(), version -> updateBeadEntrySummary());

        beadViewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                beadViewModel.clearError();
            }
        });

        if (viewModel.hasData()) {
            restoreFromViewModel();
        } else {
            refreshInventoryData();
        }

        DataRefreshBus.subscribe(refreshListener);
    }

    @Override
    public void onDestroyView() {
        DataRefreshBus.unsubscribe(refreshListener);
        super.onDestroyView();
    }

    private void restoreFromViewModel() {
        inventoryList.clear();
        lowStockList.clear();
        recentActivityList.clear();
        for (InventoryViewModel.InventoryItem vi : viewModel.getInventoryList()) {
            InventoryItem converted = convertItem(vi);
            ensureDynamicCategory(converted.category);
            inventoryList.add(converted);
        }
        for (InventoryViewModel.InventoryItem vi : viewModel.getLowStockList()) {
            lowStockList.add(convertItem(vi));
        }
        for (InventoryViewModel.InventoryItem vi : viewModel.getRecentActivityList()) {
            recentActivityList.add(convertItem(vi));
        }
        isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        updateLoginStateUI();
        if (viewModel.getCachedRelationshipId() != null) {
            UserInfoManager.saveRelationshipId(requireContext(), viewModel.getCachedRelationshipId());
        }
        updateSummary();
        recentActivityAdapter.updateData(recentActivityList);
        applyFilters();
    }

    private InventoryItem convertItem(InventoryViewModel.InventoryItem vi) {
        InventoryItem item = new InventoryItem();
        item.id = vi.id;
        item.userId = vi.userId;
        item.relationshipId = vi.relationshipId;
        item.name = vi.name;
        item.category = vi.category;
        item.imageUrl = vi.imageUrl;
        item.quantity = vi.quantity;
        item.unit = vi.unit;
        item.threshold = vi.threshold;
        item.createdAt = vi.createdAt;
        item.updatedAt = vi.updatedAt;
        item.lastConsumedAt = vi.lastConsumedAt;
        item.expirationMode = vi.expirationMode;
        item.expirationDate = vi.expirationDate;
        item.productionDate = vi.productionDate;
        item.shelfLifeDays = vi.shelfLifeDays;
        item.note = vi.note;
        item.aiImagePrompt = vi.aiImagePrompt;
        item.isLowStock = vi.isLowStock;
        item.isExpiring = vi.isExpiring;
        item.isExpired = vi.isExpired;
        item.lastActionLabel = vi.lastActionLabel;
        return item;
    }

    private void initViews(View view) {
        llAlertBanner = view.findViewById(R.id.ll_alert_banner);
        tvAlertMessage = view.findViewById(R.id.tv_alert_message);
        tvViewAlert = view.findViewById(R.id.tv_view_alert);
        etSearch = view.findViewById(R.id.et_search);
        llCategoryTags = view.findViewById(R.id.ll_category_tags);
        flexActiveFilters = view.findViewById(R.id.flex_active_filters);
        rvRecentActivity = view.findViewById(R.id.rv_recent_activity);
        rvInventoryList = view.findViewById(R.id.rv_inventory_list);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        layoutLoginPrompt = view.findViewById(R.id.layout_login_prompt);
        layoutContent = view.findViewById(R.id.layout_content);
        layoutRecentActivitySection = view.findViewById(R.id.layout_recent_activity_section);
        layoutInventorySection = view.findViewById(R.id.layout_inventory_section);
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        tvTotalCount = view.findViewById(R.id.tv_total_count);
        cardLowStock = view.findViewById(R.id.card_low_stock);
        tvLowStockCount = view.findViewById(R.id.tv_low_stock_count);
        cardExpiration = view.findViewById(R.id.card_expiration);
        tvExpirationCount = view.findViewById(R.id.tv_expiration_count);
        cardBeadInventory = view.findViewById(R.id.card_bead_inventory);
        tvBeadInventorySummary = view.findViewById(R.id.tv_bead_inventory_summary);
        fabAddInventory = view.findViewById(R.id.fab_add_inventory);
        fabRefreshInventory = view.findViewById(R.id.fab_refresh_inventory);
    }

    private void setupRecyclerViews() {
        rvInventoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRecentActivity.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(getContext(), 2));
        inventoryAdapter = new InventoryAdapter(requireContext(), filteredInventoryList, this);
        recentActivityAdapter = new RecentActivityAdapter(requireContext(), recentActivityList);
        recentActivityAdapter.setOnItemClickListener(this::showInventoryDialog);
        rvInventoryList.setAdapter(inventoryAdapter);
        rvRecentActivity.setAdapter(recentActivityAdapter);
    }

    private void setupFilters() {
        buildCategoryTags();

        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                applyFilters();
            }
        });
    }

    private List<String> getFilterCategories() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.add("全部");
        for (String category : defaultCategories) all.add(category);
        all.addAll(dynamicCategories);
        for (InventoryItem item : inventoryList) {
            if (item.category != null && !item.category.trim().isEmpty()) {
                all.add(item.category.trim());
            }
        }
        return new ArrayList<>(all);
    }

    private List<String> getDialogCategories() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String category : defaultCategories) all.add(category);
        all.addAll(dynamicCategories);
        for (InventoryItem item : inventoryList) {
            if (item.category != null && !item.category.trim().isEmpty()) {
                all.add(item.category.trim());
            }
        }
        all.add("+ 自定义分类");
        return new ArrayList<>(all);
    }

    private void ensureDynamicCategory(String category) {
        if (category == null) return;
        String trimmed = category.trim();
        if (trimmed.isEmpty()) return;
        if (!dynamicCategories.contains(trimmed)) {
            dynamicCategories.add(trimmed);
        }
    }

    private void buildCategoryTags() {
        llCategoryTags.removeAllViews();
        List<String> filterCategories = getFilterCategories();
        if (!filterCategories.isEmpty()) {
            String firstCategory = filterCategories.get(0);
            TextView firstTag = createCategoryTag(firstCategory, activeCategories.contains(firstCategory));
            firstTag.setOnClickListener(v -> toggleCategoryFilter(firstCategory));
            llCategoryTags.addView(firstTag);
        }

        llCategoryTags.addView(createQuickFilterTag("最近动态", showRecentActivity, () -> {
            showRecentActivity = !showRecentActivity;
            buildCategoryTags();
            applyFilters();
        }));

        for (int i = 1; i < filterCategories.size(); i++) {
            String cat = filterCategories.get(i);
            TextView tag = createCategoryTag(cat, activeCategories.contains(cat));
            tag.setOnClickListener(v -> toggleCategoryFilter(cat));
            llCategoryTags.addView(tag);
        }

        llCategoryTags.addView(createQuickFilterTag("告急", filterLowStock, () -> {
            filterLowStock = !filterLowStock;
            showRecentActivity = false;
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        }));
        llCategoryTags.addView(createQuickFilterTag("临期", filterExpiring, () -> {
            boolean next = !filterExpiring;
            filterExpiring = next;
            showRecentActivity = false;
            if (next) {
                filterExpired = false;
                expirationFilterMode = ExpirationFilterMode.EXPIRING;
            } else if (!filterExpired) {
                expirationFilterMode = ExpirationFilterMode.ALL;
            }
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        }));
        llCategoryTags.addView(createQuickFilterTag("过期", filterExpired, () -> {
            boolean next = !filterExpired;
            filterExpired = next;
            showRecentActivity = false;
            if (next) {
                filterExpiring = false;
                expirationFilterMode = ExpirationFilterMode.EXPIRED;
            } else if (!filterExpiring) {
                expirationFilterMode = ExpirationFilterMode.ALL;
            }
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        }));
    }

    private List<String> getDialogUnits(@Nullable String existingUnit) {
        LinkedHashSet<String> all = new LinkedHashSet<>(Arrays.asList(defaultUnits));
        for (InventoryItem item : inventoryList) {
            if (item.unit != null && !item.unit.trim().isEmpty()) {
                all.add(item.unit.trim());
            }
        }
        if (existingUnit != null && !existingUnit.trim().isEmpty()) {
            all.add(existingUnit.trim());
        }
        List<String> result = new ArrayList<>(all);
        result.add(CUSTOM_UNIT_OPTION);
        return result;
    }

    private TextView createCategoryTag(String text, boolean active) {
        TextView tag = new TextView(requireContext());
        tag.setText(text);
        tag.setTextSize(13);
        tag.setPadding(dp(14), dp(6), dp(14), dp(6));
        tag.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        tag.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (active) {
            bg.setColor(ContextCompat.getColor(requireContext(), R.color.primary_color));
            tag.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#F0F0F0"));
            tag.setTextColor(Color.parseColor("#666666"));
        }
        tag.setBackground(bg);
        return tag;
    }

    private TextView createQuickFilterTag(String text, boolean active, Runnable onClick) {
        TextView tag = createCategoryTag(text, active);
        tag.setOnClickListener(v -> onClick.run());
        return tag;
    }

    private void toggleCategoryFilter(String category) {
        showRecentActivity = false;
        if ("全部".equals(category)) {
            activeCategories.clear();
        } else {
            if (activeCategories.contains(category)) {
                activeCategories.remove(category);
            } else {
                activeCategories.add(category);
            }
        }
        buildCategoryTags();
        applyFilters();
    }

    private void setupSummaryCards() {
        cardLowStock.setOnClickListener(v -> {
            showRecentActivity = false;
            filterLowStock = !filterLowStock;
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        });

        cardExpiration.setOnClickListener(v -> {
            showRecentActivity = false;
            if (!filterExpiring && !filterExpired) {
                filterExpiring = true;
                expirationFilterMode = ExpirationFilterMode.EXPIRING;
            } else if (filterExpiring) {
                filterExpiring = false;
                filterExpired = true;
                expirationFilterMode = ExpirationFilterMode.EXPIRED;
            } else {
                filterExpired = false;
                expirationFilterMode = ExpirationFilterMode.ALL;
            }
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        });
    }

    private void updateCardHighlight() {
        GradientDrawable lowBg = new GradientDrawable();
        lowBg.setCornerRadius(dp(12));
        if (filterLowStock) {
            lowBg.setColor(Color.parseColor("#FFF1F1"));
        } else {
            lowBg.setColor(Color.WHITE);
        }
        cardLowStock.setBackground(lowBg);

        GradientDrawable expirationBg = new GradientDrawable();
        expirationBg.setCornerRadius(dp(12));
        if (expirationFilterMode != ExpirationFilterMode.ALL) {
            expirationBg.setColor(Color.parseColor("#FFF7E8"));
        } else {
            expirationBg.setColor(Color.WHITE);
        }
        cardExpiration.setBackground(expirationBg);
    }

    private void updateBeadEntrySummary() {
        if (tvBeadInventorySummary == null || beadViewModel == null) {
            return;
        }
        BeadInventoryViewModel.BeadSummary summary = beadViewModel.getSummary();
        if (summary.totalColors <= 0) {
            tvBeadInventorySummary.setText("221 色库存、告急与图纸消耗统计");
            return;
        }
        tvBeadInventorySummary.setText(String.format(Locale.getDefault(), "%d 色 · %d 色告急 · 理论消耗 %d 颗",
                summary.totalColors, summary.lowStockCount, summary.totalConsumptionReference));
    }


    private void setupActions() {
        fabAddInventory.setOnClickListener(v -> {
            if (!isLoggedIn) {
                openLoginPage();
                return;
            }
            showInventoryDialog(null);
        });
        fabRefreshInventory.setOnClickListener(v -> refreshInventoryData());

        cardBeadInventory.setOnClickListener(v -> {
            if (!isLoggedIn) {
                openLoginPage();
                return;
            }
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new BeadInventoryFragment())
                    .addToBackStack("bead_inventory")
                    .commit();
        });

        btnLoginPrompt.setOnClickListener(v -> openLoginPage());
        tvViewAlert.setOnClickListener(v -> {
            showRecentActivity = false;
            filterLowStock = true;
            buildCategoryTags();
            updateCardHighlight();
            applyFilters();
        });
    }

    public void refreshInventoryData() {
        if (!isAdded()) {
            return;
        }

        isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        updateLoginStateUI();
        if (!isLoggedIn) {
            clearInventoryData();
            updateSummary();
            applyFilters();
            return;
        }

        if (viewModel != null) {
            viewModel.loadData();
        }
    }

    private void bindInventoryData(AuthApiModels.InventoryListResponse response) {
        clearInventoryData();
        // 缓存关系状态到本地
        if (response != null && response.data != null && response.data.relationshipId != null) {
            UserInfoManager.saveRelationshipId(requireContext(), response.data.relationshipId);
        }
        if (response != null && response.data != null && response.data.items != null) {
            for (AuthApiModels.InventoryItemData itemData : response.data.items) {
                InventoryItem item = fromApiItem(itemData);
                ensureDynamicCategory(item.category);
                inventoryList.add(item);
                if (item.isLowStock()) {
                    lowStockList.add(item);
                }
                recentActivityList.add(item);
            }
        }

        if (recentActivityList.size() > 8) {
            recentActivityList.subList(8, recentActivityList.size()).clear();
        }

        updateSummary();
        recentActivityAdapter.updateData(recentActivityList);
        applyFilters();
    }

    private void updateLoginStateUI() {
        layoutLoginPrompt.setVisibility(isLoggedIn ? View.GONE : View.VISIBLE);
        layoutContent.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        fabAddInventory.setEnabled(isLoggedIn);
        fabAddInventory.setAlpha(isLoggedIn ? 1f : 0.5f);
        etSearch.setEnabled(isLoggedIn);
    }

    private void updateSummary() {
        tvTotalCount.setText(String.valueOf(inventoryList.size()));
        tvLowStockCount.setText(String.valueOf(lowStockList.size()));
        tvExpirationCount.setText(String.valueOf(getExpirationAlertCount()));

        if (lowStockList.isEmpty()) {
            llAlertBanner.setVisibility(View.GONE);
        } else {
            llAlertBanner.setVisibility(View.VISIBLE);
            tvAlertMessage.setText("有 " + lowStockList.size() + " 项物资已低于提醒阈值");
        }

        llEmptyState.setVisibility(!showRecentActivity && inventoryList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
    }

    private int getExpirationAlertCount() {
        int count = 0;
        for (InventoryItem item : inventoryList) {
            if (item.isExpired || item.isExpiring) {
                count++;
            }
        }
        return count;
    }

    private void applyFilters() {
        filteredInventoryList.clear();
        String keyword = etSearch.getText() == null ? "" : etSearch.getText().toString().trim().toLowerCase(Locale.getDefault());

        for (InventoryItem item : inventoryList) {
            boolean matchKeyword = TextUtils.isEmpty(keyword)
                    || (item.name != null && item.name.toLowerCase(Locale.getDefault()).contains(keyword))
                    || (item.note != null && item.note.toLowerCase(Locale.getDefault()).contains(keyword));
            if (!matchKeyword) continue;

            boolean matchCategory = activeCategories.isEmpty()
                    || activeCategories.contains(item.category);
            if (!matchCategory) continue;

            if (filterLowStock && !item.isLowStock()) continue;
            if (filterExpiring && !item.isExpiring) continue;
            if (filterExpired && !item.isExpired) continue;

            filteredInventoryList.add(item);
        }

        inventoryAdapter.updateData(filteredInventoryList);
        layoutRecentActivitySection.setVisibility(showRecentActivity ? View.VISIBLE : View.GONE);
        layoutInventorySection.setVisibility(showRecentActivity ? View.GONE : View.VISIBLE);
        llEmptyState.setVisibility(!showRecentActivity && filteredInventoryList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
        rebuildActiveFilterTags();
    }


    private void rebuildActiveFilterTags() {
        flexActiveFilters.removeAllViews();

        for (String cat : activeCategories) {
            flexActiveFilters.addView(createActiveFilterTag("分类: " + cat, () -> {
                activeCategories.remove(cat);
                buildCategoryTags();
                applyFilters();
            }));
        }

        if (filterLowStock) {
            flexActiveFilters.addView(createActiveFilterTag("告急物资", () -> {
                filterLowStock = false;
                buildCategoryTags();
                updateCardHighlight();
                applyFilters();
            }));
        }

        if (filterExpiring) {
            flexActiveFilters.addView(createActiveFilterTag("即将到期", () -> {
                filterExpiring = false;
                expirationFilterMode = filterExpired ? ExpirationFilterMode.EXPIRED : ExpirationFilterMode.ALL;
                buildCategoryTags();
                updateCardHighlight();
                applyFilters();
            }));
        }

        if (filterExpired) {
            flexActiveFilters.addView(createActiveFilterTag("已过期", () -> {
                filterExpired = false;
                expirationFilterMode = filterExpiring ? ExpirationFilterMode.EXPIRING : ExpirationFilterMode.ALL;
                buildCategoryTags();
                updateCardHighlight();
                applyFilters();
            }));
        }

        if (showRecentActivity) {
            flexActiveFilters.addView(createActiveFilterTag("最近动态", () -> {
                showRecentActivity = false;
                buildCategoryTags();
                applyFilters();
            }));
        }

        flexActiveFilters.setVisibility(flexActiveFilters.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private LinearLayout createActiveFilterTag(String text, Runnable onRemove) {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(10), dp(4), dp(10), dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#E8F5E9"));
        container.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        container.setLayoutParams(lp);

        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_color));
        container.addView(label);

        TextView close = new TextView(requireContext());
        close.setText(" x");
        close.setTextSize(13);
        close.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_color));
        close.setPadding(dp(4), 0, 0, 0);
        container.addView(close);

        container.setOnClickListener(v -> onRemove.run());
        return container;
    }

    private void showInventoryDialog(@Nullable InventoryItem existingItem) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_inventory, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        currentDialog = dialog;

        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        EditText etName = dialogView.findViewById(R.id.et_inventory_name);
        Spinner spinnerCategoryDialog = dialogView.findViewById(R.id.spinner_add_category);
        EditText etQuantity = dialogView.findViewById(R.id.et_quantity);
        Spinner spinnerUnit = dialogView.findViewById(R.id.spinner_unit);
        EditText etThreshold = dialogView.findViewById(R.id.et_threshold);
        EditText etNote = dialogView.findViewById(R.id.et_note);
        EditText etAiPrompt = dialogView.findViewById(R.id.et_ai_prompt);
        ImageView ivAddImage = dialogView.findViewById(R.id.iv_add_image);
        View flAddImage = dialogView.findViewById(R.id.fl_add_image);
        TextView tvSelectImage = dialogView.findViewById(R.id.tv_select_image);
        TextView tvAiGenerate = dialogView.findViewById(R.id.tv_ai_generate);
        View btnAiGenerate = dialogView.findViewById(R.id.btn_ai_generate);
        ProgressBar pbAiGenerating = dialogView.findViewById(R.id.pb_ai_generating);
        View layoutImageLoading = dialogView.findViewById(R.id.layout_image_loading);
        TextView btnPickExpirationDate = dialogView.findViewById(R.id.btn_pick_expiration_date);
        TextView btnToggleAdvancedShelfLife = dialogView.findViewById(R.id.btn_toggle_advanced_shelf_life);
        LinearLayout layoutAdvancedShelfLife = dialogView.findViewById(R.id.layout_advanced_shelf_life);
        TextView btnPickProductionDate = dialogView.findViewById(R.id.btn_pick_production_date);
        EditText etShelfLifeDays = dialogView.findViewById(R.id.et_shelf_life_days);
        TextView tvDerivedExpirationPreview = dialogView.findViewById(R.id.tv_derived_expiration_preview);
        TextView btnSubmit = dialogView.findViewById(R.id.btn_add_inventory);

        final boolean[] useAdvancedShelfLife = {existingItem != null && "calc".equals(existingItem.expirationMode)};
        final String[] selectedExpirationDate = {normalizeDateOnly(existingItem != null ? existingItem.expirationDate : null)};
        final String[] selectedProductionDate = {normalizeDateOnly(existingItem != null ? existingItem.productionDate : null)};
        final String[] simpleExpirationDraft = {selectedExpirationDate[0]};
        final String[] advancedProductionDraft = {selectedProductionDate[0]};
        final Integer[] advancedShelfLifeDraft = {existingItem != null ? existingItem.shelfLifeDays : null};

        updateDateButtonText(btnPickExpirationDate, selectedExpirationDate[0], "选择到期日期（必填）");
        updateDateButtonText(btnPickProductionDate, selectedProductionDate[0], "选择生产日期");
        if (advancedShelfLifeDraft[0] != null) {
            etShelfLifeDays.setText(String.valueOf(advancedShelfLifeDraft[0]));
        }
        updateAdvancedShelfLifeState(btnToggleAdvancedShelfLife, layoutAdvancedShelfLife, useAdvancedShelfLife[0]);
        updateDerivedExpirationPreview(tvDerivedExpirationPreview, selectedProductionDate[0], parseShelfLifeDays(etShelfLifeDays.getText().toString()));

        List<String> unitOptions = getDialogUnits(existingItem != null ? existingItem.unit : null);
        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, unitOptions);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUnit.setAdapter(unitAdapter);

        List<String> dialogCategories = getDialogCategories();
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, dialogCategories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategoryDialog.setAdapter(categoryAdapter);

        spinnerCategoryDialog.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = String.valueOf(parent.getItemAtPosition(position));
                if (!"+ 自定义分类".equals(selected)) return;

                EditText input = new EditText(requireContext());
                input.setHint("输入分类名称");
                input.setSingleLine(true);
                new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                        .setTitle("自定义分类")
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> {
                            String custom = input.getText().toString().trim();
                            if (custom.isEmpty()) {
                                Toast.makeText(requireContext(), "分类名称不能为空", Toast.LENGTH_SHORT).show();
                                spinnerCategoryDialog.setSelection(0);
                                return;
                            }
                            ensureDynamicCategory(custom);
                            List<String> updated = getDialogCategories();
                            ArrayAdapter<String> updatedAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, updated);
                            updatedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerCategoryDialog.setAdapter(updatedAdapter);
                            int idx = updated.indexOf(custom);
                            spinnerCategoryDialog.setSelection(Math.max(idx, 0));
                            buildCategoryTags();
                        })
                        .setNegativeButton("取消", (d, w) -> spinnerCategoryDialog.setSelection(0))
                        .show();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final int[] lastUnitSelection = {0};
        spinnerUnit.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = String.valueOf(parent.getItemAtPosition(position));
                if (!CUSTOM_UNIT_OPTION.equals(selected)) {
                    lastUnitSelection[0] = position;
                    return;
                }

                EditText input = new EditText(requireContext());
                input.setHint("输入单位名称");
                input.setSingleLine(true);
                new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                        .setTitle("自定义单位")
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> {
                            String custom = input.getText().toString().trim();
                            if (custom.isEmpty()) {
                                Toast.makeText(requireContext(), "单位名称不能为空", Toast.LENGTH_SHORT).show();
                                spinnerUnit.setSelection(lastUnitSelection[0]);
                                return;
                            }
                            List<String> updatedUnits = getDialogUnits(custom);
                            ArrayAdapter<String> updatedUnitAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, updatedUnits);
                            updatedUnitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerUnit.setAdapter(updatedUnitAdapter);
                            int idx = updatedUnits.indexOf(custom);
                            spinnerUnit.setSelection(Math.max(idx, 0));
                        })
                        .setNegativeButton("取消", (d, w) -> spinnerUnit.setSelection(lastUnitSelection[0]))
                        .show();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        View.OnClickListener imageClickListener = v -> {
            pendingImageView = ivAddImage;
            showImageSourcePicker();
        };
        flAddImage.setOnClickListener(imageClickListener);
        tvSelectImage.setOnClickListener(imageClickListener);

        btnAiGenerate.setOnClickListener(v -> {
            etAiPrompt.setVisibility(View.VISIBLE);
            String promptText = etAiPrompt.getText().toString().trim();
            String itemName = etName.getText().toString().trim();
            String itemCategory = spinnerCategoryDialog.getSelectedItem() != null ? spinnerCategoryDialog.getSelectedItem().toString() : "";

            if (promptText.isEmpty() && itemName.isEmpty()) {
                Toast.makeText(requireContext(), "请先输入物品名称或提示词", Toast.LENGTH_SHORT).show();
                return;
            }

            btnAiGenerate.setEnabled(false);
            pbAiGenerating.setVisibility(View.VISIBLE);
            tvAiGenerate.setText("生成中...");
            tvAiGenerate.setTextColor(Color.parseColor("#999999"));
            layoutImageLoading.setVisibility(View.VISIBLE);
            flAddImage.setClickable(false);
            tvSelectImage.setEnabled(false);

            AuthApiClient.generateInventoryImage(requireContext(), promptText, itemCategory, itemName,
                    new AuthApiClient.GenerateImageCallback() {
                        @Override
                        public void onSuccess(String imageUrl) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!isAdded() || !dialog.isShowing()) return;
                                resetAiGenerateUi(btnAiGenerate, pbAiGenerating, tvAiGenerate, layoutImageLoading, flAddImage, tvSelectImage);
                                pendingImageUrl = imageUrl;
                                imageChanged = true;
                                if (ivAddImage != null) {
                                    Glide.with(requireContext()).load(resolveImageUrl(imageUrl)).into(ivAddImage);
                                }
                                Toast.makeText(requireContext(), "AI 生图成功", Toast.LENGTH_SHORT).show();
                            });
                        }
                        @Override
                        public void onError(String message) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                if (!isAdded() || !dialog.isShowing()) return;
                                resetAiGenerateUi(btnAiGenerate, pbAiGenerating, tvAiGenerate, layoutImageLoading, flAddImage, tvSelectImage);
                                Toast.makeText(requireContext(), "AI 生图失败: " + message, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        });

        btnPickExpirationDate.setOnClickListener(v -> showDatePicker(selectedExpirationDate[0], date -> {
            selectedExpirationDate[0] = date;
            simpleExpirationDraft[0] = date;
            updateDateButtonText(btnPickExpirationDate, date, "选择到期日期（必填）");
        }));

        btnPickProductionDate.setOnClickListener(v -> showDatePicker(selectedProductionDate[0], date -> {
            selectedProductionDate[0] = date;
            advancedProductionDraft[0] = date;
            updateDateButtonText(btnPickProductionDate, date, "选择生产日期");
            updateDerivedExpirationPreview(tvDerivedExpirationPreview, date, parseShelfLifeDays(etShelfLifeDays.getText().toString()));
        }));

        btnToggleAdvancedShelfLife.setOnClickListener(v -> {
            useAdvancedShelfLife[0] = !useAdvancedShelfLife[0];
            if (useAdvancedShelfLife[0]) {
                simpleExpirationDraft[0] = selectedExpirationDate[0];
                selectedExpirationDate[0] = null;
                selectedProductionDate[0] = advancedProductionDraft[0];
                Integer draftDays = advancedShelfLifeDraft[0];
                etShelfLifeDays.setText(draftDays == null ? "" : String.valueOf(draftDays));
            } else {
                advancedProductionDraft[0] = selectedProductionDate[0];
                advancedShelfLifeDraft[0] = parseShelfLifeDays(etShelfLifeDays.getText().toString());
                selectedProductionDate[0] = null;
                selectedExpirationDate[0] = simpleExpirationDraft[0];
            }
            updateAdvancedShelfLifeState(btnToggleAdvancedShelfLife, layoutAdvancedShelfLife, useAdvancedShelfLife[0]);
            updateDateButtonText(btnPickExpirationDate, selectedExpirationDate[0], "选择到期日期（必填）");
            updateDateButtonText(btnPickProductionDate, selectedProductionDate[0], "选择生产日期");
            updateDerivedExpirationPreview(tvDerivedExpirationPreview, selectedProductionDate[0], parseShelfLifeDays(etShelfLifeDays.getText().toString()));
        });

        etShelfLifeDays.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Integer parsedDays = parseShelfLifeDays(s == null ? null : s.toString());
                advancedShelfLifeDraft[0] = parsedDays;
                updateDerivedExpirationPreview(tvDerivedExpirationPreview, selectedProductionDate[0], parsedDays);
            }
        });

        pendingImageUrl = null;
        originalImageUrl = null;
        imageChanged = false;
        if (existingItem != null) {
            tvDialogTitle.setText("编辑物资");
            btnSubmit.setText("保存修改");
            etName.setText(existingItem.name);
            etQuantity.setText(trimDecimal(existingItem.quantity));
            etThreshold.setText(trimDecimal(existingItem.threshold));
            etNote.setText(existingItem.note == null ? "" : existingItem.note);
            etAiPrompt.setVisibility(existingItem.aiImagePrompt != null && !existingItem.aiImagePrompt.isEmpty() ? View.VISIBLE : View.GONE);
            etAiPrompt.setText(existingItem.aiImagePrompt == null ? "" : existingItem.aiImagePrompt);
            pendingImageUrl = existingItem.imageUrl;
            originalImageUrl = existingItem.imageUrl;
            if (existingItem.imageUrl != null && !existingItem.imageUrl.isEmpty()) {
                Glide.with(this).load(resolveImageUrl(existingItem.imageUrl)).placeholder(R.drawable.ic_inventory_placeholder).into(ivAddImage);
            }
            ensureDynamicCategory(existingItem.category);
            List<String> updatedDialogCategories = getDialogCategories();
            ArrayAdapter<String> updatedCategoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, updatedDialogCategories);
            updatedCategoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerCategoryDialog.setAdapter(updatedCategoryAdapter);
            setSpinnerSelection(spinnerCategoryDialog, updatedDialogCategories, existingItem.category);
            setSpinnerSelection(spinnerUnit, unitOptions, existingItem.unit);
        } else {
            spinnerUnit.setSelection(0);
            tvDialogTitle.setText("新增物资");
            btnSubmit.setText("添加物资");
        }

        btnSubmit.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String category = spinnerCategoryDialog.getSelectedItem().toString();
            String quantityText = etQuantity.getText().toString().trim();
            String unit = spinnerUnit.getSelectedItem().toString();
            String thresholdText = etThreshold.getText().toString().trim();
            String note = etNote.getText().toString().trim();
            String aiPrompt = etAiPrompt.getText().toString().trim();
            Integer shelfLifeDays = parseShelfLifeDays(etShelfLifeDays.getText().toString());
            String expirationMode = useAdvancedShelfLife[0] ? "calc" : "date";
            String expirationDate = useAdvancedShelfLife[0] ? null : selectedExpirationDate[0];
            String productionDate = useAdvancedShelfLife[0] ? selectedProductionDate[0] : null;

            if (!isExpirationInputValid(useAdvancedShelfLife[0], expirationDate, productionDate, shelfLifeDays)) {
                Toast.makeText(getContext(), useAdvancedShelfLife[0] ? "生产日期和保质期天数需要同时填写" : "请选择到期日期", Toast.LENGTH_SHORT).show();
                return;
            }

            if (useAdvancedShelfLife[0] && calculateDerivedExpirationDate(productionDate, shelfLifeDays) == null) {
                Toast.makeText(getContext(), "保质期设置无效，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }

            if (TextUtils.isEmpty(name)) {
                Toast.makeText(getContext(), "请输入物资名称", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(quantityText)) {
                Toast.makeText(getContext(), "请输入数量", Toast.LENGTH_SHORT).show();
                return;
            }

            double quantity;
            double threshold = 1;
            try {
                quantity = Double.parseDouble(quantityText);
                if (!TextUtils.isEmpty(thresholdText)) {
                    threshold = Double.parseDouble(thresholdText);
                }
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            if (quantity < 0 || threshold < 0) {
                Toast.makeText(getContext(), "数量和阈值不能为负数", Toast.LENGTH_SHORT).show();
                return;
            }

            if (imageChanged && pendingImageUrl != null) {
                btnSubmit.setEnabled(false);
                btnSubmit.setText("上传图片中...");
                uploadAndSave(dialog, name, category, quantity, unit, threshold, expirationMode, expirationDate,
                        productionDate, shelfLifeDays, note, aiPrompt, existingItem);
            } else {
                String finalImageUrl = imageChanged ? null : pendingImageUrl;
                saveInventoryItem(dialog, name, category, quantity, unit, threshold, expirationMode, expirationDate,
                        productionDate, shelfLifeDays, finalImageUrl, note, aiPrompt, existingItem);
            }
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private static final int IMAGE_MAX_DIMENSION = 1024;
    private static final int IMAGE_JPEG_QUALITY = 80;

    private void uploadAndSave(AlertDialog dialog, String name, String category, double quantity,
                               String unit, double threshold, String expirationMode, String expirationDate,
                               String productionDate, Integer shelfLifeDays, String note, String aiPrompt,
                               @Nullable InventoryItem existingItem) {
        try {
            Uri localUri = Uri.parse(pendingImageUrl);
            InputStream compressed = compressImage(localUri);
            if (compressed == null) {
                Toast.makeText(getContext(), "图片压缩失败", Toast.LENGTH_SHORT).show();
                restoreSubmitButton(dialog, existingItem);
                return;
            }
            String fileName = "inventory_" + System.currentTimeMillis() + ".jpg";

            AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                @Override
                public void onSuccess(String serverImageUrl) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() ->
                            saveInventoryItem(dialog, name, category, quantity, unit, threshold, expirationMode,
                                    expirationDate, productionDate, shelfLifeDays, serverImageUrl, note, aiPrompt, existingItem)
                    );
                }

                @Override
                public void onError(String error) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "图片上传失败: " + error, Toast.LENGTH_SHORT).show();
                        restoreSubmitButton(dialog, existingItem);
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "无法读取图片: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            restoreSubmitButton(dialog, existingItem);
        }
    }

    private InputStream compressImage(Uri imageUri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(imageUri);

            // Step 1: Decode dimensions only
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            is.close();

            // Step 2: Calculate inSampleSize
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            int sampleSize = 1;
            if (width > IMAGE_MAX_DIMENSION || height > IMAGE_MAX_DIMENSION) {
                int halfW = width / 2;
                int halfH = height / 2;
                while ((halfW / sampleSize) >= IMAGE_MAX_DIMENSION
                        && (halfH / sampleSize) >= IMAGE_MAX_DIMENSION) {
                    sampleSize *= 2;
                }
            }

            // Step 3: Decode with sample size
            is = requireContext().getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, decodeOpts);
            is.close();

            if (bitmap == null) return null;

            // Step 4: Exact resize if still oversized
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            if (bw > IMAGE_MAX_DIMENSION || bh > IMAGE_MAX_DIMENSION) {
                float scale = Math.min((float) IMAGE_MAX_DIMENSION / bw, (float) IMAGE_MAX_DIMENSION / bh);
                int newW = Math.round(bw * scale);
                int newH = Math.round(bh * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                bitmap.recycle();
                bitmap = scaled;
            }

            // Step 5: Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos);
            bitmap.recycle();

            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private void saveInventoryItem(AlertDialog dialog, String name, String category, double quantity,
                                   String unit, double threshold, String expirationMode, String expirationDate,
                                   String productionDate, Integer shelfLifeDays, String imageUrl, String note,
                                   String aiPrompt, @Nullable InventoryItem existingItem) {
        AuthApiClient.InventoryMutationCallback callback = new AuthApiClient.InventoryMutationCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), existingItem == null ? "物资添加成功" : "物资更新成功", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    refreshInventoryData();
                });
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                    restoreSubmitButton(dialog, existingItem);
                });
            }
        };

        int userId = UserInfoManager.getCurrentUserId(requireContext());
        if (userId < 0) {
            Toast.makeText(getContext(), "用户未登录", Toast.LENGTH_SHORT).show();
            restoreSubmitButton(dialog, existingItem);
            return;
        }

        String normalizedExpirationMode = TextUtils.isEmpty(expirationMode) ? null : expirationMode;
        String normalizedExpirationDate = emptyToNull(expirationDate);
        String normalizedProductionDate = emptyToNull(productionDate);
        Integer normalizedShelfLifeDays = shelfLifeDays != null && shelfLifeDays > 0 ? shelfLifeDays : null;
        if ("date".equals(normalizedExpirationMode) && TextUtils.isEmpty(normalizedExpirationDate)) {
            normalizedExpirationMode = null;
        }
        if ("calc".equals(normalizedExpirationMode)
                && (TextUtils.isEmpty(normalizedProductionDate) || normalizedShelfLifeDays == null)) {
            normalizedExpirationMode = null;
            normalizedProductionDate = null;
            normalizedShelfLifeDays = null;
        }

        if (existingItem == null) {
            AuthApiModels.CreateInventoryRequest request = new AuthApiModels.CreateInventoryRequest(
                    userId,
                    name,
                    category,
                    quantity,
                    unit,
                    threshold,
                    normalizedExpirationMode,
                    normalizedExpirationDate,
                    normalizedProductionDate,
                    normalizedShelfLifeDays,
                    emptyToNull(imageUrl),
                    emptyToNull(note),
                    emptyToNull(aiPrompt)
            );
            AuthApiClient.createInventory(requireContext(), request, callback);
        } else {
            AuthApiModels.UpdateInventoryRequest request = new AuthApiModels.UpdateInventoryRequest(
                    userId,
                    name,
                    category,
                    quantity,
                    unit,
                    threshold,
                    normalizedExpirationMode,
                    normalizedExpirationDate,
                    normalizedProductionDate,
                    normalizedShelfLifeDays,
                    emptyToNullableString(imageUrl),
                    emptyToNullableString(note),
                    emptyToNullableString(aiPrompt)
            );
            AuthApiClient.updateInventory(requireContext(), existingItem.id, request, callback);
        }
    }

    private void restoreSubmitButton(AlertDialog dialog, @Nullable InventoryItem existingItem) {
        if (dialog != null) {
            TextView btnSubmit = dialog.findViewById(R.id.btn_add_inventory);
            if (btnSubmit != null) {
                btnSubmit.setEnabled(true);
                btnSubmit.setText(existingItem == null ? "添加物资" : "保存修改");
            }
        }
    }

    private void showLowStockDialog() {
        if (lowStockList.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("告急物资列表");
        String[] items = new String[lowStockList.size()];
        for (int i = 0; i < lowStockList.size(); i++) {
            InventoryItem item = lowStockList.get(i);
            items[i] = item.name + " · 当前 " + trimDecimal(item.quantity) + " " + item.unit + " · 阈值 " + trimDecimal(item.threshold);
        }
        builder.setItems(items, (dialog, which) -> showReplenishDialog(lowStockList.get(which)));
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    public void showConsumeDialog(InventoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_amount_input, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_current_info);
        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        tvTitle.setText("消耗 " + item.name);
        tvInfo.setText("当前存量：" + trimDecimal(item.quantity) + " " + item.unit);
        etAmount.setHint("消耗数量");
        etAmount.setText("1");
        btnConfirm.setText("确认消耗");

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String amountStr = etAmount.getText().toString().trim();
            if (TextUtils.isEmpty(amountStr)) {
                Toast.makeText(getContext(), "请输入消耗数量", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                double amount = Double.parseDouble(amountStr);
                dialog.dismiss();
                InventoryUtils.consumeInventory(requireContext(), item.id, amount, new ToastMutationCallback("消耗记录成功"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
            }
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    public void showReplenishDialog(InventoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_amount_input, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_current_info);
        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        tvTitle.setText("补货 " + item.name);
        tvInfo.setText("当前存量：" + trimDecimal(item.quantity) + " " + item.unit);
        etAmount.setHint("补货数量");
        etAmount.setText("1");
        btnConfirm.setText("确认补货");

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String amountStr = etAmount.getText().toString().trim();
            if (TextUtils.isEmpty(amountStr)) {
                Toast.makeText(getContext(), "请输入补货数量", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                double amount = Double.parseDouble(amountStr);
                dialog.dismiss();
                InventoryUtils.replenishInventory(requireContext(), item.id, amount, new ToastMutationCallback("补货成功"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
            }
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private void showDeleteDialog(InventoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_amount_input, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_current_info);
        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        tvTitle.setText("删除物资");
        tvInfo.setText("确定删除\"" + item.name + "\"吗？此操作不可撤销。");
        etAmount.setVisibility(View.GONE);
        btnConfirm.setText("删除");

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            InventoryUtils.deleteInventory(requireContext(), item.id, new ToastMutationCallback("删除成功"));
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private void openLoginPage() {
        startActivity(new Intent(requireContext(), LoginActivity.class));
    }

    private void checkAndRequestImagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                openImagePicker();
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                openImagePicker();
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
            pickImageLauncher.launch(intent);
        } else {
            Toast.makeText(getContext(), "没有找到可用的图片选择应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCamera() {
        File photoFile = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "inventory_photo_" + System.currentTimeMillis() + ".jpg");
        cameraImageUri = FileProvider.getUriForFile(requireContext(),
                "com.example.couplecredit.fileprovider", photoFile);

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (takePictureIntent.resolveActivity(requireContext().getPackageManager()) != null) {
            captureImageLauncher.launch(takePictureIntent);
        } else {
            Toast.makeText(getContext(), "没有找到可用的相机应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void showImageSourcePicker() {
        String[] options = {"拍照", "从图库选择"};
        new AlertDialog.Builder(requireContext())
                .setTitle("选择图片来源")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            openCamera();
                        } else {
                            requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
                        }
                    } else {
                        checkAndRequestImagePermission();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://") || imageUrl.startsWith("content://") || imageUrl.startsWith("file://")) {
            return imageUrl;
        }
        return ApiConfigManager.getBaseUrl(requireContext()) + imageUrl;
    }

    private void clearInventoryData() {
        inventoryList.clear();
        lowStockList.clear();
        recentActivityList.clear();
        filteredInventoryList.clear();
        recentActivityAdapter.updateData(recentActivityList);
        inventoryAdapter.updateData(filteredInventoryList);
    }

    private InventoryItem fromApiItem(AuthApiModels.InventoryItemData itemData) {
        InventoryItem item = new InventoryItem();
        item.id = itemData.inventoryId;
        item.userId = itemData.userId;
        item.relationshipId = itemData.relationshipId;
        item.name = itemData.name;
        item.category = itemData.category;
        item.imageUrl = resolveImageUrl(itemData.imageUrl);
        item.quantity = itemData.quantity;
        item.unit = itemData.unit;
        item.threshold = itemData.threshold;
        item.createdAt = normalizeDateTime(itemData.createdAt);
        item.updatedAt = normalizeDateTime(itemData.updatedAt);
        item.lastConsumedAt = normalizeDateTime(itemData.lastConsumedAt);
        item.expirationMode = itemData.expirationMode;
        item.expirationDate = normalizeDateOnly(itemData.expirationDate);
        item.productionDate = normalizeDateOnly(itemData.productionDate);
        item.shelfLifeDays = itemData.shelfLifeDays;
        item.note = itemData.note;
        item.aiImagePrompt = itemData.aiImagePrompt;
        item.isLowStock = item.quantity <= item.threshold;
        item.isExpiring = itemData.isExpiring;
        item.isExpired = itemData.isExpired;
        item.lastActionLabel = resolveActionLabel(item);
        return item;
    }

    private String resolveActionLabel(InventoryItem item) {
        if (item.lastConsumedAt != null && !item.lastConsumedAt.isEmpty()) {
            return "消耗";
        }
        if (item.updatedAt != null && item.createdAt != null && !item.updatedAt.equals(item.createdAt)) {
            return "更新";
        }
        return "新增";
    }

    private String normalizeDateTime(String value) {
        return DateTimeUtils.normalizeDateTimeToUtc8(value);
    }

    private String normalizeDateOnly(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim().replace('T', ' ');
        int dotIndex = normalized.indexOf('.');
        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }
        if (normalized.length() >= 10) {
            return normalized.substring(0, 10);
        }
        return normalized;
    }

    static boolean isExpirationInputValid(boolean useAdvancedShelfLife, @Nullable String expirationDate,
                                          @Nullable String productionDate, @Nullable Integer shelfLifeDays) {
        if (useAdvancedShelfLife) {
            return !TextUtils.isEmpty(productionDate) && shelfLifeDays != null;
        }
        return true;
    }

    private void updateDateButtonText(TextView view, @Nullable String date, String placeholder) {
        boolean hasValue = !TextUtils.isEmpty(date);
        view.setText(hasValue ? date : placeholder);
        view.setTextColor(Color.parseColor(hasValue ? "#111827" : "#6B7280"));
    }

    private void updateAdvancedShelfLifeState(TextView toggleView, View advancedLayout, boolean expanded) {
        advancedLayout.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggleView.setText(expanded ? "收起高级设置" : "更多设置");
    }

    private Integer parseShelfLifeDays(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            int days = Integer.parseInt(value.trim());
            return days > 0 ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateDerivedExpirationPreview(TextView previewView, @Nullable String productionDate, @Nullable Integer shelfLifeDays) {
        String derivedDate = calculateDerivedExpirationDate(productionDate, shelfLifeDays);
        if (derivedDate == null) {
            previewView.setText("将自动计算到期日期");
            previewView.setTextColor(Color.parseColor("#6B7280"));
            return;
        }
        previewView.setText("预计到期日期：" + derivedDate);
        previewView.setTextColor(Color.parseColor("#111827"));
    }

    @Nullable
    private String calculateDerivedExpirationDate(@Nullable String productionDate, @Nullable Integer shelfLifeDays) {
        if (TextUtils.isEmpty(productionDate) || shelfLifeDays == null || shelfLifeDays <= 0) {
            return null;
        }
        Calendar calendar = parseDateToCalendar(productionDate);
        if (calendar == null) {
            return null;
        }
        calendar.add(Calendar.DAY_OF_YEAR, shelfLifeDays);
        return formatDate(calendar);
    }

    private void resetAiGenerateUi(View btnAiGenerate, ProgressBar pbAiGenerating,
                                    TextView tvAiGenerate, View layoutImageLoading, View flAddImage,
                                    TextView tvSelectImage) {
        btnAiGenerate.setEnabled(true);
        pbAiGenerating.setVisibility(View.GONE);
        tvAiGenerate.setText("✨ AI生成");
        tvAiGenerate.setTextColor(Color.parseColor("#6B7280"));
        layoutImageLoading.setVisibility(View.GONE);
        flAddImage.setClickable(true);
        tvSelectImage.setEnabled(true);
    }

    private void showDatePicker(@Nullable String initialDate, DateSelectionListener listener) {
        Calendar initial = parseDateToCalendar(initialDate);
        if (initial == null) {
            initial = Calendar.getInstance();
        }
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    listener.onDateSelected(formatDate(selected));
                },
                initial.get(Calendar.YEAR),
                initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    @Nullable
    private Calendar parseDateToCalendar(@Nullable String value) {
        String normalized = normalizeDateOnly(value);
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            format.setLenient(false);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(format.parse(normalized));
            return calendar;
        } catch (ParseException | NullPointerException e) {
            return null;
        }
    }

    private String formatDate(Calendar calendar) {
        return String.format(Locale.getDefault(), "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private String emptyToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String emptyToNullableString(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    private String trimDecimal(double value) {
        if (value == (long) value) {
            return String.format(Locale.getDefault(), "%d", (long) value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private void setSpinnerSelection(Spinner spinner, List<String> values, String target) {
        if (target == null) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            if (target.equals(values.get(i))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }

    @Override
    public void onConsume(InventoryItem item) {
        showConsumeDialog(item);
    }

    @Override
    public void onReplenish(InventoryItem item) {
        showReplenishDialog(item);
    }

    @Override
    public void onEdit(InventoryItem item) {
        showInventoryDialog(item);
    }

    @Override
    public void onDelete(InventoryItem item) {
        showDeleteDialog(item);
    }

    public static class InventoryItem {
        public int id;
        public int userId;
        public Integer relationshipId;
        public String name;
        public String category;
        public String imageUrl;
        public double quantity;
        public String unit;
        public double threshold;
        public String createdAt;
        public String updatedAt;
        public String lastConsumedAt;
        public String expirationMode;
        public String expirationDate;
        public String productionDate;
        public Integer shelfLifeDays;
        public boolean isExpiring;
        public boolean isExpired;
        public String note;
        public String aiImagePrompt;
        public boolean isLowStock;
        public String lastActionLabel;

        public boolean isLowStock() {
            return isLowStock || quantity <= threshold;
        }
    }

    private interface DateSelectionListener {
        void onDateSelected(String date);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    private class ToastMutationCallback implements InventoryUtils.InventoryMutationCallback {
        private final String successMessage;

        ToastMutationCallback(String successMessage) {
            this.successMessage = successMessage;
        }

        @Override
        public void onSuccess() {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(getContext(), successMessage, Toast.LENGTH_SHORT).show();
                refreshInventoryData();
            });
        }

        @Override
        public void onError(String error) {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show());
        }
    }
}
