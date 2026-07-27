package com.example.couplecredit.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.AssetAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class AssetsActivity extends AppCompatActivity implements AssetAdapter.OnAssetClickListener {

    private static final String PREFS_NAME = "assets_list_cache";
    private static final String KEY_LIST_CACHE = "list_cache";
    private static final String KEY_STATS_CACHE = "stats_cache";

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvAssets;
    private AssetAdapter adapter;
    private TextView tvTotalValue, tvTotalCount, tvDailyAvg, tvEmpty;
    private LinearLayout llCategoryFilters, llStatusFilters;
    private int currentUserId;
    private String selectedCategory = null;
    private String selectedStatus = null;
    // 节流：防止 onResume / 筛选 chip 重建连锁触发导致 assets/stats 接口被反复请求
    private long lastLoadAssetsTs = 0;
    private long lastLoadStatsTs = 0;
    private static final long LOAD_THROTTLE_MS = 2000; // 2 秒内不重复请求同一接口

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
        loadFromCache();
        loadCategories();
        loadStats();
        loadAssets();
    }

    private void initViews() {
        swipeRefresh = findViewById(R.id.swipe_refresh);
        rvAssets = findViewById(R.id.rv_assets);
        tvTotalValue = findViewById(R.id.tv_total_value);
        tvTotalCount = findViewById(R.id.tv_total_count);
        tvDailyAvg = findViewById(R.id.tv_daily_avg);
        llCategoryFilters = findViewById(R.id.ll_category_filters);
        llStatusFilters = findViewById(R.id.ll_status_filters);
        tvEmpty = findViewById(R.id.tv_empty);

        ImageView ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        adapter = new AssetAdapter(new ArrayList<>(), this);
        // 使用 GridLayoutManager 而非 StaggeredGridLayoutManager:
        // 瀑布流在 wrap_content + ScrollView 场景下只会测量首屏可见的 item,
        // 导致超出首屏(2 列 × 2 行 = 4 个)的资产永远显示不出来。
        // 3 列布局:提高单屏信息密度。
        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        rvAssets.setLayoutManager(layoutManager);
        rvAssets.setAdapter(adapter);
    }

    private void setupListeners() {
        swipeRefresh.setColorSchemeResources(R.color.primary_color);
        swipeRefresh.setOnRefreshListener(this::refreshData);

        findViewById(R.id.fab_add).setOnClickListener(v -> {
            startActivity(new Intent(this, AddAssetActivity.class));
        });

        findViewById(R.id.iv_refresh).setOnClickListener(v -> refreshData());
    }

    private void loadFromCache() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String cachedStats = prefs.getString(KEY_STATS_CACHE, null);
        if (cachedStats != null) {
            try {
                AuthApiModels.AssetStatsResponse stats = AuthApiClient.GSON.fromJson(cachedStats, AuthApiModels.AssetStatsResponse.class);
                if (stats != null && stats.data != null) {
                    tvTotalValue.setText("¥ " + String.format("%.2f", stats.data.totalValue));
                    tvTotalCount.setText(stats.data.totalCount + " 件物品");
                    tvDailyAvg.setText("日总 ¥" + String.format("%.1f", stats.data.dailyAvgCost));
                }
            } catch (Exception ignored) {}
        }

        String cachedList = prefs.getString(KEY_LIST_CACHE, null);
        if (cachedList != null) {
            try {
                AuthApiModels.AssetListResponse list = AuthApiClient.GSON.fromJson(cachedList, AuthApiModels.AssetListResponse.class);
                if (list != null && list.data != null && list.data.items != null) {
                    adapter.updateData(list.data.items);
                    boolean empty = list.data.items.isEmpty();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    rvAssets.setVisibility(empty ? View.GONE : View.VISIBLE);
                }
            } catch (Exception ignored) {}
        }
    }

    private void saveListCache(AuthApiModels.AssetListResponse response) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_LIST_CACHE, AuthApiClient.GSON.toJson(response)).apply();
        } catch (Exception ignored) {}
    }

    private void saveStatsCache(AuthApiModels.AssetStatsResponse response) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_STATS_CACHE, AuthApiClient.GSON.toJson(response)).apply();
        } catch (Exception ignored) {}
    }

    private void refreshData() {
        swipeRefresh.setRefreshing(true);
        // 用户下拉刷新：强制重置节流时间戳，确保请求发出
        lastLoadAssetsTs = 0;
        lastLoadStatsTs = 0;
        loadCategories();
        loadStats();
        loadAssets();
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
        // 节流：2 秒内不重复请求
        long now = System.currentTimeMillis();
        if (now - lastLoadStatsTs < LOAD_THROTTLE_MS) {
            return;
        }
        lastLoadStatsTs = now;
        AuthApiClient.getAssetStats(this, currentUserId, new AuthApiClient.AssetStatsCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetStatsResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null) {
                        saveStatsCache(response);
                        tvTotalValue.setText("¥ " + String.format("%.2f", response.data.totalValue));
                        tvTotalCount.setText(response.data.totalCount + " 件物品");
                        tvDailyAvg.setText("日总 ¥" + String.format("%.1f", response.data.dailyAvgCost));
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (!swipeRefresh.isRefreshing()) {
                        Toast.makeText(AssetsActivity.this, "加载统计失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void loadAssets() {
        // 节流：2 秒内不重复请求（防止 onResume/筛选重建连锁触发）
        long now = System.currentTimeMillis();
        if (now - lastLoadAssetsTs < LOAD_THROTTLE_MS) {
            return;
        }
        lastLoadAssetsTs = now;
        AuthApiClient.getAssetsByFilter(this, currentUserId, selectedCategory, selectedStatus,
                new AuthApiClient.AssetListCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.AssetListResponse response) {
                        runOnUiThread(() -> {
                            swipeRefresh.setRefreshing(false);
                            if (response.data != null && response.data.items != null) {
                                saveListCache(response);
                                adapter.updateData(response.data.items);
                                boolean empty = response.data.items.isEmpty();
                                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                                rvAssets.setVisibility(empty ? View.GONE : View.VISIBLE);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            swipeRefresh.setRefreshing(false);
                            if (!swipeRefresh.isRefreshing()) {
                                Toast.makeText(AssetsActivity.this, "加载资产失败", Toast.LENGTH_SHORT).show();
                            }
                        });
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