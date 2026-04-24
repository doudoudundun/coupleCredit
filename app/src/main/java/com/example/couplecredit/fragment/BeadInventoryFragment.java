package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.BeadColorAdapter;
import com.example.couplecredit.utils.BeadUtils;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.List;
import java.util.Locale;

public class BeadInventoryFragment extends Fragment {

    private BeadInventoryViewModel viewModel;
    private BeadColorAdapter adapter;
    private TextView tvSummary;
    private EditText etSearch;
    private TextView btnAll;
    private TextView btnLowStock;
    private ProgressBar progressBar;
    private TextView tvError;
    private boolean lowStockOnly = false;

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

        RecyclerView recyclerView = view.findViewById(R.id.rv_bead_colors);
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        adapter = new BeadColorAdapter();
        adapter.setOnBeadColorClickListener(this::showQuantityDialog);
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btn_bead_back).setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        view.findViewById(R.id.btn_bead_blueprints).setOnClickListener(v -> openBlueprintList());
        btnAll.setOnClickListener(v -> setLowStockOnly(false));
        btnLowStock.setOnClickListener(v -> setLowStockOnly(true));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters(); }
            @Override public void afterTextChanged(Editable s) {}
        });

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
                viewModel.getInventoryList(), etSearch.getText().toString(), lowStockOnly);
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
