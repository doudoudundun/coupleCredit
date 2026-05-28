package com.example.couplecredit.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.UserInfoManager;

public class AssetDetailActivity extends AppCompatActivity {

    private int assetId;
    private int currentUserId;
    private AuthApiModels.AssetItemData currentAsset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_asset_detail);

        assetId = getIntent().getIntExtra("assetId", -1);
        currentUserId = UserInfoManager.getCurrentUserId(this);

        if (assetId == -1 || currentUserId == -1) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        loadAssetDetail();
    }

    private void initViews() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        findViewById(R.id.iv_delete).setOnClickListener(v -> confirmDelete());

        findViewById(R.id.btn_edit).setOnClickListener(v -> {
            // TODO: Edit asset - will be implemented later
        });

        findViewById(R.id.btn_status).setOnClickListener(v -> toggleStatus());
    }

    private void loadAssetDetail() {
        AuthApiClient.getAssetById(this, assetId, currentUserId, new AuthApiClient.AssetListCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetListResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null && response.data.items != null && !response.data.items.isEmpty()) {
                        currentAsset = response.data.items.get(0);
                        displayAssetDetails();
                    } else {
                        Toast.makeText(AssetDetailActivity.this, "资产不存在", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(AssetDetailActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void displayAssetDetails() {
        if (currentAsset == null) return;

        // Image
        ImageView ivImage = findViewById(R.id.iv_asset_image);
        if (currentAsset.imageUrl != null && !currentAsset.imageUrl.isEmpty()) {
            String baseUrl = ApiConfigManager.getBaseUrl(this);
            Glide.with(this)
                    .load(baseUrl + currentAsset.imageUrl)
                    .placeholder(R.drawable.ic_asset_placeholder)
                    .centerCrop()
                    .into(ivImage);
        } else {
            ivImage.setImageResource(R.drawable.ic_asset_placeholder);
        }

        // Stats
        ((TextView) findViewById(R.id.tv_hold_days)).setText(String.valueOf(currentAsset.holdDays));
        ((TextView) findViewById(R.id.tv_daily_cost)).setText("¥" + String.format("%.1f", currentAsset.dailyCost));
        ((TextView) findViewById(R.id.tv_monthly_cost)).setText("¥" + String.format("%.0f", currentAsset.monthlyCost));

        // Details
        ((TextView) findViewById(R.id.tv_asset_name)).setText(currentAsset.name);
        ((TextView) findViewById(R.id.tv_asset_category)).setText(currentAsset.category);
        ((TextView) findViewById(R.id.tv_purchase_price)).setText(currentAsset.purchasePrice != null ?
                "¥" + String.format("%.2f", currentAsset.purchasePrice) : "未设置");
        ((TextView) findViewById(R.id.tv_purchase_date)).setText(currentAsset.purchaseDate != null ?
                currentAsset.purchaseDate : "未设置");
        ((TextView) findViewById(R.id.tv_current_value)).setText(currentAsset.currentValue != null ?
                "¥" + String.format("%.2f", currentAsset.currentValue) : "未设置");

        // Note
        TextView tvNote = findViewById(R.id.tv_note);
        if (currentAsset.note != null && !currentAsset.note.isEmpty()) {
            tvNote.setText(currentAsset.note);
            tvNote.setVisibility(View.VISIBLE);
        }

        // Status button
        updateStatusButton();
    }

    private void updateStatusButton() {
        if (currentAsset == null) return;

        Button btnStatus = findViewById(R.id.btn_status);
        switch (currentAsset.status) {
            case "active":
                btnStatus.setText("标记闲置");
                break;
            case "idle":
                btnStatus.setText("标记在用");
                break;
            case "disposed":
                btnStatus.setText("已处置");
                btnStatus.setEnabled(false);
                break;
        }
    }

    private void toggleStatus() {
        if (currentAsset == null) return;

        String newStatus;
        switch (currentAsset.status) {
            case "active":
                newStatus = "idle";
                break;
            case "idle":
                newStatus = "active";
                break;
            default:
                return;
        }

        AuthApiClient.updateAssetStatus(this, assetId, currentUserId, newStatus,
                new AuthApiClient.AssetStatusCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            currentAsset.status = newStatus;
                            updateStatusButton();
                            Toast.makeText(AssetDetailActivity.this, "状态更新成功", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() ->
                                Toast.makeText(AssetDetailActivity.this, "更新失败: " + message, Toast.LENGTH_SHORT).show()
                        );
                    }
                });
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除资产")
                .setMessage("确定要删除这个资产吗？")
                .setPositiveButton("删除", (dialog, which) -> deleteAsset())
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteAsset() {
        AuthApiClient.deleteAsset(this, assetId, currentUserId, new AuthApiClient.AssetDeleteCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(AssetDetailActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(AssetDetailActivity.this, "删除失败: " + message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }
}
