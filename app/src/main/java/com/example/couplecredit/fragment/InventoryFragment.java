package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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
import java.util.List;
import java.util.Locale;

public class InventoryFragment extends Fragment implements InventoryAdapter.InventoryActionListener {

    private LinearLayout llAlertBanner;
    private TextView tvAlertMessage;
    private TextView tvViewAlert;
    private EditText etSearch;
    private Spinner spinnerCategory;
    private RecyclerView rvRecentActivity;
    private RecyclerView rvInventoryList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private LinearLayout layoutContent;
    private TextView btnLoginPrompt;
    private TextView tvTotalCount;
    private TextView tvLowStockCount;
    private TextView tvRecentCount;
    private View fabAddInventory;

    private InventoryAdapter inventoryAdapter;
    private RecentActivityAdapter recentActivityAdapter;
    private final List<InventoryItem> inventoryList = new ArrayList<>();
    private final List<InventoryItem> lowStockList = new ArrayList<>();
    private final List<InventoryItem> recentActivityList = new ArrayList<>();
    private final List<InventoryItem> filteredInventoryList = new ArrayList<>();
    private String currentCategoryFilter = "全部";
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
        refreshInventoryData();
    }

    private void initViews(View view) {
        llAlertBanner = view.findViewById(R.id.ll_alert_banner);
        tvAlertMessage = view.findViewById(R.id.tv_alert_message);
        tvViewAlert = view.findViewById(R.id.tv_view_alert);
        etSearch = view.findViewById(R.id.et_search);
        spinnerCategory = view.findViewById(R.id.spinner_category);
        rvRecentActivity = view.findViewById(R.id.rv_recent_activity);
        rvInventoryList = view.findViewById(R.id.rv_inventory_list);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        layoutLoginPrompt = view.findViewById(R.id.layout_login_prompt);
        layoutContent = view.findViewById(R.id.layout_content);
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        tvTotalCount = view.findViewById(R.id.tv_total_count);
        tvLowStockCount = view.findViewById(R.id.tv_low_stock_count);
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
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, categories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(categoryAdapter);
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCategoryFilter = categories[position];
                applyFilters();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                applyFilters();
            }
        });
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
        tvViewAlert.setOnClickListener(v -> showLowStockDialog());
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
        spinnerCategory.setEnabled(isLoggedIn);
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
        for (InventoryItem item : inventoryList) {
            boolean matchCategory = "全部".equals(currentCategoryFilter) || currentCategoryFilter.equals(item.category);
            boolean matchKeyword = TextUtils.isEmpty(keyword)
                    || (item.name != null && item.name.toLowerCase(Locale.getDefault()).contains(keyword))
                    || (item.note != null && item.note.toLowerCase(Locale.getDefault()).contains(keyword));
            if (matchCategory && matchKeyword) {
                filteredInventoryList.add(item);
            }
        }
        inventoryAdapter.updateData(filteredInventoryList);
        llEmptyState.setVisibility(filteredInventoryList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
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

        String imageUrl = null;
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
            imageUrl = existingItem.imageUrl;
            pendingImageUrl = imageUrl;
            if (imageUrl != null && !imageUrl.isEmpty()) {
                Glide.with(this).load(imageUrl).placeholder(R.drawable.ic_inventory_placeholder).into(ivAddImage);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("消耗 " + item.name);
        final EditText input = new EditText(getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("消耗数量");
        input.setText("1");
        builder.setView(input);
        builder.setPositiveButton("确认消耗", (dialog, which) -> {
            String amountStr = input.getText().toString().trim();
            if (TextUtils.isEmpty(amountStr)) {
                Toast.makeText(getContext(), "请输入消耗数量", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                double amount = Double.parseDouble(amountStr);
                InventoryUtils.consumeInventory(requireContext(), item.id, amount, new ToastMutationCallback("消耗记录成功"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    public void showReplenishDialog(InventoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("补货 " + item.name);
        final EditText input = new EditText(getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("补货数量");
        input.setText("1");
        builder.setView(input);
        builder.setPositiveButton("确认补货", (dialog, which) -> {
            String amountStr = input.getText().toString().trim();
            if (TextUtils.isEmpty(amountStr)) {
                Toast.makeText(getContext(), "请输入补货数量", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                double amount = Double.parseDouble(amountStr);
                InventoryUtils.replenishInventory(requireContext(), item.id, amount, new ToastMutationCallback("补货成功"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showDeleteDialog(InventoryItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除物资")
                .setMessage("确定删除“" + item.name + "”吗？")
                .setPositiveButton("删除", (dialog, which) -> InventoryUtils.deleteInventory(requireContext(), item.id, new ToastMutationCallback("删除成功")))
                .setNegativeButton("取消", null)
                .show();
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
