package com.example.couplecredit.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.ImageCompressor;
import com.example.couplecredit.view.BeadGridView;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeadRecognizeDialog {

    private static final int REQUEST_IMAGE_PICK = 2002;

    private final Fragment fragment;
    private final BeadInventoryViewModel viewModel;
    private AlertDialog dialog;
    private String pendingImageUrl;
    private List<AuthApiModels.BeadRecognizedColorData> recognizedColors = new ArrayList<>();

    private ImageView ivPreview;
    private BeadGridView beadGridPreview;
    private LinearLayout llLoading;
    private RecyclerView rvColors;
    private View btnRecognize;

    public BeadRecognizeDialog(Fragment fragment, BeadInventoryViewModel viewModel) {
        this.fragment = fragment;
        this.viewModel = viewModel;
    }

    public void show() {
        View content = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.dialog_bead_recognize, null);
        dialog = new AlertDialog.Builder(fragment.requireContext(), R.style.CustomDialogStyle).setView(content).create();

        ivPreview = content.findViewById(R.id.iv_preview);
        beadGridPreview = content.findViewById(R.id.bead_grid_preview);
        llLoading = content.findViewById(R.id.ll_loading);
        rvColors = content.findViewById(R.id.rv_recognized_colors);
        btnRecognize = content.findViewById(R.id.btn_recognize);

        rvColors.setLayoutManager(new LinearLayoutManager(fragment.requireContext()));
        RecognizedColorAdapter colorAdapter = new RecognizedColorAdapter();
        rvColors.setAdapter(colorAdapter);

        content.findViewById(R.id.btn_select_image).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            fragment.startActivityForResult(intent, REQUEST_IMAGE_PICK);
        });

        btnRecognize.setOnClickListener(v -> doRecognize(colorAdapter));
        content.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (recognizedColors.isEmpty()) {
                Toast.makeText(fragment.requireContext(), "请先识别颜色", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = ((android.widget.EditText) content.findViewById(R.id.et_blueprint_name)).getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(fragment.requireContext(), "请输入图纸名称", Toast.LENGTH_SHORT).show();
                return;
            }
            saveBlueprint(name);
        });

        if (pendingImageUrl != null) {
            String fullUrl = ApiConfigManager.resolveResourceUrl(fragment.requireContext(), pendingImageUrl);
            Glide.with(fragment.requireContext()).load(fullUrl).fitCenter().into(ivPreview);
            ivPreview.setVisibility(View.VISIBLE);
            btnRecognize.setVisibility(View.VISIBLE);
        }
        if (!recognizedColors.isEmpty()) {
            colorAdapter.setItems(recognizedColors);
            rvColors.setVisibility(View.VISIBLE);
            showGridPreview();
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

    private void doRecognize(RecognizedColorAdapter colorAdapter) {
        if (pendingImageUrl == null) { Toast.makeText(fragment.requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show(); return; }
        llLoading.setVisibility(View.VISIBLE);
        BeadUtils.recognizeColors(fragment.requireContext(), pendingImageUrl, new BeadUtils.BeadRecognizeColorsCallback() {
            @Override public void onSuccess(AuthApiModels.BeadRecognizeColorsResponse response) {
                if (!fragment.isAdded()) return;
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    llLoading.setVisibility(View.GONE);
                    if (response != null && response.data != null && response.data.recognized && response.data.colors != null && !response.data.colors.isEmpty()) {
                        recognizedColors = new ArrayList<>(response.data.colors);
                        colorAdapter.setItems(recognizedColors);
                        rvColors.setVisibility(View.VISIBLE);
                        showGridPreview();
                        Toast.makeText(fragment.requireContext(), "识别成功，共 " + recognizedColors.size() + " 种颜色", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(fragment.requireContext(), "未识别到拼豆颜色", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onError(String error) {
                if (!fragment.isAdded()) return;
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    llLoading.setVisibility(View.GONE);
                    Toast.makeText(fragment.requireContext(), "识别失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showGridPreview() {
        if (beadGridPreview == null || recognizedColors.isEmpty()) return;
        int totalBeads = 0;
        Map<String, String> colorMap = new HashMap<>();
        List<String> codeList = new ArrayList<>();
        List<Integer> qtyList = new ArrayList<>();
        for (AuthApiModels.BeadRecognizedColorData c : recognizedColors) {
            int qty = c.quantityPerBuild;
            if (qty > 0 && c.colorCode != null) {
                totalBeads += qty;
                codeList.add(c.colorCode);
                qtyList.add(qty);
                String hex = "#DDDDDD";
                colorMap.put(c.colorCode, hex);
            }
        }
        if (totalBeads == 0) return;
        int cols = (int) Math.ceil(Math.sqrt(totalBeads));
        int rows = (int) Math.ceil((double) totalBeads / cols);
        List<List<String>> gridData = new ArrayList<>();
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                if (idx < totalBeads) {
                    int cumQty = 0;
                    String code = "???";
                    for (int i = 0; i < codeList.size(); i++) {
                        cumQty += qtyList.get(i);
                        if (idx < cumQty) { code = codeList.get(i); break; }
                    }
                    row.add(code);
                } else {
                    row.add("???");
                }
                idx++;
            }
            gridData.add(row);
        }
        beadGridPreview.setVisibility(View.VISIBLE);
        beadGridPreview.setGridData(gridData, colorMap);
    }

    private void saveBlueprint(String name) {
        List<AuthApiModels.BeadBlueprintColorRequest> colorRequests = new ArrayList<>();
        for (AuthApiModels.BeadRecognizedColorData c : recognizedColors) {
            if (c.colorCode != null && c.quantityPerBuild > 0) {
                colorRequests.add(new AuthApiModels.BeadBlueprintColorRequest(c.colorCode, c.quantityPerBuild));
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
                byte[] compressedBytes = ImageCompressor.compress(fragment.requireContext(), imageUri, 2048, 90);
                InputStream compressed = compressedBytes != null ? new ByteArrayInputStream(compressedBytes) : null;
                if (compressed == null) {
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
                            if (btnRecognize != null) btnRecognize.setVisibility(View.VISIBLE);
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
                fragment.requireActivity().runOnUiThread(() -> {
                    if (!fragment.isAdded()) return;
                    if (llLoading != null) llLoading.setVisibility(View.GONE);
                    Toast.makeText(fragment.requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    static class RecognizedColorAdapter extends RecyclerView.Adapter<RecognizedColorAdapter.ViewHolder> {
        private List<AuthApiModels.BeadRecognizedColorData> items = new ArrayList<>();

        void setItems(List<AuthApiModels.BeadRecognizedColorData> items) {
            this.items = items != null ? items : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bead_blueprint_color, parent, false);
            return new ViewHolder(view);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AuthApiModels.BeadRecognizedColorData item = items.get(position);
            holder.tvCode.setText(item.colorCode != null ? item.colorCode : "");
            holder.tvQty.setText("每次 " + item.quantityPerBuild + " 颗");
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvCode;
            TextView tvQty;
            ViewHolder(View v) {
                super(v);
                tvCode = v.findViewById(R.id.tv_blueprint_color_code);
                tvQty = v.findViewById(R.id.tv_blueprint_color_quantity);
            }
        }
    }
}
