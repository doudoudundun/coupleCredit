package com.example.couplecredit.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.AccountAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class AccountsActivity extends AppCompatActivity implements AccountAdapter.OnAccountClickListener {

    private static final String PREFS_NAME = "accounts_list_cache";
    private static final String KEY_LIST_CACHE = "list_cache";

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView rvAccounts;
    private AccountAdapter adapter;
    private TextView tvTotalCount, tvCategoryCount, tvEmpty;
    private LinearLayout llCategoryFilters;

    private int currentUserId;
    private String selectedCategory = null;
    private List<String> allCategories = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);

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
        loadAccounts();
    }

    private void initViews() {
        swipeRefresh = findViewById(R.id.swipe_refresh);
        rvAccounts = findViewById(R.id.rv_accounts);
        tvTotalCount = findViewById(R.id.tv_total_count);
        tvCategoryCount = findViewById(R.id.tv_category_count);
        llCategoryFilters = findViewById(R.id.ll_category_filters);
        tvEmpty = findViewById(R.id.tv_empty);

        ImageButton ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        adapter = new AccountAdapter(new ArrayList<>(), this);
        // 账号条目信息较多，用线性列表而非网格
        rvAccounts.setLayoutManager(new LinearLayoutManager(this));
        rvAccounts.setAdapter(adapter);
    }

    private void setupListeners() {
        swipeRefresh.setColorSchemeResources(R.color.primary_color);
        swipeRefresh.setOnRefreshListener(this::refreshData);

        findViewById(R.id.fab_add).setOnClickListener(v ->
                startActivity(new Intent(this, AddAccountActivity.class)));

        findViewById(R.id.iv_refresh).setOnClickListener(v -> refreshData());
    }

    private void loadFromCache() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cachedList = prefs.getString(KEY_LIST_CACHE, null);
        if (cachedList != null) {
            try {
                AuthApiModels.PasswordAccountListResponse list =
                        AuthApiClient.GSON.fromJson(cachedList, AuthApiModels.PasswordAccountListResponse.class);
                if (list != null && list.data != null && list.data.items != null) {
                    adapter.updateData(list.data.items);
                    updateOverview(list.data.items.size());
                    toggleEmpty(list.data.items.isEmpty());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void saveListCache(AuthApiModels.PasswordAccountListResponse response) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_LIST_CACHE, AuthApiClient.GSON.toJson(response)).apply();
        } catch (Exception ignored) {
        }
    }

    private void refreshData() {
        swipeRefresh.setRefreshing(true);
        loadCategories();
        loadAccounts();
    }

    private void loadCategories() {
        AuthApiClient.getPasswordAccountCategories(this, currentUserId,
                new AuthApiClient.PasswordAccountCategoryListCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.PasswordAccountCategoryListResponse response) {
                        runOnUiThread(() -> {
                            if (response.data != null && response.data.categories != null) {
                                allCategories = response.data.categories;
                                setupCategoryFilters();
                                tvCategoryCount.setText(allCategories.size() + " 个分类");
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> setupCategoryFilters());
                    }
                });
    }

    private void setupCategoryFilters() {
        llCategoryFilters.removeAllViews();
        addCategoryChip("全部", true);
        for (String category : allCategories) {
            addCategoryChip(category, false);
        }
    }

    private void addCategoryChip(String text, boolean isSelected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setPadding(dpToPx(14), dpToPx(5), dpToPx(14), dpToPx(5));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dpToPx(6), 0);
        chip.setLayoutParams(params);

        applyChipStyle(chip, isSelected);

        chip.setOnClickListener(v -> {
            selectedCategory = text.equals("全部") ? null : text;
            refreshCategorySelection();
            loadAccounts();
        });

        llCategoryFilters.addView(chip);
    }

    private void refreshCategorySelection() {
        for (int i = 0; i < llCategoryFilters.getChildCount(); i++) {
            View child = llCategoryFilters.getChildAt(i);
            if (child instanceof TextView) {
                TextView chip = (TextView) child;
                String chipText = chip.getText().toString();
                boolean selected = (selectedCategory == null && chipText.equals("全部")) ||
                        (selectedCategory != null && selectedCategory.equals(chipText));
                applyChipStyle(chip, selected);
            }
        }
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_chip_selected);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip_default);
            chip.setTextColor(Color.parseColor("#666666"));
        }
    }

    private void loadAccounts() {
        AuthApiClient.getPasswordAccounts(this, currentUserId, selectedCategory,
                new AuthApiClient.PasswordAccountListCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.PasswordAccountListResponse response) {
                        runOnUiThread(() -> {
                            swipeRefresh.setRefreshing(false);
                            if (response.data != null && response.data.items != null) {
                                // 仅在无筛选时缓存（带分类筛选的结果不缓存）
                                if (selectedCategory == null) {
                                    saveListCache(response);
                                }
                                adapter.updateData(response.data.items);
                                updateOverview(response.data.items.size());
                                toggleEmpty(response.data.items.isEmpty());
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            swipeRefresh.setRefreshing(false);
                            Toast.makeText(AccountsActivity.this, "加载账号失败", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void updateOverview(int count) {
        tvTotalCount.setText(count + " 个账号");
    }

    private void toggleEmpty(boolean empty) {
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvAccounts.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onAccountClick(AuthApiModels.PasswordAccountItemData account) {
        Intent intent = new Intent(this, AccountDetailActivity.class);
        intent.putExtra("accountId", account.accountId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCategories();
        loadAccounts();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
