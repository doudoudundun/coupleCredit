package com.example.couplecredit.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.view.BeadGridView;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeadConvertDialog {

    private static final int REQUEST_IMAGE_PICK = 2001;

    private final Fragment fragment;
    private final BeadInventoryViewModel viewModel;
    private AlertDialog dialog;
    private String pendingImageUrl;
    private int selectedCols = 36;
    private Integer selectedRows = null;
    private int matchLevel = 1;
    private int smoothLevel = 0;
    private AuthApiModels.BeadConvertData convertResult;

    private ImageView ivPreview;
    private BeadGridView beadGridPreview;
    private LinearLayout llParams;
    private LinearLayout llLoading;

    public BeadConvertDialog(Fragment fragment, BeadInventoryViewModel viewModel) {
        this.fragment = fragment;
        this.viewModel = viewModel;
    }

    public void show() {
        View content = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.dialog_bead_convert, null);
        dialog = new AlertDialog.Builder(fragment.requireContext(), R.style.CustomDialogStyle).setView(content).create();

        EditText etName = content.findViewById(R.id.et_blueprint_name);
        ivPreview = content.findViewById(R.id.iv_preview);
        beadGridPreview = content.findViewById(R.id.bead_grid_preview);
        llParams = content.findViewById(R.id.ll_params);
        llLoading = content.findViewById(R.id.ll_loading);

        SeekBar seekCols = content.findViewById(R.id.seek_cols);
        TextView tvColsValue = content.findViewById(R.id.tv_cols_value);
        CheckBox cbRowsAuto = content.findViewById(R.id.cb_rows_auto);
        EditText etRows = content.findViewById(R.id.et_rows);

        seekCols.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedCols = progress + 8;
                tvColsValue.setText(selectedCols + " 列");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        tvColsValue.setText(selectedCols + " 列");
        seekCols.setProgress(selectedCols - 8);

        cbRowsAuto.setOnCheckedChangeListener((btn, checked) -> {
            etRows.setEnabled(!checked);
            selectedRows = checked ? null : parseRows(etRows);
        });
        etRows.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !cbRowsAuto.isChecked()) selectedRows = parseRows(etRows);
        });

        View[] matchBtns = {content.findViewById(R.id.btn_match_low), content.findViewById(R.id.btn_match_medium), content.findViewById(R.id.btn_match_high)};
        setupTriState(fragment.requireContext(), matchBtns, matchLevel, idx -> matchLevel = idx);
        View[] smoothBtns = {content.findViewById(R.id.btn_smooth_low), content.findViewById(R.id.btn_smooth_medium), content.findViewById(R.id.btn_smooth_high)};
        setupTriState(fragment.requireContext(), smoothBtns, smoothLevel, idx -> smoothLevel = idx);

        content.findViewById(R.id.btn_select_image).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            fragment.startActivityForResult(intent, REQUEST_IMAGE_PICK);
        });

        content.findViewById(R.id.btn_convert).setOnClickListener(v -> doConvert());
        content.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (convertResult == null) {
                Toast.makeText(fragment.requireContext(), "请先转换图片", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(fragment.requireContext(), "请输入图纸名称", Toast.LENGTH_SHORT).show();
                return;
            }
            saveBlueprint(name);
        });

        if (pendingImageUrl != null) {
            llParams.setVisibility(View.VISIBLE);
            String fullUrl = ApiConfigManager.resolveResourceUrl(fragment.requireContext(), pendingImageUrl);
            Glide.with(fragment.requireContext()).load(fullUrl).fitCenter().into(ivPreview);
            ivPreview.setVisibility(View.VISIBLE);
        }

        DialogHelper.showWide(dialog, fragment.requireContext());
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) uploadImage(imageUri);
            return true;
        }
        return false;
    }

    private Integer parseRows(EditText etRows) {
        try { int v = Integer.parseInt(etRows.getText().toString().trim()); return v > 0 ? v : null; }
        catch (NumberFormatException e) { return null; }
    }

    private void setupTriState(Context ctx, View[] btns, int current, java.util.function.IntConsumer setter) {
        for (int i = 0; i < btns.length; i++) {
            final int idx = i;
            btns[i].setOnClickListener(v -> {
                setter.accept(idx);
                for (int j = 0; j < btns.length; j++) {
                    TextView tv = (TextView) btns[j];
                    if (j == idx) {
                        tv.setBackground(ctx.getDrawable(R.drawable.button_background));
                        tv.setTextColor(ctx.getResources().getColor(android.R.color.white));
                    } else {
                        tv.setBackground(ctx.getDrawable(R.drawable.category_tag_background));
                        tv.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
                    }
                }
            });
        }
    }

    private void doConvert() {
        if (pendingImageUrl == null) { Toast.makeText(fragment.requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show(); return; }
        llLoading.setVisibility(View.VISIBLE);
        beadGridPreview.setVisibility(View.GONE);
        int[] thresholds = {3500, 2500, 1500};
        int[] smoothExtras = {0, 1, 2};
        BeadUtils.convertToBeadImage(fragment.requireContext(), pendingImageUrl, selectedCols, selectedRows, thresholds[matchLevel], smoothExtras[smoothLevel], new BeadUtils.BeadConvertCallback() {
            @Override public void onSuccess(AuthApiModels.BeadConvertResponse response) {
                if (!fragment.isAdded()) return;
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    llLoading.setVisibility(View.GONE);
                    if (response != null && response.data != null) {
                        convertResult = response.data;
                        if (response.data.gridData != null && beadGridPreview != null) {
                            Map<String, String> colorMap = buildConvertColorMap(response.data.colors);
                            beadGridPreview.setVisibility(View.VISIBLE);
                            beadGridPreview.setGridData(response.data.gridData, colorMap);
                        }
                        Toast.makeText(fragment.requireContext(), "转换完成，共 " + (response.data.colors != null ? response.data.colors.size() : 0) + " 种颜色", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onError(String error) {
                if (!fragment.isAdded()) return;
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    llLoading.setVisibility(View.GONE);
                    Toast.makeText(fragment.requireContext(), "转换失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void saveBlueprint(String name) {
        if (convertResult == null || convertResult.colors == null) return;
        List<AuthApiModels.BeadBlueprintColorRequest> colorRequests = new ArrayList<>();
        for (AuthApiModels.BeadConvertColorData c : convertResult.colors) {
            if (c.colorCode != null && c.quantity > 0) {
                colorRequests.add(new AuthApiModels.BeadBlueprintColorRequest(c.colorCode, c.quantity));
            }
        }
        BeadUtils.createBlueprint(fragment.requireContext(), name, pendingImageUrl, colorRequests, new BeadUtils.BeadBlueprintCreateCallback() {
            @Override public void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response) {
                if (!fragment.isAdded()) return;
                Toast.makeText(fragment.requireContext(), "图纸已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (viewModel != null) viewModel.loadBlueprints();
            }
            @Override public void onError(String error) {
                if (!fragment.isAdded()) return;
                Toast.makeText(fragment.requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void uploadImage(Uri imageUri) {
        if (llLoading != null) llLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                InputStream compressed = compressImage(imageUri);
                if (compressed == null) {
                    if (!fragment.isAdded()) return;
                    fragment.requireActivity().runOnUiThread(() -> {
                        if (!fragment.isAdded()) return;
                        if (llLoading != null) llLoading.setVisibility(View.GONE);
                        Toast.makeText(fragment.requireContext(), "图片压缩失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                String fileName = "blueprint_" + System.currentTimeMillis() + ".jpg";
                AuthApiClient.uploadImage(fragment.requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                    @Override public void onSuccess(String imageUrl) {
                        if (!fragment.isAdded()) return;
                        fragment.requireActivity().runOnUiThread(() -> {
                            if (!fragment.isAdded()) return;
                            pendingImageUrl = imageUrl;
                            if (llLoading != null) llLoading.setVisibility(View.GONE);
                            if (ivPreview != null) {
                                ivPreview.setVisibility(View.VISIBLE);
                                String fullUrl = ApiConfigManager.resolveResourceUrl(fragment.requireContext(), imageUrl);
                                Glide.with(fragment.requireContext()).load(fullUrl).fitCenter().into(ivPreview);
                            }
                            if (llParams != null) llParams.setVisibility(View.VISIBLE);
                            if (beadGridPreview != null) beadGridPreview.setVisibility(View.GONE);
                        });
                    }
                    @Override public void onError(String message) {
                        if (!fragment.isAdded()) return;
                        fragment.requireActivity().runOnUiThread(() -> {
                            if (!fragment.isAdded()) return;
                            if (llLoading != null) llLoading.setVisibility(View.GONE);
                            Toast.makeText(fragment.requireContext(), "图片上传失败: " + message, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } catch (Exception e) {
                if (!fragment.isAdded()) return;
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    if (llLoading != null) llLoading.setVisibility(View.GONE);
                    Toast.makeText(fragment.requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private InputStream compressImage(Uri imageUri) {
        try {
            InputStream is = fragment.requireContext().getContentResolver().openInputStream(imageUri);
            if (is == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            is.close();
            int scale = 1;
            int maxDim = 2048;
            if (opts.outWidth > maxDim || opts.outHeight > maxDim) {
                scale = Math.max(opts.outWidth, opts.outHeight) / maxDim;
                if (scale < 1) scale = 1;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = scale;
            is = fragment.requireContext().getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, decodeOpts);
            if (is != null) is.close();
            if (bitmap == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            bitmap.recycle();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) { return null; }
    }

    private Map<String, String> buildConvertColorMap(List<AuthApiModels.BeadConvertColorData> colors) {
        Map<String, String> map = new HashMap<>();
        if (colors == null) return map;
        for (AuthApiModels.BeadConvertColorData c : colors) {
            if (c.colorCode != null && !map.containsKey(c.colorCode)) {
                map.put(c.colorCode, c.hexColor != null ? c.hexColor : "#DDDDDD");
            }
        }
        return map;
    }
}
