package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadBulkReplenishAdapter;
import com.example.couplecredit.adapter.BeadColorAdapter;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.List;
import java.util.Locale;

public class BeadInventoryFragment extends Fragment {

    private static final String[] COLOR_GROUPS = {"A", "B", "C", "D", "E", "F", "G", "H", "M"};

    private BeadInventoryViewModel viewModel;
    private BeadColorAdapter adapter;
    private TextView tvSummary;
    private EditText etSearch;
    private TextView btnAll;
    private TextView btnLowStock;
    private ProgressBar progressBar;
    private TextView tvError;
    private boolean lowStockOnly = false;
    private String selectedGroup = null;
    private LinearLayout groupChipContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bead_inventory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(BeadInventoryViewModel.class);
        tvSummary = view.findViewById(R.id.tv_bead_summary);
        etSearch = view.findViewById(R.id.et_bead_search);
        btnAll = view.findViewById(R.id.btn_bead_all);
        btnLowStock = view.findViewById(R.id.btn_bead_low_stock);
        progressBar = view.findViewById(R.id.progress_bead_loading);
        tvError = view.findViewById(R.id.tv_bead_error);
        groupChipContainer = view.findViewById(R.id.ll_bead_group_chips);

        RecyclerView recyclerView = view.findViewById(R.id.rv_bead_colors);
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        adapter = new BeadColorAdapter();
        adapter.setOnBeadColorClickListener(this::showQuantityDialog);
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_bead_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.btn_bead_blueprints).setOnClickListener(v -> openBlueprintList());
        view.findViewById(R.id.btn_bead_bulk_replenish).setOnClickListener(v -> showBulkReplenishDialog());
        btnAll.setOnClickListener(v -> setLowStockOnly(false));
        btnLowStock.setOnClickListener(v -> setLowStockOnly(true));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        buildGroupChips();

        viewModel.getDataVersion().observe(getViewLifecycleOwner(), version -> bindData());
        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> progressBar.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                showInlineError(error);
                viewModel.clearError();
            }
        });

        if (!UserInfoManager.isUserLoggedIn(requireContext())) {
            Toast.makeText(requireContext(), "请先登录后查看拼豆库存", Toast.LENGTH_SHORT).show();
            requireActivity().getSupportFragmentManager().popBackStack();
            return;
        }

        if (!viewModel.hasInventoryData()) {
            viewModel.loadInventory();
        } else {
            bindData();
        }
    }

    private void buildGroupChips() {
        groupChipContainer.removeAllViews();
        TextView allChip = createGroupChip("全部", null);
        allChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_color));
        groupChipContainer.addView(allChip);

        for (String group : COLOR_GROUPS) {
            TextView chip = createGroupChip(group, group);
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            groupChipContainer.addView(chip);
        }
    }

    private TextView createGroupChip(String label, String groupValue) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(13);
        int padH = dpToPx(14);
        int padV = dpToPx(7);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.category_tag_background));
        chip.setOnClickListener(v -> {
            selectedGroup = groupValue;
            updateGroupChipColors();
            applyFilters();
        });
        chip.setTag(groupValue);
        return chip;
    }

    private void updateGroupChipColors() {
        for (int i = 0; i < groupChipContainer.getChildCount(); i++) {
            View child = groupChipContainer.getChildAt(i);
            if (child instanceof TextView) {
                Object tag = child.getTag();
                boolean isSelected = (selectedGroup == null && tag == null) || (selectedGroup != null && selectedGroup.equals(tag));
                ((TextView) child).setTextColor(ContextCompat.getColor(requireContext(),
                        isSelected ? R.color.primary_color : R.color.text_secondary));
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void setLowStockOnly(boolean enabled) {
        lowStockOnly = enabled;
        btnAll.setTextColor(ContextCompat.getColor(requireContext(), enabled ? R.color.text_secondary : R.color.primary_color));
        btnLowStock.setTextColor(ContextCompat.getColor(requireContext(), enabled ? R.color.primary_color : R.color.text_secondary));
        applyFilters();
    }

    private void bindData() {
        if (tvError != null) {
            tvError.setVisibility(View.GONE);
        }
        BeadInventoryViewModel.BeadSummary summary = viewModel.getSummary();
        tvSummary.setText(String.format(Locale.getDefault(), "%d 色 · %d 色告急 · 理论消耗 %d 颗",
                summary.totalColors, summary.lowStockCount, summary.totalConsumptionReference));
        applyFilters();
    }

    private void showInlineError(String error) {
        if (tvError == null) {
            return;
        }
        tvError.setText("拼豆库存加载失败：" + error);
        tvError.setVisibility(View.VISIBLE);
    }

    private void applyFilters() {
        if (adapter == null || etSearch == null) {
            return;
        }
        List<BeadColorAdapter.BeadColorDisplayItem> items = BeadColorAdapter.buildDisplayItems(
                viewModel.getInventoryList(), etSearch.getText().toString(), lowStockOnly, selectedGroup);
        adapter.submitList(items);
    }

    private void showQuantityDialog(BeadColorAdapter.BeadColorDisplayItem item) {
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bead_quantity, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(content).create();
        TextView title = content.findViewById(R.id.tv_bead_dialog_title);
        EditText etQuantity = content.findViewById(R.id.et_bead_quantity);
        EditText etThreshold = content.findViewById(R.id.et_bead_threshold);
        title.setText("更新 " + item.colorCode);
        etQuantity.setText(String.valueOf(item.quantity));
        if (item.thresholdOverride != null) {
            etThreshold.setText(String.valueOf(item.thresholdOverride));
        }
        content.findViewById(R.id.btn_bead_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btn_bead_dialog_save).setOnClickListener(v -> {
            Integer quantity = parseNonNegativeInt(etQuantity.getText().toString());
            if (quantity == null) {
                Toast.makeText(requireContext(), "请输入有效库存数量", Toast.LENGTH_SHORT).show();
                return;
            }
            Integer threshold = null;
            String thresholdText = etThreshold.getText().toString().trim();
            if (!thresholdText.isEmpty()) {
                threshold = parseNonNegativeInt(thresholdText);
                if (threshold == null) {
                    Toast.makeText(requireContext(), "请输入有效告急线", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            BeadUtils.updateInventory(requireContext(), item.colorCode, quantity, threshold, new BeadUtils.BeadMutationCallback() {
                @Override public void onSuccess() {
                    dialog.dismiss();
                    viewModel.loadInventory();
                }
                @Override public void onError(String error) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    // --- Bulk Replenish ---

    private void showBulkReplenishDialog() {
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bead_bulk_replenish, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(content).create();

        LinearLayout chipContainer = content.findViewById(R.id.ll_bulk_group_chips);
        TextView tvSummary = content.findViewById(R.id.tv_bulk_summary);
        TextView tvProgress = content.findViewById(R.id.tv_bulk_progress);
        RecyclerView rvColors = content.findViewById(R.id.rv_bulk_colors);
        rvColors.setLayoutManager(new GridLayoutManager(requireContext(), 3));

        BeadBulkReplenishAdapter bulkAdapter = new BeadBulkReplenishAdapter();
        rvColors.setAdapter(bulkAdapter);
        bulkAdapter.setOnSelectionChanged(() ->
                tvSummary.setText(String.format(Locale.getDefault(), "已选 %d 种颜色", bulkAdapter.getSelectedCount())));

        buildDialogGroupChips(chipContainer, group -> {
            List<BeadInventoryViewModel.BeadInventoryItem> filtered = filterByGroup(viewModel.getInventoryList(), group);
            bulkAdapter.submitList(filtered);
        });

        bulkAdapter.submitList(viewModel.getInventoryList());

        content.findViewById(R.id.btn_bulk_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btn_bulk_replenish).setOnClickListener(v -> {
            List<BeadBulkReplenishAdapter.ReplenishEntry> entries = bulkAdapter.getSelectedItems();
            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), "请至少选择一种颜色", Toast.LENGTH_SHORT).show();
                return;
            }
            content.findViewById(R.id.btn_bulk_replenish).setEnabled(false);
            executeBulkReplenish(entries, 0, dialog, bulkAdapter, tvProgress, content.findViewById(R.id.btn_bulk_replenish));
        });

        dialog.show();
    }

    private void buildDialogGroupChips(LinearLayout container, OnGroupSelectedListener listener) {
        container.removeAllViews();
        String[] dialogGroup = {null};

        TextView allChip = createDialogGroupChip(container, "全部", null, dialogGroup, listener);
        allChip.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_color));

        for (String group : COLOR_GROUPS) {
            createDialogGroupChip(container, group, group, dialogGroup, listener);
        }
    }

    private TextView createDialogGroupChip(LinearLayout container, String label, String groupValue,
                                            String[] dialogGroup, OnGroupSelectedListener listener) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(13);
        int padH = dpToPx(14);
        int padV = dpToPx(7);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.category_tag_background));
        chip.setTag(groupValue);
        chip.setOnClickListener(v -> {
            dialogGroup[0] = groupValue;
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof TextView) {
                    Object tag = child.getTag();
                    boolean sel = (groupValue == null && tag == null) || (groupValue != null && groupValue.equals(tag));
                    ((TextView) child).setTextColor(ContextCompat.getColor(requireContext(),
                            sel ? R.color.primary_color : R.color.text_secondary));
                }
            }
            listener.onGroupSelected(groupValue);
        });
        if (groupValue != null) {
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        }
        container.addView(chip);
        return chip;
    }

    private interface OnGroupSelectedListener {
        void onGroupSelected(String group);
    }

    private List<BeadInventoryViewModel.BeadInventoryItem> filterByGroup(
            List<BeadInventoryViewModel.BeadInventoryItem> items, String group) {
        if (group == null || items == null) {
            return items;
        }
        java.util.ArrayList<BeadInventoryViewModel.BeadInventoryItem> filtered = new java.util.ArrayList<>();
        for (BeadInventoryViewModel.BeadInventoryItem item : items) {
            if (group.equals(item.colorGroup)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void executeBulkReplenish(List<BeadBulkReplenishAdapter.ReplenishEntry> entries, int index,
                                      AlertDialog dialog, BeadBulkReplenishAdapter adapter,
                                      TextView tvProgress, View btnReplenish) {
        if (index >= entries.size()) {
            Toast.makeText(requireContext(),
                    String.format(Locale.getDefault(), "补货完成，共 %d 种颜色", entries.size()),
                    Toast.LENGTH_SHORT).show();
            viewModel.loadInventory();
            adapter.clearSelections();
            tvProgress.setVisibility(View.GONE);
            btnReplenish.setEnabled(true);
            return;
        }
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText(String.format(Locale.getDefault(), "正在补货 %d/%d...", index + 1, entries.size()));

        BeadBulkReplenishAdapter.ReplenishEntry entry = entries.get(index);
        BeadUtils.replenishInventory(requireContext(), entry.colorCode, entry.amount,
                new BeadUtils.BeadMutationCallback() {
                    @Override public void onSuccess() {
                        executeBulkReplenish(entries, index + 1, dialog, adapter, tvProgress, btnReplenish);
                    }
                    @Override public void onError(String error) {
                        Toast.makeText(requireContext(), entry.colorCode + " 补货失败：" + error, Toast.LENGTH_SHORT).show();
                        executeBulkReplenish(entries, index + 1, dialog, adapter, tvProgress, btnReplenish);
                    }
                });
    }

    private Integer parseNonNegativeInt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void openBlueprintList() {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new BeadBlueprintListFragment())
                .addToBackStack("bead_blueprints")
                .commit();
    }
}
