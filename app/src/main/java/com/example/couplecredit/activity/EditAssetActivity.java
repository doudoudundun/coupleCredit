package com.example.couplecredit.activity;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.ImageCompressor;
import com.example.couplecredit.utils.UserInfoManager;

import java.io.ByteArrayInputStream;
import java.util.Calendar;
import java.util.List;

public class EditAssetActivity extends AppCompatActivity {

    private enum UploadState { IDLE, UPLOADING, PROCESSING }

    private ImageView ivAssetImage;
    private LinearLayout llImageLoading;
    private TextView tvImageHint;
    private Button btnRemoveBg;
    private EditText etAssetName, etPurchaseDate, etPurchasePrice, etCurrentValue, etNote;
    private Spinner spinnerCategory;
    private View btnSave;
    private int currentUserId;
    private int assetId;
    private String imageUrl = null;
    private UploadState uploadState = UploadState.IDLE;

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        uploadImage(imageUri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_asset);

        assetId = getIntent().getIntExtra("assetId", -1);
        currentUserId = UserInfoManager.getCurrentUserId(this);

        if (assetId == -1 || currentUserId == -1) {
            Toast.makeText(this, "参数错误", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupListeners();
        loadAssetDetail();
    }

    private void initViews() {
        ivAssetImage = findViewById(R.id.iv_asset_image);
        llImageLoading = findViewById(R.id.ll_image_loading);
        tvImageHint = findViewById(R.id.tv_image_hint);
        btnRemoveBg = findViewById(R.id.btn_remove_bg);
        etAssetName = findViewById(R.id.et_asset_name);
        etPurchaseDate = findViewById(R.id.et_purchase_date);
        etPurchasePrice = findViewById(R.id.et_purchase_price);
        etCurrentValue = findViewById(R.id.et_current_value);
        etNote = findViewById(R.id.et_note);
        spinnerCategory = findViewById(R.id.spinner_category);
        btnSave = findViewById(R.id.btn_save);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
    }

    private void setupListeners() {
        ivAssetImage.setOnClickListener(v -> {
            if (uploadState == UploadState.IDLE) openImagePicker();
        });
        etPurchaseDate.setOnClickListener(v -> showDatePicker());
        btnRemoveBg.setOnClickListener(v -> {
            if (uploadState != UploadState.PROCESSING && imageUrl != null) doRemoveBackground();
        });
        btnSave.setOnClickListener(v -> updateAsset());
    }

    private void loadAssetDetail() {
        AuthApiClient.getAssetById(this, assetId, currentUserId, new AuthApiClient.AssetDetailCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetItemData asset) {
                runOnUiThread(() -> populateFields(asset));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(EditAssetActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void populateFields(AuthApiModels.AssetItemData asset) {
        etAssetName.setText(asset.name);
        etPurchaseDate.setText(asset.purchaseDate != null ? asset.purchaseDate : "");
        etPurchasePrice.setText(asset.purchasePrice != null ? String.valueOf(asset.purchasePrice) : "");
        etCurrentValue.setText(asset.currentValue != null ? String.valueOf(asset.currentValue) : "");
        etNote.setText(asset.note != null ? asset.note : "");
        imageUrl = asset.imageUrl;

        if (asset.imageUrl != null && !asset.imageUrl.isEmpty()) {
            String baseUrl = ApiConfigManager.getBaseUrl(this);
            Glide.with(this).load(baseUrl + asset.imageUrl).into(ivAssetImage);
            ivAssetImage.setColorFilter(null);
            tvImageHint.setText("点击更换照片");
            btnRemoveBg.setVisibility(View.VISIBLE);
        }

        AuthApiClient.getAssetCategories(this, currentUserId, new AuthApiClient.AssetCategoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetCategoryListResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null && response.data.categories != null) {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                EditAssetActivity.this,
                                android.R.layout.simple_spinner_item,
                                response.data.categories
                        );
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        spinnerCategory.setAdapter(adapter);

                        int pos = response.data.categories.indexOf(asset.category);
                        if (pos >= 0) spinnerCategory.setSelection(pos);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(EditAssetActivity.this, "加载分类失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    private void uploadImage(Uri imageUri) {
        uploadState = UploadState.UPLOADING;
        llImageLoading.setVisibility(View.VISIBLE);
        tvImageHint.setText("正在上传...");
        btnSave.setEnabled(false);
        btnSave.setAlpha(0.5f);
        btnRemoveBg.setVisibility(View.GONE);

        new Thread(() -> {
            byte[] compressed = ImageCompressor.compress(this, imageUri, 2048, 90);
            if (compressed == null) {
                runOnUiThread(() -> {
                    uploadState = UploadState.IDLE;
                    llImageLoading.setVisibility(View.GONE);
                    tvImageHint.setText("点击更换照片");
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1.0f);
                    Toast.makeText(this, "图片压缩失败", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            ByteArrayInputStream inputStream = new ByteArrayInputStream(compressed);
            String fileName = "asset_" + System.currentTimeMillis() + ".jpg";

            AuthApiClient.uploadImage(this, inputStream, fileName, new AuthApiClient.ImageUploadCallback() {
                @Override
                public void onSuccess(String serverImageUrl) {
                    runOnUiThread(() -> {
                        imageUrl = serverImageUrl;
                        uploadState = UploadState.IDLE;
                        llImageLoading.setVisibility(View.GONE);
                        tvImageHint.setText("点击更换照片");
                        btnSave.setEnabled(true);
                        btnSave.setAlpha(1.0f);
                        btnRemoveBg.setVisibility(View.VISIBLE);

                        String baseUrl = ApiConfigManager.getBaseUrl(EditAssetActivity.this);
                        Glide.with(EditAssetActivity.this)
                                .load(baseUrl + serverImageUrl)
                                .centerCrop()
                                .into(ivAssetImage);
                        ivAssetImage.setColorFilter(null);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        uploadState = UploadState.IDLE;
                        llImageLoading.setVisibility(View.GONE);
                        tvImageHint.setText("点击更换照片");
                        btnSave.setEnabled(true);
                        btnSave.setAlpha(1.0f);
                        Toast.makeText(EditAssetActivity.this, "图片上传失败: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }).start();
    }

    private void doRemoveBackground() {
        uploadState = UploadState.PROCESSING;
        llImageLoading.setVisibility(View.VISIBLE);
        tvImageHint.setText("正在抠图...");
        btnSave.setEnabled(false);
        btnSave.setAlpha(0.5f);
        btnRemoveBg.setEnabled(false);
        btnRemoveBg.setAlpha(0.5f);

        AuthApiClient.removeBackground(this, imageUrl, new AuthApiClient.RemoveBgCallback() {
            @Override
            public void onSuccess(AuthApiModels.RemoveBgResponse response) {
                runOnUiThread(() -> {
                    uploadState = UploadState.IDLE;
                    llImageLoading.setVisibility(View.GONE);
                    tvImageHint.setText("点击更换照片");
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1.0f);
                    btnRemoveBg.setEnabled(true);
                    btnRemoveBg.setAlpha(1.0f);

                    if (response.data != null && response.data.imageUrl != null) {
                        imageUrl = response.data.imageUrl;
                        String baseUrl = ApiConfigManager.getBaseUrl(EditAssetActivity.this);
                        Glide.with(EditAssetActivity.this)
                                .load(baseUrl + response.data.imageUrl)
                                .centerCrop()
                                .into(ivAssetImage);
                        Toast.makeText(EditAssetActivity.this, "抠图完成", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    uploadState = UploadState.IDLE;
                    llImageLoading.setVisibility(View.GONE);
                    tvImageHint.setText("点击更换照片");
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1.0f);
                    btnRemoveBg.setEnabled(true);
                    btnRemoveBg.setAlpha(1.0f);
                    Toast.makeText(EditAssetActivity.this, "抠图失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(this,
                (view, year, month, dayOfMonth) -> {
                    String date = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth);
                    etPurchaseDate.setText(date);
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void updateAsset() {
        if (uploadState != UploadState.IDLE) {
            Toast.makeText(this, "图片正在处理中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = etAssetName.getText().toString().trim();
        String category = spinnerCategory.getSelectedItem() != null ?
                spinnerCategory.getSelectedItem().toString() : "";
        String purchaseDate = etPurchaseDate.getText().toString().trim();
        String priceStr = etPurchasePrice.getText().toString().trim();
        String valueStr = etCurrentValue.getText().toString().trim();
        String note = etNote.getText().toString().trim();

        if (name.isEmpty()) {
            etAssetName.setError("请输入资产名称");
            etAssetName.requestFocus();
            return;
        }

        if (category.isEmpty()) {
            Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show();
            return;
        }

        Double purchasePrice = null;
        if (!priceStr.isEmpty()) {
            try {
                purchasePrice = Double.parseDouble(priceStr);
            } catch (NumberFormatException e) {
                etPurchasePrice.setError("请输入有效价格");
                etPurchasePrice.requestFocus();
                return;
            }
        }

        Double currentValue = null;
        if (!valueStr.isEmpty()) {
            try {
                currentValue = Double.parseDouble(valueStr);
            } catch (NumberFormatException e) {
                etCurrentValue.setError("请输入有效价格");
                etCurrentValue.requestFocus();
                return;
            }
        }

        AuthApiModels.CreateAssetRequest request = new AuthApiModels.CreateAssetRequest(
                currentUserId, name, category, imageUrl, imageUrl,
                purchaseDate.isEmpty() ? null : purchaseDate,
                purchasePrice, currentValue, "active", note.isEmpty() ? null : note
        );

        btnSave.setEnabled(false);
        btnSave.setAlpha(0.5f);

        AuthApiClient.updateAsset(this, assetId, request, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(EditAssetActivity.this, "更新成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1.0f);
                    Toast.makeText(EditAssetActivity.this, "更新失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}
