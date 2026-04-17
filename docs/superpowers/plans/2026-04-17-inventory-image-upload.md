# Inventory Image Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable camera capture and gallery selection for inventory item photos, upload them to the server, and display them to both partners.

**Architecture:** The server upload infrastructure is already complete. The work is purely Android-side: add camera support via FileProvider, modify the save flow to upload images before creating/updating items, and fix image URL resolution in the adapters so Glide can load server-relative URLs.

**Tech Stack:** Android (Java), Glide 4.15.1, FileProvider, existing AuthApiClient.uploadImage()

---

### Task 1: Add Camera Permission and FileProvider Configuration

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Add camera permission to AndroidManifest.xml**

In `app/src/main/AndroidManifest.xml`, add `<uses-permission android:name="android.permission.CAMERA" />` after the existing `READ_MEDIA_IMAGES` permission line (line 6):

```xml
    <uses-permission android:name="android.permission.CAMERA" />
```

- [ ] **Step 2: Add FileProvider declaration to AndroidManifest.xml**

In `app/src/main/AndroidManifest.xml`, add the following `<provider>` block inside `<application>`, after the existing `BillProvider` declaration (after line 78):

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="com.example.couplecredit.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 3: Create file_paths.xml**

Create `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path
        name="camera_images"
        path="Pictures/" />
</paths>
```

- [ ] **Step 4: Verify the directory exists**

Run: `ls app/src/main/res/xml/`
Expected: `file_paths.xml` is listed (along with any existing XML files)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "feat: add camera permission and FileProvider for inventory photos"
```

---

### Task 2: Update dialog layout to show image source chooser

**Files:**
- Modify: `app/src/main/res/layout/dialog_add_inventory.xml`

- [ ] **Step 1: Make the image area clickable as a whole**

Replace the `iv_add_image` ImageView block (lines 22-30 of `dialog_add_inventory.xml`) with a clickable container that wraps the image and an overlay hint:

```xml
    <!-- 图片区域 -->
    <FrameLayout
        android:id="@+id/fl_add_image"
        android:layout_width="80dp"
        android:layout_height="80dp"
        android:layout_gravity="center_horizontal"
        android:layout_marginBottom="8dp"
        android:foreground="?attr/selectableItemBackground"
        android:clickable="true"
        android:focusable="true">

        <ImageView
            android:id="@+id/iv_add_image"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:scaleType="centerCrop"
            android:background="@drawable/image_placeholder_background"
            android:src="@drawable/ic_add_image_placeholder" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="24dp"
            android:layout_gravity="bottom"
            android:background="#80000000"
            android:gravity="center"
            android:text="点击添加"
            android:textColor="@android:color/white"
            android:textSize="10sp" />
    </FrameLayout>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/layout/dialog_add_inventory.xml
git commit -m "feat: make image area clickable with overlay hint"
```

---

### Task 3: Modify InventoryFragment to support camera and upload pipeline

**Files:**
- Modify: `app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java`

This is the core task. The changes are:

1. Add a camera launcher (`ActivityResultLauncher<Intent>`) for `MediaStore.ACTION_IMAGE_CAPTURE`
2. Add a camera permission launcher
3. Add a temporary camera image URI field
4. Track whether the image was changed during editing (to avoid re-uploading unchanged images)
5. When the image area is clicked, show an AlertDialog with "拍照" / "从图库选择" options
6. In the save flow: if a new local image was selected, upload it first via `AuthApiClient.uploadImage()`, then save the inventory item with the server URL
7. Fix image URL resolution: prepend the base URL to server-relative URLs when loading with Glide

- [ ] **Step 1: Add new imports**

Add these imports after the existing imports in `InventoryFragment.java`:

```java
import android.os.Environment;
import android.provider.Settings;

import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.config.ApiConfigManager;

import java.io.File;
import java.io.InputStream;
```

- [ ] **Step 2: Add new instance fields**

After the existing `pendingImageUrl` field (line 93), add:

```java
    private Uri cameraImageUri;
    private String originalImageUrl;
    private boolean imageChanged = false;
    private AlertDialog currentDialog;
```

- [ ] **Step 3: Add camera image capture launcher**

After the `requestPermissionLauncher` (line 121), add:

```java
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
```

- [ ] **Step 4: Add helper methods for camera and image source chooser**

After `openImagePicker()` method (line 737), add:

```java
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
```

- [ ] **Step 5: Modify showInventoryDialog — update image click handling and save flow**

Replace the entire `showInventoryDialog` method (lines 466-589) with:

```java
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
        TextView btnSubmit = dialogView.findViewById(R.id.btn_add_inventory);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, addCategories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategoryDialog.setAdapter(categoryAdapter);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, units);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerUnit.setAdapter(unitAdapter);

        // Click on the image frame or the "select image" button both open the source picker
        View.OnClickListener imageClickListener = v -> {
            pendingImageView = ivAddImage;
            showImageSourcePicker();
        };
        flAddImage.setOnClickListener(imageClickListener);
        tvSelectImage.setOnClickListener(imageClickListener);

        tvAiGenerate.setOnClickListener(v -> {
            etAiPrompt.setVisibility(View.VISIBLE);
            Toast.makeText(getContext(), "AI 生图入口已保留，当前先记录提示词", Toast.LENGTH_SHORT).show();
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

            // Determine final imageUrl
            if (imageChanged && pendingImageUrl != null) {
                // New local image selected — upload first, then save
                btnSubmit.setEnabled(false);
                btnSubmit.setText("上传图片中...");
                uploadAndSave(dialog, name, category, quantity, unit, threshold, note, aiPrompt, existingItem);
            } else {
                // No image change — use existing URL (or null)
                String finalImageUrl = imageChanged ? null : (pendingImageUrl != null ? pendingImageUrl : null);
                saveInventoryItem(dialog, name, category, quantity, unit, threshold, finalImageUrl, note, aiPrompt, existingItem);
            }
        });

        showDialogWide(dialog);
    }
```

- [ ] **Step 6: Add upload-and-save and save helper methods**

Add these methods after `showInventoryDialog`:

```java
    private void uploadAndSave(AlertDialog dialog, String name, String category, double quantity,
                               String unit, double threshold, String note, String aiPrompt,
                               @Nullable InventoryItem existingItem) {
        try {
            Uri localUri = Uri.parse(pendingImageUrl);
            InputStream inputStream = requireContext().getContentResolver().openInputStream(localUri);
            String fileName = "inventory_" + System.currentTimeMillis() + ".jpg";

            AuthApiClient.uploadImage(requireContext(), inputStream, fileName, new AuthApiClient.ImageUploadCallback() {
                @Override
                public void onSuccess(String serverImageUrl) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() ->
                            saveInventoryItem(dialog, name, category, quantity, unit, threshold, serverImageUrl, note, aiPrompt, existingItem)
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

    private void saveInventoryItem(AlertDialog dialog, String name, String category, double quantity,
                                   String unit, double threshold, String imageUrl, String note,
                                   String aiPrompt, @Nullable InventoryItem existingItem) {
        InventoryUtils.InventoryMutationCallback callback = new InventoryUtils.InventoryMutationCallback() {
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

        if (existingItem == null) {
            InventoryUtils.createInventory(requireContext(), name, category, quantity, unit, threshold, imageUrl, note, aiPrompt, callback);
        } else {
            InventoryUtils.updateInventory(requireContext(), existingItem.id, name, category, quantity, unit, threshold, imageUrl, note, aiPrompt, callback);
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
```

- [ ] **Step 7: Update pickImageLauncher to mark imageChanged**

In the existing `pickImageLauncher` (lines 95-110), add `imageChanged = true;` after `pendingImageUrl = uri.toString();`:

The line `pendingImageUrl = uri.toString();` becomes:
```java
                        pendingImageUrl = uri.toString();
                        imageChanged = true;
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java
git commit -m "feat: add camera support and upload pipeline for inventory images"
```

---

### Task 4: Fix image URL resolution in adapters

The server returns relative URLs like `/uploads/xxx.jpg`. The adapters need to resolve these to full URLs before passing to Glide. Since adapters don't have easy access to Context for ApiConfigManager, the simplest fix is to resolve the URL when binding data in `InventoryFragment.fromApiItem()`.

**Files:**
- Modify: `app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java`

- [ ] **Step 1: Resolve imageUrl in fromApiItem**

In the `fromApiItem` method (around line 755), change the imageUrl assignment to resolve the full URL:

```java
        item.imageUrl = resolveImageUrl(itemData.imageUrl);
```

This replaces:
```java
        item.imageUrl = itemData.imageUrl;
```

Note: `resolveImageUrl` was added in Task 3 Step 4.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/couplecredit/fragment/InventoryFragment.java
git commit -m "fix: resolve relative image URLs for Glide loading"
```

---

### Task 5: Build and test

- [ ] **Step 1: Build the Android project**

Run: `cd /Users/chengzi/Code/GitHub/coupleCredit && ./gradlew assembleDebug 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Verify the server is running**

Run: `curl -s http://localhost:3000/api/auth/healthz || echo "Server not running locally"`
If the server is not running, start it: `cd server && npm start`

- [ ] **Step 3: Verify upload endpoint works**

Run: `curl -s -X POST http://localhost:3000/api/upload/image -F "image=@app/src/main/res/drawable/ic_inventory_placeholder.xml" 2>&1 | head -5`
Expected: JSON with `ok: true` and `data.imageUrl`

- [ ] **Step 4: Commit any fixups**

If any build fixes were needed, commit them:
```bash
git add -A
git commit -m "fix: build fixes for image upload feature"
```

---

### Task 6: Manual smoke test checklist

On a physical device or emulator:

- [ ] Open the app, navigate to the inventory tab
- [ ] Tap the "+" FAB to add a new item
- [ ] Verify the image area shows "点击添加" overlay
- [ ] Tap the image area → see "拍照" / "从图库选择" dialog
- [ ] Choose "从图库选择" → select an image → image appears in preview
- [ ] Fill in name, category, quantity → tap "添加物资" → see "上传图片中..." briefly → item created with image
- [ ] Verify the image appears in the inventory list
- [ ] Edit the item → verify the existing image loads in the dialog
- [ ] Change the image via camera → verify new image replaces old one
- [ ] Log in as the partner account → verify the image is visible
