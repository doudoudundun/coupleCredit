package com.example.couplecredit.activity;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.AssetAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class AssetsActivity extends AppCompatActivity implements AssetAdapter.OnAssetClickListener {

    private RecyclerView rvAssets;
    private AssetAdapter adapter;
    private TextView tvTotalValue, tvTotalCount, tvDailyAvg, tvMonthlyAvg;
    private LinearLayout llCategoryFilters, llStatusFilters;
    private int currentUserId;
    private String selectedCategory = null;
    private String selectedStatus = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assets);

        currentUserId = UserInfoManager.getCurrentUserId(this);
        if (currentUserId == -1) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupRecyclerView();
        setupListeners();
        loadCategories();
        loadStats();
        loadAssets();
    }

    private void initViews() {
        rvAssets = findViewById(R.id.rv_assets);
        tvTotalValue = findViewById(R.id.tv_total_value);
        tvTotalCount = findViewById(R.id.tv_total_count);
        tvDailyAvg = findViewById(R.id.tv_daily_avg);
        tvMonthlyAvg = findViewById(R.id.tv_monthly_avg);
        llCategoryFilters = findViewById(R.id.ll_category_filters);
        llStatusFilters = findViewById(R.id.ll_status_filters);

        ImageView ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        adapter = new AssetAdapter(new ArrayList<>(), this);
        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        rvAssets.setLayoutManager(layoutManager);
        rvAssets.setAdapter(adapter);
    }

    private void setupListeners() {
        findViewById(R.id.fab_add).setOnClickListener(v -> {
            startActivity(new Intent(this, AddAssetActivity.class));
        });
    }

    private void loadCategories() {
        AuthApiClient.getAssetCategories(this, currentUserId, new AuthApiClient.AssetCategoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetCategoryListResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null && response.data.categories != null) {
                        setupCategoryFilters(response.data.categories);
                    }
                });
            }

            @Override
            public void onError(String message) {
                List<String> defaults = new ArrayList<>();
                defaults.add("电子设备");
                defaults.add("衣物");
                defaults.add("包包");
                defaults.add("鞋履");
                defaults.add("家具");
                runOnUiThread(() -> setupCategoryFilters(defaults));
            }
        });
    }

    private void setupCategoryFilters(List<String> categories) {
        llCategoryFilters.removeAllViews();

        // "All" button
        addCategoryChip("全部", true);

        for (String category : categories) {
            addCategoryChip(category, false);
        }
    }

    private void addCategoryChip(String text, boolean isSelected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(11);
        chip.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, dpToPx(6), 0);
        chip.setLayoutParams(params);

        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_chip_selected);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip_default);
            chip.setTextColor(Color.parseColor("#666666"));
        }

        chip.setOnClickListener(v -> {
            selectedCategory = text.equals("全部") ? null : text;
            refreshCategorySelection();
            loadAssets();
        });

        llCategoryFilters.addView(chip);
    }

    private void refreshCategorySelection() {
        for (int i = 0; i < llCategoryFilters.getChildCount(); i++) {
            View child = llCategoryFilters.getChildAt(i);
            if (child instanceof TextView) {
                TextView chip = (TextView) child;
                String chipText = chip.getText().toString();

                boolean isSelected = (selectedCategory == null && chipText.equals("全部")) ||
                        (selectedCategory != null && selectedCategory.equals(chipText));

                if (isSelected) {
                    chip.setBackgroundResource(R.drawable.bg_chip_selected);
                    chip.setTextColor(Color.WHITE);
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_default);
                    chip.setTextColor(Color.parseColor("#666666"));
                }
            }
        }
    }

    private void setupStatusFilters() {
        llStatusFilters.removeAllViews();

        String[] statuses = {"全部", "在用", "闲置", "已处置"};
        String[] statusValues = {null, "active", "idle", "disposed"};

        for (int i = 0; i < statuses.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(statuses[i]);
            chip.setTextSize(10);
            chip.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, dpToPx(6), 0);
            chip.setLayoutParams(params);

            boolean isSelected = (selectedStatus == statusValues[i]);

            if (isSelected) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected);
                chip.setTextColor(Color.WHITE);
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_default);
                chip.setTextColor(Color.parseColor("#666666"));
            }

            final String statusValue = statusValues[i];
            chip.setOnClickListener(v -> {
                selectedStatus = statusValue;
                setupStatusFilters();
                loadAssets();
            });

            llStatusFilters.addView(chip);
        }
    }

    private void loadStats() {
        AuthApiClient.getAssetStats(this, currentUserId, new AuthApiClient.AssetStatsCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetStatsResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null) {
                        tvTotalValue.setText("¥ " + String.format("%.2f", response.data.totalValue));
                        tvTotalCount.setText(response.data.totalCount + " 件物品");
                        tvDailyAvg.setText("日均 ¥" + String.format("%.1f", response.data.dailyAvgCost));
                        tvMonthlyAvg.setText("月均 ¥" + String.format("%.0f", response.data.monthlyAvgCost));
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(AssetsActivity.this, "加载统计失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadAssets() {
        AuthApiClient.getAssetsByFilter(this, currentUserId, selectedCategory, selectedStatus,
                new AuthApiClient.AssetListCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.AssetListResponse response) {
                        runOnUiThread(() -> {
                            if (response.data != null && response.data.items != null) {
                                adapter.updateData(response.data.items);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> Toast.makeText(AssetsActivity.this, "加载资产失败", Toast.LENGTH_SHORT).show());
                    }
                });
    }

    @Override
    public void onAssetClick(AuthApiModels.AssetItemData asset) {
        Intent intent = new Intent(this, AssetDetailActivity.class);
        intent.putExtra("assetId", asset.assetId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupStatusFilters();
        loadStats();
        loadAssets();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
