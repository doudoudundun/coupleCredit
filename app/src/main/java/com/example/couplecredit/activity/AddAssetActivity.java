package com.example.couplecredit.activity;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.Calendar;
import java.util.List;

public class AddAssetActivity extends AppCompatActivity {

    private ImageView ivAssetImage;
    private EditText etAssetName, etPurchaseDate, etPurchasePrice, etCurrentValue, etNote;
    private Spinner spinnerCategory;
    private int currentUserId;
    private String imageUrl = null;
    private String originalImageUrl = null;

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        ivAssetImage.setImageURI(imageUri);
                        originalImageUrl = imageUri.toString();
                        imageUrl = originalImageUrl;
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_asset);

        currentUserId = UserInfoManager.getCurrentUserId(this);
        if (currentUserId == -1) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupListeners();
        loadCategories();
    }

    private void initViews() {
        ivAssetImage = findViewById(R.id.iv_asset_image);
        etAssetName = findViewById(R.id.et_asset_name);
        etPurchaseDate = findViewById(R.id.et_purchase_date);
        etPurchasePrice = findViewById(R.id.et_purchase_price);
        etCurrentValue = findViewById(R.id.et_current_value);
        etNote = findViewById(R.id.et_note);
        spinnerCategory = findViewById(R.id.spinner_category);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
    }

    private void setupListeners() {
        ivAssetImage.setOnClickListener(v -> openImagePicker());

        etPurchaseDate.setOnClickListener(v -> showDatePicker());

        findViewById(R.id.btn_save).setOnClickListener(v -> saveAsset());
    }

    private void loadCategories() {
        AuthApiClient.getAssetCategories(this, currentUserId, new AuthApiClient.AssetCategoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetCategoryListResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null && response.data.categories != null) {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                AddAssetActivity.this,
                                android.R.layout.simple_spinner_item,
                                response.data.categories
                        );
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        spinnerCategory.setAdapter(adapter);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(AddAssetActivity.this, "加载分类失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
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

    private void saveAsset() {
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
                currentUserId, name, category, imageUrl, originalImageUrl,
                purchaseDate.isEmpty() ? null : purchaseDate,
                purchasePrice, currentValue, "active", note.isEmpty() ? null : note
        );

        findViewById(R.id.btn_save).setEnabled(false);

        AuthApiClient.createAsset(this, request, new AuthApiClient.AssetMutationCallback() {
            @Override
            public void onSuccess(AuthApiModels.AssetMutationResponse response) {
                runOnUiThread(() -> {
                    Toast.makeText(AddAssetActivity.this, "资产添加成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    findViewById(R.id.btn_save).setEnabled(true);
                    Toast.makeText(AddAssetActivity.this, "添加失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}
