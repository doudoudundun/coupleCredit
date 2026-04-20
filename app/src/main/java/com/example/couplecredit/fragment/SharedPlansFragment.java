package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.SharedPlanAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SharedPlansFragment extends Fragment implements SharedPlanAdapter.SharedPlanActionListener {
    private final DataRefreshBus.Listener refreshListener = () -> refreshData();

    private TextView tvPlanCount;
    private TextView tvTotalBalance;
    private RecyclerView rvSharedPlans;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private LinearLayout layoutContent;
    private TextView btnLoginPrompt;
    private View fabAddPlan;

    private final List<AuthApiModels.SharedPlanData> plans = new ArrayList<>();
    private SharedPlanAdapter adapter;
    private boolean isLoggedIn;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_shared_plans, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvPlanCount = view.findViewById(R.id.tv_plan_count);
        tvTotalBalance = view.findViewById(R.id.tv_total_balance);
        rvSharedPlans = view.findViewById(R.id.rv_shared_plans);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        layoutLoginPrompt = view.findViewById(R.id.layout_login_prompt);
        layoutContent = view.findViewById(R.id.layout_content);
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        fabAddPlan = view.findViewById(R.id.fab_add_plan);

        rvSharedPlans.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SharedPlanAdapter(plans, this);
        rvSharedPlans.setAdapter(adapter);

        btnLoginPrompt.setOnClickListener(v -> startActivity(new Intent(requireContext(), LoginActivity.class)));
        fabAddPlan.setOnClickListener(v -> {
            if (!isLoggedIn) {
                startActivity(new Intent(requireContext(), LoginActivity.class));
                return;
            }
            showCreateDialog();
        });

        refreshData();
        DataRefreshBus.subscribe(refreshListener);
    }

    @Override
    public void onDestroyView() {
        DataRefreshBus.unsubscribe(refreshListener);
        super.onDestroyView();
    }

    public void refreshData() {
        if (!isAdded()) return;
        isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        updateLoginUi();
        if (!isLoggedIn) {
            plans.clear();
            updateSummary();
            adapter.updateData(plans);
            return;
        }
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.querySharedPlans(requireContext(), userId, new AuthApiClient.SharedPlanListCallback() {
            @Override
            public void onSuccess(AuthApiModels.SharedPlanListResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    plans.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        plans.addAll(response.data.items);
                    }
                    adapter.updateData(plans);
                    updateSummary();
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "共同计划加载失败: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateLoginUi() {
        layoutLoginPrompt.setVisibility(isLoggedIn ? View.GONE : View.VISIBLE);
        layoutContent.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        fabAddPlan.setEnabled(isLoggedIn);
        fabAddPlan.setAlpha(isLoggedIn ? 1f : 0.5f);
    }

    private void updateSummary() {
        tvPlanCount.setText(String.valueOf(plans.size()));
        double total = 0;
        for (AuthApiModels.SharedPlanData plan : plans) total += plan.currentBalance;
        tvTotalBalance.setText("￥" + String.format(Locale.getDefault(), "%.2f", total));
        llEmptyState.setVisibility(plans.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
        rvSharedPlans.setVisibility(plans.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showCreateDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_plan, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView)
                .create();

        EditText etName = dialogView.findViewById(R.id.et_plan_name);
        EditText etAmount = dialogView.findViewById(R.id.et_plan_amount);
        TextView btnVisBoth = dialogView.findViewById(R.id.btn_vis_both);
        TextView btnVisSelf = dialogView.findViewById(R.id.btn_vis_self);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        final String[] visibility = {"both"};
        btnVisBoth.setOnClickListener(v -> {
            visibility[0] = "both";
            btnVisBoth.setBackgroundResource(R.drawable.button_background);
            btnVisBoth.setTextColor(getResources().getColor(android.R.color.white));
            btnVisSelf.setBackgroundResource(R.drawable.btn_cancel_background);
            btnVisSelf.setTextColor(0xFF6B7280);
        });
        btnVisSelf.setOnClickListener(v -> {
            visibility[0] = "self";
            btnVisSelf.setBackgroundResource(R.drawable.button_background);
            btnVisSelf.setTextColor(getResources().getColor(android.R.color.white));
            btnVisBoth.setBackgroundResource(R.drawable.btn_cancel_background);
            btnVisBoth.setTextColor(0xFF6B7280);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(requireContext(), "请输入计划名称", Toast.LENGTH_SHORT).show();
                return;
            }
            double amount = 0;
            String amountText = etAmount.getText().toString().trim();
            if (!amountText.isEmpty()) amount = Double.parseDouble(amountText);
            dialog.dismiss();
            createPlan(name, amount, visibility[0]);
        });

        dialog.show();
        showDialogWide(dialog);
    }

    private void createPlan(String name, double amount, String visibility) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiModels.CreateSharedPlanRequest request = new AuthApiModels.CreateSharedPlanRequest(userId, name, amount, visibility);
        AuthApiClient.createSharedPlan(requireContext(), request, new AuthApiClient.SharedPlanMutationCallback() {
            @Override public void onSuccess() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "计划已创建", Toast.LENGTH_SHORT).show();
                    refreshData();
                    DataRefreshBus.refreshAll();
                });
            }
            @Override public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onAddMoney(AuthApiModels.SharedPlanData item) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_amount_input, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView)
                .create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_current_info);
        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
        TextView btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        tvTitle.setText("存入 " + item.name);
        tvInfo.setText("当前余额：￥" + String.format(Locale.getDefault(), "%.2f", item.currentBalance));
        etAmount.setHint("存入金额");
        btnConfirm.setText("确认存入");

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String text = etAmount.getText().toString().trim();
            if (text.isEmpty()) return;
            dialog.dismiss();
            adjustPlan(item.planId, Double.parseDouble(text), "in");
        });

        dialog.show();
        showDialogWide(dialog);
    }

    @Override
    public void onDelete(AuthApiModels.SharedPlanData item) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除计划")
                .setMessage("确定删除\"" + item.name + "\"吗？已关联的账单会保留。")
                .setPositiveButton("删除", (dialog, which) -> deletePlan(item.planId))
                .setNegativeButton("取消", null)
                .show();
    }

    private void adjustPlan(int planId, double amount, String direction) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiModels.AdjustSharedPlanRequest request = new AuthApiModels.AdjustSharedPlanRequest(userId, amount, direction);
        AuthApiClient.adjustSharedPlan(requireContext(), planId, request, new AuthApiClient.SharedPlanMutationCallback() {
            @Override public void onSuccess() {
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    refreshData();
                    DataRefreshBus.refreshAll();
                });
            }
            @Override public void onError(String message) {
                if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deletePlan(int planId) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.deleteSharedPlan(requireContext(), planId, userId, new AuthApiClient.SharedPlanMutationCallback() {
            @Override public void onSuccess() {
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    refreshData();
                    DataRefreshBus.refreshAll();
                });
            }
            @Override public void onError(String message) {
                if (isAdded()) requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showDialogWide(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }
}
