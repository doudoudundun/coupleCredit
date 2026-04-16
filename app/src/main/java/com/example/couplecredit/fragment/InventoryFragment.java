package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.adapter.InventoryAdapter;
import com.example.couplecredit.adapter.RecentActivityAdapter;
import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.database.InventoryDatabaseHelper;
import com.example.couplecredit.utils.UserInfoManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 存货清单Fragment
 * 管理家庭日用品/菜品存货
 */
public class InventoryFragment extends Fragment {

    private static final String TAG = "InventoryFragment";

    // UI组件
    private LinearLayout llAlertBanner;
    private TextView tvAlertMessage;
    private TextView tvViewAlert;
    private EditText etSearch;
    private Spinner spinnerCategory;
    private RecyclerView rvRecentActivity;
    private RecyclerView rvInventoryList;
    private LinearLayout llEmptyState;

    // 数据
    private InventoryDatabaseHelper inventoryHelper;
    private InventoryAdapter inventoryAdapter;
    private RecentActivityAdapter recentActivityAdapter;
    private int currentUserId;
    private Integer currentRelationshipId;
    private List<InventoryItem> inventoryList = new ArrayList<>();
    private List<InventoryItem> lowStockList = new ArrayList<>();
    private List<InventoryItem> recentActivityList = new ArrayList<>();
    private String currentCategoryFilter = "全部";

    // 类别选项
    private final String[] categories = {"全部", "食材", "日用品", "调料", "饮品", "药品", "其他"};
    private final String[] units = {"个", "包", "瓶", "盒", "袋", "斤", "克", "升", "毫升"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inventory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initViews(view);
        initData();
        setupListeners();
        loadInventoryData();
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

        // 设置RecyclerView
        rvInventoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRecentActivity.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));

        inventoryAdapter = new InventoryAdapter(inventoryList, this);
        recentActivityAdapter = new RecentActivityAdapter(recentActivityList);

        rvInventoryList.setAdapter(inventoryAdapter);
        rvRecentActivity.setAdapter(recentActivityAdapter);

        // 设置类别筛选器
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, categories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(categoryAdapter);

        // 设置添加按钮
        view.findViewById(R.id.fab_add_inventory).setOnClickListener(v -> showAddInventoryDialog());
    }

    private void initData() {
        if (getContext() == null) return;

        inventoryHelper = new InventoryDatabaseHelper(getContext());

        // 获取当前用户信息
        if (UserInfoManager.isUserLoggedIn(requireContext())) {
            currentUserId = UserInfoManager.getCurrentUserId(requireContext());
            // 获取情侣关系ID（异步方式）
            CoupleRelationshipHelper relationshipHelper = new CoupleRelationshipHelper();
            relationshipHelper.getUserRelationshipIdOptimized(currentUserId, new CoupleRelationshipHelper.RelationshipIdCallback() {
                @Override
                public void onRelationshipIdFound(int relationshipId) {
                    currentRelationshipId = relationshipId;
                    // 重新加载数据
                    if (inventoryHelper != null) {
                        loadInventoryData();
                    }
                }

                @Override
                public void onNoRelationshipFound() {
                    currentRelationshipId = null;
                    // 加载个人数据
                    if (inventoryHelper != null) {
                        loadInventoryData();
                    }
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "获取关系ID失败: " + error);
                    currentRelationshipId = null;
                    // 加载个人数据
                    if (inventoryHelper != null) {
                        loadInventoryData();
                    }
                }
            });
        }
    }

    private void setupListeners() {
        // 类别筛选
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCategoryFilter = categories[position];
                filterInventoryList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 搜索功能
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            filterInventoryList();
            return false;
        });

        // 查看告急存货
        tvViewAlert.setOnClickListener(v -> showLowStockDialog());
    }

    private void loadInventoryData() {
        if (inventoryHelper == null) return;

        inventoryHelper.queryInventory(currentUserId, currentRelationshipId,
                new InventoryDatabaseHelper.InventoryQueryCallback() {
                    @Override
                    public void onQuerySuccess(Cursor cursor) {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            inventoryList.clear();
                            lowStockList.clear();
                            recentActivityList.clear();

                            if (cursor != null && cursor.moveToFirst()) {
                                // 缓存列索引，避免循环中重复查找
                                int idCol = cursor.getColumnIndexOrThrow("inventory_id");
                                int userIdCol = cursor.getColumnIndexOrThrow("user_id");
                                int relIdCol = cursor.getColumnIndexOrThrow("relationship_id");
                                int nameCol = cursor.getColumnIndexOrThrow("name");
                                int categoryCol = cursor.getColumnIndexOrThrow("category");
                                int imageUrlCol = cursor.getColumnIndexOrThrow("image_url");
                                int quantityCol = cursor.getColumnIndexOrThrow("quantity");
                                int unitCol = cursor.getColumnIndexOrThrow("unit");
                                int thresholdCol = cursor.getColumnIndexOrThrow("threshold");
                                int createdAtCol = cursor.getColumnIndexOrThrow("created_at");
                                int updatedAtCol = cursor.getColumnIndexOrThrow("updated_at");
                                int lastConsumedCol = cursor.getColumnIndexOrThrow("last_consumed_at");
                                int noteCol = cursor.getColumnIndexOrThrow("note");
                                int aiPromptCol = cursor.getColumnIndexOrThrow("ai_image_prompt");

                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                                long oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000;

                                do {
                                    InventoryItem item = new InventoryItem();
                                    item.id = cursor.getInt(idCol);
                                    item.userId = cursor.getInt(userIdCol);
                                    item.relationshipId = cursor.isNull(relIdCol) ?
                                            null : cursor.getInt(relIdCol);
                                    item.name = cursor.getString(nameCol);
                                    item.category = cursor.getString(categoryCol);
                                    item.imageUrl = cursor.getString(imageUrlCol);
                                    item.quantity = cursor.getDouble(quantityCol);
                                    item.unit = cursor.getString(unitCol);
                                    item.threshold = cursor.getDouble(thresholdCol);
                                    item.createdAt = cursor.getString(createdAtCol);
                                    item.updatedAt = cursor.getString(updatedAtCol);
                                    item.lastConsumedAt = cursor.getString(lastConsumedCol);
                                    item.note = cursor.getString(noteCol);
                                    item.aiImagePrompt = cursor.getString(aiPromptCol);

                                    inventoryList.add(item);

                                    // 检查是否告急
                                    if (item.quantity <= item.threshold) {
                                        lowStockList.add(item);
                                    }

                                    // 检查是否最近动态（24小时内更新）
                                    try {
                                        if (item.updatedAt != null) {
                                            Date updateDate = sdf.parse(item.updatedAt);
                                            if (updateDate != null && updateDate.getTime() > oneDayAgo) {
                                                recentActivityList.add(item);
                                            }
                                        }
                                    } catch (Exception e) {
                                        Log.w(TAG, "日期解析失败: " + item.updatedAt);
                                    }

                                } while (cursor.moveToNext());
                                cursor.close();
                            }

                            // 更新UI
                            updateUI();
                        });
                    }

                    @Override
                    public void onQueryError(String error) {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), "加载存货失败: " + error, Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "查询存货失败: " + error);
                        });
                    }
                });
    }

    private void updateUI() {
        // 更新告急提示
        if (!lowStockList.isEmpty()) {
            llAlertBanner.setVisibility(View.VISIBLE);
            tvAlertMessage.setText("有 " + lowStockList.size() + " 项存货告急！");
        } else {
            llAlertBanner.setVisibility(View.GONE);
        }

        // 更新列表
        filterInventoryList();

        // 更新空状态
        if (inventoryList.isEmpty()) {
            llEmptyState.setVisibility(View.VISIBLE);
            rvInventoryList.setVisibility(View.GONE);
        } else {
            llEmptyState.setVisibility(View.GONE);
            rvInventoryList.setVisibility(View.VISIBLE);
        }

        // 更新最近动态
        if (recentActivityList.isEmpty()) {
            rvRecentActivity.setVisibility(View.GONE);
        } else {
            rvRecentActivity.setVisibility(View.VISIBLE);
            recentActivityAdapter.updateData(recentActivityList);
        }
    }

    private void filterInventoryList() {
        String searchText = etSearch.getText().toString().toLowerCase();
        List<InventoryItem> filteredList = new ArrayList<>();

        for (InventoryItem item : inventoryList) {
            boolean matchCategory = currentCategoryFilter.equals("全部") ||
                    item.category.equals(currentCategoryFilter);
            boolean matchSearch = TextUtils.isEmpty(searchText) ||
                    item.name.toLowerCase().contains(searchText) ||
                    (item.note != null && item.note.toLowerCase().contains(searchText));

            if (matchCategory && matchSearch) {
                filteredList.add(item);
            }
        }

        inventoryAdapter.updateData(filteredList);
    }

    private void showAddInventoryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_inventory, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        // 初始化对话框组件
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
        TextView btnAdd = dialogView.findViewById(R.id.btn_add_inventory);

        // 设置类别和单位选项
        String[] addCategories = {"食材", "日用品", "调料", "饮品", "药品", "其他"};
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, addCategories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategoryDialog.setAdapter(categoryAdapter);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, units);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUnit.setAdapter(unitAdapter);

        // 选择图片（预留功能）
        tvSelectImage.setOnClickListener(v -> {
            Toast.makeText(getContext(), "图片选择功能待实现", Toast.LENGTH_SHORT).show();
        });

        // AI生图（预留功能）
        tvAiGenerate.setOnClickListener(v -> {
            etAiPrompt.setVisibility(View.VISIBLE);
            Toast.makeText(getContext(), "AI生图接口已预留，请配置服务后使用", Toast.LENGTH_SHORT).show();
        });

        // 添加按钮
        btnAdd.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String category = spinnerCategoryDialog.getSelectedItem().toString();
            String quantityStr = etQuantity.getText().toString().trim();
            String unit = spinnerUnit.getSelectedItem().toString();
            String thresholdStr = etThreshold.getText().toString().trim();
            String note = etNote.getText().toString().trim();
            String aiPrompt = etAiPrompt.getText().toString().trim();

            // 验证输入
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(getContext(), "请输入存货名称", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(quantityStr)) {
                Toast.makeText(getContext(), "请输入数量", Toast.LENGTH_SHORT).show();
                return;
            }

            double quantity;
            double threshold = 1;
            try {
                quantity = Double.parseDouble(quantityStr);
                if (!TextUtils.isEmpty(thresholdStr)) {
                    threshold = Double.parseDouble(thresholdStr);
                }
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            // 创建存货数据
            ContentValues values = new ContentValues();
            values.put("user_id", currentUserId);
            if (currentRelationshipId != null) {
                values.put("relationship_id", currentRelationshipId);
            }
            values.put("name", name);
            values.put("category", category);
            values.put("quantity", quantity);
            values.put("unit", unit);
            values.put("threshold", threshold);
            if (!TextUtils.isEmpty(note)) {
                values.put("note", note);
            }
            if (!TextUtils.isEmpty(aiPrompt)) {
                values.put("ai_image_prompt", aiPrompt);
            }

            // 保存到数据库
            inventoryHelper.insertInventory(values, new InventoryDatabaseHelper.InventoryInsertCallback() {
                @Override
                public void onInsertSuccess(long id) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "存货添加成功", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadInventoryData();
                    });
                }

                @Override
                public void onInsertError(String error) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "添加失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        dialog.show();
    }

    private void showLowStockDialog() {
        if (lowStockList.isEmpty()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("告急存货列表");

        String[] items = new String[lowStockList.size()];
        for (int i = 0; i < lowStockList.size(); i++) {
            InventoryItem item = lowStockList.get(i);
            items[i] = item.name + " - 剩余: " + item.quantity + " " + item.unit;
        }

        builder.setItems(items, (dialog, which) -> {
            // 点击后可以进行补货操作
            InventoryItem item = lowStockList.get(which);
            showReplenishDialog(item);
        });

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

            double consumeAmount;
            try {
                consumeAmount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            inventoryHelper.consumeInventory(item.id, consumeAmount,
                    new InventoryDatabaseHelper.InventoryUpdateCallback() {
                        @Override
                        public void onUpdateSuccess(int rowsUpdated) {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "消耗记录成功", Toast.LENGTH_SHORT).show();
                                loadInventoryData();
                            });
                        }

                        @Override
                        public void onUpdateError(String error) {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
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

            double addAmount;
            try {
                addAmount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "数量格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }

            inventoryHelper.replenishInventory(item.id, addAmount,
                    new InventoryDatabaseHelper.InventoryUpdateCallback() {
                        @Override
                        public void onUpdateSuccess(int rowsUpdated) {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "补货成功", Toast.LENGTH_SHORT).show();
                                loadInventoryData();
                            });
                        }

                        @Override
                        public void onUpdateError(String error) {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "补货失败: " + error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * 存货数据模型
     */
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

        public boolean isLowStock() {
            return quantity <= threshold;
        }
    }
}