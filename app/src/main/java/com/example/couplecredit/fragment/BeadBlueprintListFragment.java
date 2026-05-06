package com.example.couplecredit.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadBlueprintAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.view.BeadGridView;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BeadBlueprintListFragment extends Fragment {

    public static class BlueprintColorDraft {
        public final String colorCode;
        public final String quantityPerBuild;

        public BlueprintColorDraft(String colorCode, String quantityPerBuild) {
            this.colorCode = colorCode;
            this.quantityPerBuild = quantityPerBuild;
        }
    }

    private static class ParsedBlueprintColors {
        final List<BlueprintColorDraft> drafts = new ArrayList<>();
        int invalidLineNumber = 0;
    }

    private static final int REQUEST_IMAGE_PICK = 2001;
    private static final int MAX_IMAGE_DIMENSION = 2048;
    private static final int JPEG_QUALITY = 90;

    private BeadInventoryViewModel viewModel;
    private BeadBlueprintAdapter adapter;
    private TextView tvSummary;
    private TextView tvEmpty;

    private AlertDialog currentDialog;
    private ImageView ivPreview;
    private BeadGridView beadGridPreview;
    private View llLoading;
    private ProgressBar pbLoading;
    private TextView tvLoadingHint;
    private EditText etColors;
    private String pendingImageUrl;
    private View llGridSize;
    private View llAiActions;
    private int selectedCols = 36;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bead_blueprint_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        tvSummary = view.findViewById(R.id.tv_blueprint_list_summary);
        tvEmpty = view.findViewById(R.id.tv_blueprint_empty);

        RecyclerView recyclerView = view.findViewById(R.id.rv_bead_blueprints);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BeadBlueprintAdapter();
        adapter.setOnBlueprintClickListener(this::openDetail);
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_blueprint_list_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.fab_add_bead_blueprint).setOnClickListener(v -> showCreateDialog());

        viewModel.getDataVersion().observe(getViewLifecycleOwner(), version -> bindData());
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
        if (!viewModel.hasBlueprintData()) {
            viewModel.loadBlueprints();
        } else {
            bindData();
        }
    }

    public static List<AuthApiModels.BeadBlueprintColorRequest> buildColorRequests(List<BlueprintColorDraft> drafts) {
        List<AuthApiModels.BeadBlueprintColorRequest> requests = new ArrayList<>();
        if (drafts == null) {
            return requests;
        }
        for (BlueprintColorDraft draft : drafts) {
            if (draft == null || draft.colorCode == null || draft.quantityPerBuild == null) {
                continue;
            }
            String code = draft.colorCode.trim().toUpperCase(Locale.ROOT);
            code = com.example.couplecredit.utils.BeadUtils.normalizeColorCode(code);
            if (code == null || code.isEmpty()) {
                continue;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(draft.quantityPerBuild.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (quantity <= 0) {
                continue;
            }
            requests.add(new AuthApiModels.BeadBlueprintColorRequest(code, quantity));
        }
        return requests;
    }

    private void bindData() {
        List<BeadInventoryViewModel.BeadBlueprintItem> items = viewModel.getBlueprintList();
        adapter.submitList(items);
        tvSummary.setText(String.format(Locale.getDefault(), "%d 张图纸 · 制作不会自动扣库存", items.size()));
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showCreateDialog() {
        pendingImageUrl = null;
        selectedCols = 36;
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bead_blueprint_editor, null);
        currentDialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle).setView(content).create();

        EditText etName = content.findViewById(R.id.et_blueprint_name);
        etColors = content.findViewById(R.id.et_blueprint_colors);
        ivPreview = content.findViewById(R.id.iv_blueprint_preview);
        beadGridPreview = content.findViewById(R.id.bead_grid_preview);
        llLoading = content.findViewById(R.id.ll_loading);
        pbLoading = content.findViewById(R.id.pb_ai_loading);
        tvLoadingHint = content.findViewById(R.id.tv_loading_hint);
        llGridSize = content.findViewById(R.id.ll_grid_size);
        llAiActions = content.findViewById(R.id.ll_ai_actions);

        content.findViewById(R.id.btn_select_image).setOnClickListener(v -> openImagePicker());

        TextView btnGridSmall = content.findViewById(R.id.btn_grid_small);
        TextView btnGridMedium = content.findViewById(R.id.btn_grid_medium);
        TextView btnGridLarge = content.findViewById(R.id.btn_grid_large);
        TextView btnGridXLarge = content.findViewById(R.id.btn_grid_xlarge);
        View[] gridBtns = {btnGridSmall, btnGridMedium, btnGridLarge, btnGridXLarge};
        View.OnClickListener gridClickListener = v -> {
            if (v.getId() == R.id.btn_grid_small) selectedCols = 29;
            else if (v.getId() == R.id.btn_grid_large) selectedCols = 52;
            else if (v.getId() == R.id.btn_grid_xlarge) selectedCols = 104;
            else selectedCols = 36;
            for (View btn : gridBtns) {
                boolean active = btn == v;
                btn.setBackground(getResources().getDrawable(active ? R.drawable.button_background : R.drawable.btn_cancel_background));
                ((TextView) btn).setTextColor(active ? 0xFFFFFFFF : 0xFF6B7280);
            }
        };
        btnGridSmall.setOnClickListener(gridClickListener);
        btnGridMedium.setOnClickListener(gridClickListener);
        btnGridLarge.setOnClickListener(gridClickListener);
        btnGridXLarge.setOnClickListener(gridClickListener);

        content.findViewById(R.id.btn_ai_recognize).setOnClickListener(v -> {
            if (pendingImageUrl == null) {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show();
                return;
            }
            showLoading("AI识图中...");
            performAiRecognition(pendingImageUrl);
        });

        content.findViewById(R.id.btn_convert_to_bead).setOnClickListener(v -> {
            if (pendingImageUrl == null) {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show();
                return;
            }
            showLoading("转换拼豆图中...");
            BeadUtils.convertToBeadImage(requireContext(), pendingImageUrl, selectedCols, new BeadUtils.BeadConvertCallback() {
                @Override public void onSuccess(AuthApiModels.BeadConvertResponse response) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        hideLoading();
                        if (response != null && response.data != null) {
                            if (response.data.colorSummaryText != null && !response.data.colorSummaryText.isEmpty()) {
                                etColors.setText(response.data.colorSummaryText);
                            }
                            if (response.data.gridData != null && beadGridPreview != null) {
                                Map<String, String> colorMap = buildConvertColorMap(response.data.colors);
                                beadGridPreview.setVisibility(View.VISIBLE);
                                beadGridPreview.setGridData(response.data.gridData, colorMap);
                                if (ivPreview != null) {
                                    Glide.with(requireContext()).clear(ivPreview);
                                    ivPreview.setVisibility(View.GONE);
                                }
                            }
                            Toast.makeText(requireContext(), "转换完成，共 " + (response.data.colors != null ? response.data.colors.size() : 0) + " 种颜色", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                @Override public void onError(String error) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        hideLoading();
                        Toast.makeText(requireContext(), "转换失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });

        content.findViewById(R.id.btn_blueprint_dialog_cancel).setOnClickListener(v -> currentDialog.dismiss());
        content.findViewById(R.id.btn_blueprint_dialog_save).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(requireContext(), "请输入图纸名称", Toast.LENGTH_SHORT).show();
                return;
            }
            ParsedBlueprintColors parsedColors = parseColorDrafts(etColors.getText().toString());
            if (parsedColors.invalidLineNumber > 0) {
                Toast.makeText(requireContext(), "第 " + parsedColors.invalidLineNumber + " 行格式有误，请按 A01 12 填写", Toast.LENGTH_SHORT).show();
                return;
            }
            List<AuthApiModels.BeadBlueprintColorRequest> requests = buildColorRequests(parsedColors.drafts);
            if (requests.size() != parsedColors.drafts.size()) {
                Toast.makeText(requireContext(), "请检查色号和数量，数量必须大于 0", Toast.LENGTH_SHORT).show();
                return;
            }
            if (requests.isEmpty()) {
                Toast.makeText(requireContext(), "请至少填写一个有效颜色", Toast.LENGTH_SHORT).show();
                return;
            }
            BeadUtils.createBlueprint(requireContext(), name, pendingImageUrl, requests, new BeadUtils.BeadBlueprintCreateCallback() {
                @Override public void onSuccess(AuthApiModels.BeadBlueprintCreateResponse response) {
                    currentDialog.dismiss();
                    viewModel.loadBlueprints();
                }
                @Override public void onError(String error) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        DialogHelper.showWide(currentDialog, requireContext());
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

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImageUri = data.getData();
            if (selectedImageUri != null) {
                uploadImage(selectedImageUri);
            }
        }
    }

    private void showLoading(String text) {
        if (llLoading != null) llLoading.setVisibility(View.VISIBLE);
        if (tvLoadingHint != null) {
            tvLoadingHint.setText(text);
        }
    }

    private void hideLoading() {
        if (llLoading != null) llLoading.setVisibility(View.GONE);
    }

    private void uploadImage(Uri imageUri) {
        showLoading("上传图片中...");
        new Thread(() -> {
            try {
                InputStream compressed = compressImage(imageUri);
                if (compressed == null) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        hideLoading();
                        Toast.makeText(requireContext(), "图片压缩失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                String fileName = "blueprint_" + System.currentTimeMillis() + ".jpg";
                AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                    @Override public void onSuccess(String imageUrl) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            pendingImageUrl = imageUrl;
                            hideLoading();
                            if (ivPreview != null) {
                                ivPreview.setVisibility(View.VISIBLE);
                                String fullUrl = ApiConfigManager.resolveResourceUrl(requireContext(), imageUrl);
                                Glide.with(requireContext()).load(fullUrl).fitCenter().into(ivPreview);
                            }
                            if (beadGridPreview != null) beadGridPreview.setVisibility(View.GONE);
                            if (llAiActions != null) llAiActions.setVisibility(View.VISIBLE);
                            if (llGridSize != null) llGridSize.setVisibility(View.VISIBLE);
                        });
                    }
                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            hideLoading();
                            Toast.makeText(requireContext(), "图片上传失败: " + message, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } catch (Exception e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    hideLoading();
                    Toast.makeText(requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void performAiRecognition(String imageUrl) {
        BeadUtils.recognizeColors(requireContext(), imageUrl, new BeadUtils.BeadRecognizeColorsCallback() {
            @Override public void onSuccess(AuthApiModels.BeadRecognizeColorsResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    hideLoading();
                    if (response != null && response.data != null && response.data.recognized && response.data.colors != null && !response.data.colors.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (AuthApiModels.BeadRecognizedColorData color : response.data.colors) {
                            sb.append(color.colorCode).append(" ").append(color.quantityPerBuild).append("\n");
                        }
                        if (etColors != null) {
                            etColors.setText(sb.toString().trim());
                        }
                        Toast.makeText(requireContext(), "识别成功，共 " + response.data.colors.size() + " 种颜色", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), "未能识别颜色信息，请手动填写", Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    hideLoading();
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "AI识图失败: " + error + "，图片已上传，可手动填写色号", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private InputStream compressImage(Uri imageUri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            if (is != null) is.close();

            int sampleSize = 1;
            int halfW = bounds.outWidth / 2;
            int halfH = bounds.outHeight / 2;
            while ((halfW / sampleSize) >= MAX_IMAGE_DIMENSION && (halfH / sampleSize) >= MAX_IMAGE_DIMENSION) {
                sampleSize *= 2;
            }

            is = requireContext().getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            if (bitmap == null) return null;

            if (bitmap.getWidth() > MAX_IMAGE_DIMENSION || bitmap.getHeight() > MAX_IMAGE_DIMENSION) {
                float scale = Math.min((float) MAX_IMAGE_DIMENSION / bitmap.getWidth(), (float) MAX_IMAGE_DIMENSION / bitmap.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bitmap.recycle();
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private ParsedBlueprintColors parseColorDrafts(String raw) {
        ParsedBlueprintColors parsed = new ParsedBlueprintColors();
        if (raw == null || raw.trim().isEmpty()) {
            return parsed;
        }
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("[\\s,，:：]+", 2);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                parsed.invalidLineNumber = i + 1;
                return parsed;
            }
            parsed.drafts.add(new BlueprintColorDraft(parts[0], parts[1]));
        }
        return parsed;
    }

    private void openDetail(BeadInventoryViewModel.BeadBlueprintItem item) {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, BeadBlueprintDetailFragment.newInstance(item.blueprintId))
                .addToBackStack("bead_blueprint_detail")
                .commit();
    }
}
