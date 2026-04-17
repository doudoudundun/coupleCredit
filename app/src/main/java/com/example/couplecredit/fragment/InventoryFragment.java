package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.InventoryAdapter;
import com.example.couplecredit.adapter.RecentActivityAdapter;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.InventoryUtils;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.HashSet;
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
    private TextView btnLoginPrompt;
    private TextView tvTotalCount;
    private LinearLayout cardLowStock;
    private TextView tvLowStockCount;
    private LinearLayout cardRecent;
    private TextView tvRecentCount;
    private View fabAddInventory;

    private InventoryAdapter inventoryAdapter;
    private RecentActivityAdapter recentActivityAdapter;
    private final List<InventoryItem> inventoryList = new ArrayList<>();
    private final List<InventoryItem> lowStockList = new ArrayList<>();
    private final List<InventoryItem> recentActivityList = new ArrayList<>();
    private final List<InventoryItem> filteredInventoryList = new ArrayList<>();

    // 筛选状态
    private final Set<String> activeCategories = new HashSet<>();
    private boolean filterLowStock = false;
    private boolean filterRecent = false;

    private boolean isLoggedIn;

    private final String[] categories = {"全部", "食材", "日用品", "调料", "饮品", "药品", "其他"};
    private final String[] addCategories = {"食材", "日用品", "调料", "饮品", "药品", "其他"};
    private final String[] units = {"个", "包", "瓶", "盒", "袋", "斤", "克", "升", "毫升"};

    private ImageView pendingImageView;
    private String pendingImageUrl;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inventory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupRecyclerViews();
        setupFilters();
        setupActions();
        setupSummaryCards();
        refreshInventoryData();
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
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        tvTotalCount = view.findViewById(R.id.tv_total_count);
        cardLowStock = view.findViewById(R.id.card_low_stock);
        tvLowStockCount = view.findViewById(R.id.tv_low_stock_count);
        cardRecent = view.findViewById(R.id.card_recent);
        tvRecentCount = view.findViewById(R.id.tv_recent_count);
        fabAddInventory = view.findViewById(R.id.fab_add_inventory);
    }

    private void setupRecyclerViews() {
        rvInventoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRecentActivity.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        inventoryAdapter = new InventoryAdapter(filteredInventoryList, this);
        recentActivityAdapter = new RecentActivityAdapter(recentActivityList);
        rvInventoryList.setAdapter(inventoryAdapter);
        rvRecentActivity.setAdapter(recentActivityAdapter);
    }

    private void setupFilters() {
        // 分类标签
        buildCategoryTags();

        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                applyFilters();
            }
        });
    }

    private void buildCategoryTags() {
        llCategoryTags.removeAllViews();
        for (String cat : categories) {
            TextView tag = createCategoryTag(cat, activeCategories.contains(cat));
            tag.setOnClickListener(v -> toggleCategoryFilter(cat));
            llCategoryTags.addView(tag);
        }
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

    private void toggleCategoryFilter(String category) {
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
            filterLowStock = !filterLowStock;
            updateCardHighlight();
            applyFilters();
        });

        cardRecent.setOnClickListener(v -> {
            filterRecent = !filterRecent;
            updateCardHighlight();
            applyFilters();
        });
    }

    private void updateCardHighlight() {
        // 告急卡片高亮
        GradientDrawable lowBg = new GradientDrawable();
        lowBg.setCornerRadius(dp(12));
        if (filterLowStock) {
            lowBg.setColor(Color.parseColor("#FFF1F1"));
        } else {
            lowBg.setColor(Color.WHITE);
        }
        cardLowStock.setBackground(lowBg);

        // 最近变动卡片高亮
        GradientDrawable recentBg = new GradientDrawable();
        recentBg.setCornerRadius(dp(12));
        if (filterRecent) {
            recentBg.setColor(Color.parseColor("#E8F5E9"));
        } else {
            recentBg.setColor(Color.WHITE);
        }
        cardRecent.setBackground(recentBg);
    }

    private void setupActions() {
        fabAddInventory.setOnClickListener(v -> {
            if (!isLoggedIn) {
                openLoginPage();
                return;
            }
            showInventoryDialog(null);
        });

        btnLoginPrompt.setOnClickListener(v -> openLoginPage());
        tvViewAlert.setOnClickListener(v -> {
            filterLowStock = true;
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

        InventoryUtils.loadInventory(requireContext(), new InventoryUtils.InventoryLoadCallback() {
            @Override
            public void onSuccess(AuthApiModels.InventoryListResponse response) {
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> bindInventoryData(response));
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "加载物资失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
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
        tvRecentCount.setText(String.valueOf(recentActivityList.size()));

        if (lowStockList.isEmpty()) {
            llAlertBanner.setVisibility(View.GONE);
        } else {
            llAlertBanner.setVisibility(View.VISIBLE);
            tvAlertMessage.setText("有 " + lowStockList.size() + " 项物资已低于提醒阈值");
        }

        llEmptyState.setVisibility(inventoryList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
    }

    private void applyFilters() {
        filteredInventoryList.clear();
        String keyword = etSearch.getText() == null ? "" : etSearch.getText().toString().trim().toLowerCase(Locale.getDefault());

        // 构建"最近变动"名称集合用于快速查找
        Set<String> recentNames = new HashSet<>();
        for (InventoryItem ri : recentActivityList) {
            if (ri.name != null) recentNames.add(ri.name.toLowerCase(Locale.getDefault()));
        }

        for (InventoryItem item : inventoryList) {
            // 关键词
            boolean matchKeyword = TextUtils.isEmpty(keyword)
                    || (item.name != null && item.name.toLowerCase(Locale.getDefault()).contains(keyword))
                    || (item.note != null && item.note.toLowerCase(Locale.getDefault()).contains(keyword));
            if (!matchKeyword) continue;

            // 分类
            boolean matchCategory = activeCategories.isEmpty()
                    || activeCategories.contains(item.category);
            if (!matchCategory) continue;

            // 告急
            if (filterLowStock && !item.isLowStock()) continue;

            // 最近变动
            if (filterRecent && (item.name == null || !recentNames.contains(item.name.toLowerCase(Locale.getDefault())))) continue;

            filteredInventoryList.add(item);
        }

        inventoryAdapter.updateData(filteredInventoryList);
        llEmptyState.setVisibility(filteredInventoryList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
        rebuildActiveFilterTags();
    }

    private void rebuildActiveFilterTags() {
        flexActiveFilters.removeAllViews();

        // 分类标签
        for (String cat : activeCategories) {
            flexActiveFilters.addView(createActiveFilterTag("分类: " + cat, () -> {
                activeCategories.remove(cat);
                buildCategoryTags();
                applyFilters();
            }));
        }

        // 告急标签
        if (filterLowStock) {
            flexActiveFilters.addView(createActiveFilterTag("告急物资", () -> {
                filterLowStock = false;
                updateCardHighlight();
                applyFilters();
            }));
        }

        // 最近变动标签
        if (filterRecent) {
            flexActiveFilters.addView(createActiveFilterTag("最近变动", () -> {
                filterRecent = false;
                updateCardHighlight();
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

        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        EditText etName = dialogView.findViewById(R.id.et_inventory_name);
        Spinner spinnerCategoryDialog = dialogView.findViewById(R.id.spinner_add_category);
        EditText etQuantity = dialogView.findViewById(R.id.et_quantity);
        Spinner spinnerUnit = dialogView.findViewById(R.id.spinner_unit);
        EditText etThreshold = dialogView.findViewById(R.id.et_threshold);
        EditText etNote = dialogView.findViewById(R.id.et_note);
        EditText etAiPrompt = dialogView.findViewById(R.id.et_ai_prompt);
        ImageView ivAddImage = dialogView.findViewById(R.id.iv_add_image);
        TextView tvSelectImage = dialogView.findViewById(R.id.tv_select_image);
        TextView tvAiGenerate = dialogView.findViewById(R.id.tv_ai_generate);
        TextView btnSubmit = dialogView.findViewById(R.id.btn_add_inventory);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, addCategories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategoryDialog.setAdapter(categoryAdapter);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, units);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUnit.setAdapter(unitAdapter);

        tvSelectImage.setOnClickListener(v -> {
            pendingImageView = ivAddImage;
            checkAndRequestImagePermission();
        });
        tvAiGenerate.setOnClickListener(v -> {
            etAiPrompt.setVisibility(View.VISIBLE);
            Toast.makeText(getContext(), "AI 生图入口已保留，当前先记录提示词", Toast.LENGTH_SHORT).show();
        });

        pendingImageUrl = null;
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
            if (existingItem.imageUrl != null && !existingItem.imageUrl.isEmpty()) {
                Glide.with(this).load(existingItem.imageUrl).placeholder(R.drawable.ic_inventory_placeholder).into(ivAddImage);
            }
            setSpinnerSelection(spinnerCategoryDialog, addCategories, existingItem.category);
            setSpinnerSelection(spinnerUnit, units, existingItem.unit);
        } else {
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
            String currentImageUrl = pendingImageUrl;

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

            InventoryUtils.InventoryMutationCallback callback = new InventoryUtils.InventoryMutationCallback() {
                @Override
                public void onSuccess() {
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), existingItem == null ? "物资添加成功" : "物资更新成功", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
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
            };

            if (existingItem == null) {
                InventoryUtils.createInventory(requireContext(), name, category, quantity, unit, threshold, currentImageUrl, note, aiPrompt, callback);
            } else {
                InventoryUtils.updateInventory(requireContext(), existingItem.id, name, category, quantity, unit, threshold, currentImageUrl, note, aiPrompt, callback);
            }
        });

        dialog.show();
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

        dialog.show();
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

        dialog.show();
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

        dialog.show();
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
        item.imageUrl = itemData.imageUrl;
        item.quantity = itemData.quantity;
        item.unit = itemData.unit;
        item.threshold = itemData.threshold;
        item.createdAt = normalizeDateTime(itemData.createdAt);
        item.updatedAt = normalizeDateTime(itemData.updatedAt);
        item.lastConsumedAt = normalizeDateTime(itemData.lastConsumedAt);
        item.note = itemData.note;
        item.aiImagePrompt = itemData.aiImagePrompt;
        item.isLowStock = itemData.isLowStock || item.quantity <= item.threshold;
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
        if (value == null) {
            return null;
        }
        String normalized = value.replace('T', ' ');
        int dotIndex = normalized.indexOf('.');
        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }
        return normalized;
    }

    private String trimDecimal(double value) {
        if (value == (long) value) {
            return String.format(Locale.getDefault(), "%d", (long) value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private void setSpinnerSelection(Spinner spinner, String[] values, String target) {
        if (target == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) {
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
        public String note;
        public String aiImagePrompt;
        public boolean isLowStock;
        public String lastActionLabel;

        public boolean isLowStock() {
            return isLowStock || quantity <= threshold;
        }
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
