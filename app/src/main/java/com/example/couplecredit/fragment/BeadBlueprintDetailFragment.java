package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.example.couplecredit.adapter.BeadBlueprintColorAdapter;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BeadBlueprintDetailFragment extends Fragment {

    private static final String ARG_BLUEPRINT_ID = "blueprint_id";

    public static class BlueprintColorDisplayItem {
        public final String colorCode;
        public final String hexColor;
        public final int quantityPerBuild;
        public final int totalConsumed;
        public final boolean isTransparent;

        public BlueprintColorDisplayItem(String colorCode, String hexColor, int quantityPerBuild, int totalConsumed, boolean isTransparent) {
            this.colorCode = colorCode;
            this.hexColor = hexColor;
            this.quantityPerBuild = quantityPerBuild;
            this.totalConsumed = totalConsumed;
            this.isTransparent = isTransparent;
        }
    }

    private int blueprintId;
    private BeadInventoryViewModel viewModel;
    private BeadBlueprintColorAdapter adapter;
    private TextView tvTitle;
    private TextView tvTotal;
    private TextView tvBuilds;
    private ImageView ivImage;
    private BeadInventoryViewModel.BeadBlueprintItem currentItem;

    public static BeadBlueprintDetailFragment newInstance(int blueprintId) {
        BeadBlueprintDetailFragment fragment = new BeadBlueprintDetailFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_BLUEPRINT_ID, blueprintId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bead_blueprint_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        blueprintId = getArguments() == null ? 0 : getArguments().getInt(ARG_BLUEPRINT_ID, 0);
        viewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        tvTitle = view.findViewById(R.id.tv_blueprint_detail_title);
        tvTotal = view.findViewById(R.id.tv_blueprint_detail_total);
        tvBuilds = view.findViewById(R.id.tv_blueprint_detail_builds);
        ivImage = view.findViewById(R.id.iv_blueprint_detail_image);

        RecyclerView recyclerView = view.findViewById(R.id.rv_blueprint_colors);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BeadBlueprintColorAdapter();
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_blueprint_detail_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.btn_build_blueprint).setOnClickListener(v -> buildOnce());
        view.findViewById(R.id.btn_blueprint_edit).setOnClickListener(v -> showEditDialog());
        view.findViewById(R.id.btn_blueprint_delete).setOnClickListener(v -> showDeleteConfirmDialog());

        if (!bindFromViewModel()) {
            loadDetail();
        }
    }

    public static int calculateTotalBeadsPerBuild(List<BlueprintColorDisplayItem> colors) {
        int total = 0;
        if (colors == null) {
            return total;
        }
        for (BlueprintColorDisplayItem color : colors) {
            if (color != null) {
                total += color.quantityPerBuild;
            }
        }
        return total;
    }

    private boolean bindFromViewModel() {
        for (BeadInventoryViewModel.BeadBlueprintItem item : viewModel.getBlueprintList()) {
            if (item.blueprintId == blueprintId) {
                if (!item.colors.isEmpty()) {
                    bindItem(item);
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private void loadDetail() {
        if (blueprintId <= 0) {
            return;
        }
        BeadUtils.getBlueprintDetail(requireContext(), blueprintId, new BeadUtils.BeadBlueprintDetailLoadCallback() {
            @Override public void onSuccess(AuthApiModels.BeadBlueprintResponse response) {
                if (response != null && response.data != null) {
                    bindItem(fromApi(response.data));
                }
            }
            @Override public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindItem(BeadInventoryViewModel.BeadBlueprintItem item) {
        currentItem = item;
        tvTitle.setText(item.name == null || item.name.trim().isEmpty() ? "未命名图纸" : item.name);
        List<BlueprintColorDisplayItem> colors = toDisplayColors(item);
        int total = item.totalBeadsPerBuild != null ? item.totalBeadsPerBuild : calculateTotalBeadsPerBuild(colors);
        tvTotal.setText(String.format(Locale.getDefault(), "每次 %d 颗", total));
        tvBuilds.setText(String.format(Locale.getDefault(), "已制作 %d 次", item.buildCount));
        if (item.imageUrl != null && !item.imageUrl.isEmpty() && ivImage != null) {
            ivImage.setVisibility(View.VISIBLE);
            Glide.with(requireContext()).load(ApiConfigManager.resolveResourceUrl(requireContext(), item.imageUrl)).centerCrop().into(ivImage);
        } else if (ivImage != null) {
            ivImage.setVisibility(View.GONE);
        }
        adapter.submitList(colors);
    }

    private void buildOnce() {
        if (currentItem == null) {
            return;
        }
        BeadUtils.buildBlueprint(requireContext(), currentItem.blueprintId, 1, new BeadUtils.BuildBeadBlueprintCallback() {
            @Override public void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response) {
                viewModel.loadBlueprints();
                loadDetail();
            }
            @Override public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showEditDialog() {
        if (currentItem == null || !isAdded()) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bead_blueprint_editor, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView).create();

        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        if (tvDialogTitle != null) tvDialogTitle.setText("编辑图纸");

        EditText etName = dialogView.findViewById(R.id.et_blueprint_name);
        EditText etColors = dialogView.findViewById(R.id.et_blueprint_colors);

        etName.setText(currentItem.name);
        StringBuilder colorText = new StringBuilder();
        for (BeadInventoryViewModel.BeadBlueprintColor color : currentItem.colors) {
            colorText.append(color.colorCode).append(" ").append(color.quantityPerBuild).append("\n");
        }
        if (colorText.length() > 0) colorText.setLength(colorText.length() - 1);
        etColors.setText(colorText.toString());

        dialogView.findViewById(R.id.btn_select_image).setVisibility(View.GONE);
        dialogView.findViewById(R.id.ll_loading).setVisibility(View.GONE);
        dialogView.findViewById(R.id.iv_blueprint_preview).setVisibility(View.GONE);

        dialogView.findViewById(R.id.btn_blueprint_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_blueprint_dialog_save).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "请输入图纸名称", Toast.LENGTH_SHORT).show();
                return;
            }
            String rawColors = etColors.getText().toString().trim();
            List<AuthApiModels.BeadBlueprintColorRequest> colorRequests = parseColorRequests(rawColors);
            if (colorRequests.isEmpty()) {
                Toast.makeText(requireContext(), "请输入至少一种颜色", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            BeadUtils.updateBlueprint(requireContext(), blueprintId, name, currentItem.imageUrl, colorRequests,
                    new BeadUtils.BeadMutationCallback() {
                        @Override public void onSuccess() {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), "图纸已更新", Toast.LENGTH_SHORT).show();
                            viewModel.loadBlueprints();
                            loadDetail();
                        }
                        @Override public void onError(String error) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private List<AuthApiModels.BeadBlueprintColorRequest> parseColorRequests(String raw) {
        List<AuthApiModels.BeadBlueprintColorRequest> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        String[] lines = raw.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("[\\s,，:：]+", 2);
            if (parts.length >= 2) {
                try {
                    int qty = Integer.parseInt(parts[1].trim());
                    result.add(new AuthApiModels.BeadBlueprintColorRequest(parts[0].trim().toUpperCase(), qty));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private void showDeleteConfirmDialog() {
        if (currentItem == null || !isAdded()) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView).create();

        TextView tvMessage = dialogView.findViewById(R.id.tv_confirm_message);
        tvMessage.setText("确定删除\"" + currentItem.name + "\"吗？此操作不可撤销。");

        dialogView.findViewById(R.id.btn_confirm_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm_delete).setOnClickListener(v -> {
            dialog.dismiss();
            BeadUtils.deleteBlueprint(requireContext(), blueprintId, new BeadUtils.BeadMutationCallback() {
                @Override public void onSuccess() {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "图纸已删除", Toast.LENGTH_SHORT).show();
                    viewModel.loadBlueprints();
                    requireActivity().getSupportFragmentManager().popBackStack();
                }
                @Override public void onError(String error) {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                }
            });
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private List<BlueprintColorDisplayItem> toDisplayColors(BeadInventoryViewModel.BeadBlueprintItem item) {
        List<BlueprintColorDisplayItem> colors = new ArrayList<>();
        for (BeadInventoryViewModel.BeadBlueprintColor color : item.colors) {
            colors.add(new BlueprintColorDisplayItem(
                    color.colorCode,
                    color.hexColor,
                    color.quantityPerBuild,
                    color.totalConsumed == null ? item.buildCount * color.quantityPerBuild : color.totalConsumed,
                    color.isTransparent
            ));
        }
        return colors;
    }

    private BeadInventoryViewModel.BeadBlueprintItem fromApi(AuthApiModels.BeadBlueprintItemData data) {
        BeadInventoryViewModel.BeadBlueprintItem item = new BeadInventoryViewModel.BeadBlueprintItem();
        item.blueprintId = data.blueprintId;
        item.userId = data.userId;
        item.relationshipId = data.relationshipId;
        item.name = data.name;
        item.imageUrl = data.imageUrl;
        item.buildCount = data.buildCount;
        item.colorCount = data.colorCount;
        item.totalBeadsPerBuild = data.totalBeadsPerBuild;
        item.totalConsumed = data.totalConsumed;
        item.createdAt = data.createdAt;
        item.updatedAt = data.updatedAt;
        if (data.colors != null) {
            for (AuthApiModels.BeadBlueprintColorData colorData : data.colors) {
                BeadInventoryViewModel.BeadBlueprintColor color = new BeadInventoryViewModel.BeadBlueprintColor();
                color.colorCode = colorData.colorCode;
                color.hexColor = colorData.hexColor;
                color.colorGroup = colorData.colorGroup;
                color.isTransparent = colorData.isTransparent;
                color.quantityPerBuild = colorData.quantityPerBuild;
                color.totalConsumed = colorData.totalConsumed;
                item.colors.add(color);
            }
        }
        return item;
    }
}
