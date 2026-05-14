package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadBlueprintColorAdapter;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.view.BeadGridView;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BeadBlueprintDetailFragment extends Fragment {

    private static final String ARG_BLUEPRINT_ID = "blueprint_id";
    private static final int DEFAULT_GRID_COLS = 48;

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
    private BeadGridView beadGridView;
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
        beadGridView = view.findViewById(R.id.bead_grid_view);

        RecyclerView recyclerView = view.findViewById(R.id.rv_blueprint_colors);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BeadBlueprintColorAdapter();
        adapter.setOnColorClickListener(this::onColorClick);
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_blueprint_detail_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.btn_build_blueprint).setOnClickListener(v -> buildOnce());
        view.findViewById(R.id.btn_blueprint_edit).setOnClickListener(v -> showEditDialog());
        view.findViewById(R.id.btn_blueprint_delete).setOnClickListener(v -> showDeleteConfirmDialog());

        if (!bindFromViewModel()) {
            loadDetail();
        }
    }

    private void onColorClick(String colorCode) {
        if (beadGridView != null) {
            beadGridView.setHighlightColor(colorCode);
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
                if (!isAdded()) return;
                if (response != null && response.data != null) {
                    bindItem(fromApi(response.data));
                }
            }
            @Override public void onError(String error) {
                if (!isAdded()) return;
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

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            BeadInventoryViewModel.GridCacheEntry cached = viewModel.getGridCache(blueprintId);
            if (cached != null && cached.convertColors != null && beadGridView != null) {
                beadGridView.setVisibility(View.VISIBLE);
                beadGridView.setGridData(cached.gridData, cached.colorMap);
                adapter.submitList(cached.convertColors);
                tvTotal.setText(String.format(Locale.getDefault(), "每次 %d 颗", calculateTotalBeadsPerBuild(cached.convertColors)));
            } else {
                adapter.submitList(colors);
                loadBeadGridFromImage(item.imageUrl);
            }
        } else if (!colors.isEmpty()) {
            adapter.submitList(colors);
            loadBeadGridFromColors(colors);
        } else {
            adapter.submitList(colors);
            if (beadGridView != null) beadGridView.setVisibility(View.GONE);
        }
    }

    private void loadBeadGridFromImage(String imageUrl) {
        if (beadGridView == null) return;
        beadGridView.setVisibility(View.VISIBLE);
        BeadUtils.convertToBeadImage(requireContext(), imageUrl, DEFAULT_GRID_COLS, new BeadUtils.BeadConvertCallback() {
            @Override public void onSuccess(AuthApiModels.BeadConvertResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || beadGridView == null) return;
                    if (response != null && response.data != null && response.data.gridData != null && response.data.colors != null) {
                        List<BlueprintColorDisplayItem> convertColors = buildDisplayItemsFromConvertColors(response.data.colors);
                        Map<String, String> colorMap = new HashMap<>();
                        for (AuthApiModels.BeadConvertColorData c : response.data.colors) {
                            if (c.colorCode != null && c.hexColor != null) {
                                colorMap.put(c.colorCode, c.hexColor);
                            }
                        }
                        viewModel.putGridCache(blueprintId, response.data.gridData, colorMap, convertColors);
                        beadGridView.setGridData(response.data.gridData, colorMap);
                        adapter.submitList(convertColors);
                        tvTotal.setText(String.format(Locale.getDefault(), "每次 %d 颗", calculateTotalBeadsPerBuild(convertColors)));
                    } else {
                        fallbackToSavedColors();
                    }
                });
            }
            @Override public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || beadGridView == null) return;
                    fallbackToSavedColors();
                });
            }
        });
    }

    private void fallbackToSavedColors() {
        if (currentItem == null) return;
        List<BlueprintColorDisplayItem> savedColors = toDisplayColors(currentItem);
        adapter.submitList(savedColors);
        loadBeadGridFromColors(savedColors);
        int total = currentItem.totalBeadsPerBuild != null ? currentItem.totalBeadsPerBuild : calculateTotalBeadsPerBuild(savedColors);
        tvTotal.setText(String.format(Locale.getDefault(), "每次 %d 颗", total));
    }

    private List<BlueprintColorDisplayItem> buildDisplayItemsFromConvertColors(List<AuthApiModels.BeadConvertColorData> convertColors) {
        List<BlueprintColorDisplayItem> result = new ArrayList<>();
        if (convertColors == null) return result;
        int buildCount = currentItem != null ? currentItem.buildCount : 0;
        for (AuthApiModels.BeadConvertColorData c : convertColors) {
            if (c.colorCode == null) continue;
            result.add(new BlueprintColorDisplayItem(
                    c.colorCode,
                    c.hexColor != null ? c.hexColor : "#DDDDDD",
                    c.quantity,
                    buildCount * c.quantity,
                    false
            ));
        }
        result.sort((a, b) -> Integer.compare(b.quantityPerBuild, a.quantityPerBuild));
        return result;
    }

    private void loadBeadGridFromColors(List<BlueprintColorDisplayItem> colors) {
        if (beadGridView == null || colors.isEmpty()) {
            if (beadGridView != null) beadGridView.setVisibility(View.GONE);
            return;
        }
        beadGridView.setVisibility(View.VISIBLE);

        int totalBeads = 0;
        for (BlueprintColorDisplayItem c : colors) {
            totalBeads += c.quantityPerBuild;
        }
        int cols = (int) Math.ceil(Math.sqrt(totalBeads));
        if (cols < 1) cols = 1;
        int rows = (int) Math.ceil((double) totalBeads / cols);

        List<List<String>> gridData = new ArrayList<>();
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                if (idx < totalBeads) {
                    int cumQty = 0;
                    String code = "???";
                    for (BlueprintColorDisplayItem color : colors) {
                        cumQty += color.quantityPerBuild;
                        if (idx < cumQty) {
                            code = color.colorCode;
                            break;
                        }
                    }
                    row.add(code);
                } else {
                    row.add("???");
                }
                idx++;
            }
            gridData.add(row);
        }

        Map<String, String> colorCodeToHex = buildColorCodeToHex(colors);
        beadGridView.setGridData(gridData, colorCodeToHex);
    }

    private Map<String, String> buildColorCodeToHex(List<BlueprintColorDisplayItem> colors) {
        Map<String, String> map = new HashMap<>();
        if (colors != null) {
            for (BlueprintColorDisplayItem c : colors) {
                map.put(c.colorCode, c.hexColor);
            }
        }
        return map;
    }

    private void buildOnce() {
        if (currentItem == null) return;
        List<BlueprintColorDisplayItem> colors = toDisplayColors(currentItem);
        if (colors.isEmpty()) {
            Toast.makeText(requireContext(), "图纸没有颜色数据", Toast.LENGTH_SHORT).show();
            return;
        }
        showBuildConfirmDialog(colors);
    }

    private void showBuildConfirmDialog(List<BlueprintColorDisplayItem> colors) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_build_confirm, null);
        AlertDialog confirmDialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle).setView(dialogView).create();

        RecyclerView rvConsumeColors = dialogView.findViewById(R.id.rv_consume_colors);
        rvConsumeColors.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvConsumeColors.setAdapter(new ConsumeColorAdapter(colors, viewModel));

        boolean hasInsufficient = false;
        for (BlueprintColorDisplayItem c : colors) {
            Integer stock = viewModel.getInventoryQuantity(c.colorCode);
            if (stock != null && stock < c.quantityPerBuild) {
                hasInsufficient = true;
                break;
            }
        }
        dialogView.findViewById(R.id.tv_warning).setVisibility(hasInsufficient ? View.VISIBLE : View.GONE);

        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> confirmDialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            confirmDialog.dismiss();
            performBuild();
        });

        DialogHelper.showWide(confirmDialog, requireContext());
    }

    private void performBuild() {
        BeadUtils.buildBlueprint(requireContext(), currentItem.blueprintId, 1, new BeadUtils.BuildBeadBlueprintCallback() {
            @Override public void onSuccess(AuthApiModels.BuildBeadBlueprintResponse response) {
                if (!isAdded()) return;
                if (response != null && response.data != null && response.data.consumedColors != null && !response.data.consumedColors.isEmpty()) {
                    showDeductConfirmDialog(response.data.consumedColors);
                } else {
                    viewModel.loadBlueprints();
                    loadDetail();
                    Toast.makeText(requireContext(), "制作记录成功", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onError(String error) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeductConfirmDialog(List<AuthApiModels.ConsumedColorData> consumedColors) {
        new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setTitle("扣减库存")
                .setMessage("是否同步扣减库存中的拼豆数量？")
                .setPositiveButton("扣减", (d, w) -> batchDeduct(consumedColors))
                .setNegativeButton("跳过", (d, w) -> {
                    viewModel.loadBlueprints();
                    loadDetail();
                    Toast.makeText(requireContext(), "制作记录成功（未扣库存）", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void batchDeduct(List<AuthApiModels.ConsumedColorData> consumedColors) {
        List<AuthApiModels.BatchDeductItem> items = new ArrayList<>();
        for (AuthApiModels.ConsumedColorData c : consumedColors) {
            if (c.quantity > 0) {
                items.add(new AuthApiModels.BatchDeductItem(c.colorCode, c.quantity));
            }
        }
        int userId = com.example.couplecredit.utils.UserInfoManager.getCurrentUserId(requireContext());
        com.example.couplecredit.api.AuthApiClient.batchDeductInventory(requireContext(), userId, items, new com.example.couplecredit.api.AuthApiClient.BeadMutationCallback() {
            @Override public void onSuccess() {
                if (!isAdded()) return;
                viewModel.loadInventory();
                viewModel.loadBlueprints();
                loadDetail();
                Toast.makeText(requireContext(), "制作成功，库存已扣减", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String error) {
                if (!isAdded()) return;
                viewModel.loadBlueprints();
                loadDetail();
                Toast.makeText(requireContext(), "制作成功，库存扣减失败: " + error, Toast.LENGTH_SHORT).show();
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
        dialogView.findViewById(R.id.ll_grid_size).setVisibility(View.GONE);
        dialogView.findViewById(R.id.ll_ai_actions).setVisibility(View.GONE);
        dialogView.findViewById(R.id.bead_grid_preview).setVisibility(View.GONE);

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
        colors.sort((a, b) -> Integer.compare(b.quantityPerBuild, a.quantityPerBuild));
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

    static class ConsumeColorAdapter extends RecyclerView.Adapter<ConsumeColorAdapter.ViewHolder> {
        private final List<BlueprintColorDisplayItem> colors;
        private final BeadInventoryViewModel viewModel;

        ConsumeColorAdapter(List<BlueprintColorDisplayItem> colors, BeadInventoryViewModel viewModel) {
            this.colors = colors;
            this.viewModel = viewModel;
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_consume_color, parent, false);
            return new ViewHolder(v);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BlueprintColorDisplayItem item = colors.get(position);
            try {
                holder.swatch.setBackgroundColor(android.graphics.Color.parseColor(item.hexColor));
            } catch (Exception e) {
                holder.swatch.setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"));
            }
            holder.tvCode.setText(item.colorCode);
            holder.tvQty.setText("-" + item.quantityPerBuild + " 颗");
            Integer stock = viewModel != null ? viewModel.getInventoryQuantity(item.colorCode) : null;
            if (stock != null && stock < item.quantityPerBuild) {
                holder.tvStatus.setVisibility(View.VISIBLE);
                holder.tvStatus.setText("库存不足 (剩" + stock + ")");
            } else {
                holder.tvStatus.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return colors.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            View swatch;
            TextView tvCode;
            TextView tvQty;
            TextView tvStatus;
            ViewHolder(View v) {
                super(v);
                swatch = v.findViewById(R.id.view_color_swatch);
                tvCode = v.findViewById(R.id.tv_color_code);
                tvQty = v.findViewById(R.id.tv_consume_qty);
                tvStatus = v.findViewById(R.id.tv_stock_status);
            }
        }
    }
}
