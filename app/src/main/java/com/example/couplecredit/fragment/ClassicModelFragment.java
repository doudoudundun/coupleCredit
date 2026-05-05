package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.model.BillBean;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.adapter.BillAdapter;
import com.example.couplecredit.utils.BillUtils;
import com.example.couplecredit.utils.CategoryIconMapper;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.viewmodel.ClassicViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClassicModelFragment extends Fragment implements BillAdapter.OnItemClickListener {
    private final DataRefreshBus.Listener refreshListener = () -> refreshBillData();
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<Object> displayItems;
    private TextView tvMonthTitle;
    private int currentYear;
    private int currentMonth;
    private TextView tvLoginPrompt;
    private View fabQuickAddBill;
    private TextView tvExpenseAmount;
    private TextView tvIncomeAmount;
    private AlertDialog mDialog;
    private View dialogView;
    private ClassicViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.classic_model_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1;

        tvMonthTitle = view.findViewById(R.id.tv_month_title);
        rvBillList = view.findViewById(R.id.rv_bill_list);
        tvExpenseAmount = view.findViewById(R.id.tv_expense_amount);
        tvIncomeAmount = view.findViewById(R.id.tv_income_amount);
        tvLoginPrompt = view.findViewById(R.id.tv_login_prompt);
        fabQuickAddBill = view.findViewById(R.id.fab_quick_add_bill);

        LinearLayout llExpenseCard = view.findViewById(R.id.ll_expense_card);
        LinearLayout llIncomeCard = view.findViewById(R.id.ll_income_card);

        llExpenseCard.setOnClickListener(v -> openReportFragment("expense"));
        llIncomeCard.setOnClickListener(v -> openReportFragment("income"));
        if (fabQuickAddBill != null) {
            fabQuickAddBill.setOnClickListener(v -> openAddBillTab());
        }

        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rvBillList.setLayoutManager(layoutManager);

        viewModel = new ViewModelProvider(this).get(ClassicViewModel.class);
        displayItems = viewModel.getDisplayItemsRef();
        billAdapter = new BillAdapter(getContext(), displayItems, (v1, position, bill) -> processDialog(bill));
        rvBillList.setAdapter(billAdapter);

        viewModel.getDisplayVersion().observe(getViewLifecycleOwner(), v2 -> {
            if (billAdapter != null) {
                billAdapter.notifyDataSetChanged();
            }
        });
        viewModel.getTotalIncome().observe(getViewLifecycleOwner(), income -> {
            if (tvIncomeAmount != null && income != null) {
                tvIncomeAmount.setText("￥ " + String.format("%.2f", income));
            }
        });
        viewModel.getTotalExpense().observe(getViewLifecycleOwner(), expense -> {
            if (tvExpenseAmount != null && expense != null) {
                tvExpenseAmount.setText("￥ " + String.format("%.2f", expense));
            }
        });
        viewModel.getIsLoggedIn().observe(getViewLifecycleOwner(), loggedIn -> {
            if (Boolean.TRUE.equals(loggedIn)) {
                hideLoginPrompt();
            } else {
                showLoginPrompt();
            }
        });
        viewModel.getMonthTitle().observe(getViewLifecycleOwner(), title -> {
            if (tvMonthTitle != null && title != null) {
                tvMonthTitle.setText(title);
            }
        });

        viewModel.initialize();
        tvMonthTitle.setOnClickListener(v -> showDatePickerDialog());

        DataRefreshBus.subscribe(refreshListener);
    }

    @Override
    public void onDestroyView() {
        DataRefreshBus.unsubscribe(refreshListener);
        super.onDestroyView();
    }

    private void openAddBillTab() {
        MainActivity mainActivity = (MainActivity) getActivity();
        if (mainActivity == null) {
            return;
        }
        HeadFragment headFragment = mainActivity.getHeadFragment();
        if (headFragment != null) {
            headFragment.switchToAddBillTab();
        }
    }

    private void openReportFragment(String type) {
        MainActivity mainActivity = (MainActivity) getActivity();
        if (mainActivity == null) {
            return;
        }
        HeadFragment headFragment = mainActivity.getHeadFragment();
        com.example.couplecredit.fragment.ReportFragment reportFragment = headFragment != null ? headFragment.getReportFragment() : null;
        if (reportFragment == null) {
            reportFragment = new com.example.couplecredit.fragment.ReportFragment();
        }
        headFragment.switchToReportTab();
        reportFragment.updateDisplay(currentYear, currentMonth, type);
    }

    public void refreshBillData() {
        if (getContext() != null && viewModel != null) {
            viewModel.refreshCurrentMonth();
            currentYear = viewModel.getCurrentYear();
            currentMonth = viewModel.getCurrentMonth();
        }
    }

    private void showDatePickerDialog() {
        if (viewModel != null) {
            BillUtils.showDatePickerDialog(getContext(), viewModel.getCurrentYear(), viewModel.getCurrentMonth(),
                    (selectedYear, selectedMonth) -> {
                        viewModel.setYearMonth(selectedYear, selectedMonth);
                        currentYear = selectedYear;
                        currentMonth = selectedMonth;
                    });
        }
    }

    public void processDialog(BillBean bill) {
        double fare = bill.getFare();
        String incomeType = bill.getIncomeType() == 1 ? "收入" : "支出";
        String categoryName = bill.getCategoryName();
        String date = String.format("%04d-%02d-%02d", bill.getYear(), bill.getMonth(), bill.getDay());

        dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_layout, null);
        mDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("账单详情")
                .setView(dialogView)
                .create();
        mDialog.show();

        ImageView ivCategoryIcon = dialogView.findViewById(R.id.iv_category_icon);
        TextView tvCategoryName = dialogView.findViewById(R.id.tv_category_name);
        TextView tvDate = dialogView.findViewById(R.id.tv_date);
        TextView tvFare = dialogView.findViewById(R.id.tv_fare);
        TextView tvNoteContent = dialogView.findViewById(R.id.tv_note_content);
        EditText etFare = dialogView.findViewById(R.id.et_fare);
        EditText etNoteContent = dialogView.findViewById(R.id.et_note_content);
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinner_bill_category);
        Button btnDelete = dialogView.findViewById(R.id.btn_delete);
        Button btnEdit = dialogView.findViewById(R.id.btn_edit);
        ImageButton btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        ImageButton btnCancel = dialogView.findViewById(R.id.btn_cancel);
        LinearLayout llNoteCard = dialogView.findViewById(R.id.ll_note_card);

        // 初始化种类 Spinner
        boolean isExpense = bill.getIncomeType() != 1;
        List<String> categories = CategoryIconMapper.getCategoriesByType(isExpense);
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, categories);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerCategory != null) {
            spinnerCategory.setAdapter(categoryAdapter);
            int idx = categories.indexOf(categoryName);
            spinnerCategory.setSelection(idx >= 0 ? idx : 0);
        }

        if (ivCategoryIcon != null) {
            int iconResId = CategoryIconMapper.getIconForCategory(categoryName);
            ivCategoryIcon.setImageResource(iconResId);
            ivCategoryIcon.setBackground(null);
        }
        if (tvCategoryName != null) {
            tvCategoryName.setText(incomeType + "-" + categoryName);
        }
        if (tvDate != null) {
            tvDate.setText(date);
        }
        if (tvFare != null) {
            tvFare.setText("￥ " + String.format("%.2f", fare));
        }
        if (etFare != null) {
            etFare.setText("￥" + String.format("%.2f", fare));
        }

        String noteTitle = bill.getTitle();
        if (noteTitle != null && !noteTitle.trim().isEmpty() && !noteTitle.equals(categoryName)) {
            if (llNoteCard != null) {
                llNoteCard.setVisibility(View.VISIBLE);
            }
            if (tvNoteContent != null) {
                tvNoteContent.setText(noteTitle);
            }
            if (etNoteContent != null) {
                etNoteContent.setText(noteTitle);
            }
        } else if (llNoteCard != null) {
            llNoteCard.setVisibility(View.GONE);
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> showWarningDialog(bill));
        }
        if (btnEdit != null) {
            btnEdit.setOnClickListener(v -> BillUtils.enterEditMode(getContext(), mDialog, tvDate, tvFare, tvNoteContent,
                    etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel, tvCategoryName, spinnerCategory));
        }
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                String currentDate = tvDate.getText().toString();
                String fareText = etFare.getText().toString();
                double currentFare = fareText.startsWith("￥") ? Double.parseDouble(fareText.substring(1)) : Double.parseDouble(fareText);
                String currentNoteContent = etNoteContent.getText().toString();
                String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                String selectedCategory = (spinnerCategory != null && spinnerCategory.getSelectedItem() != null)
                        ? spinnerCategory.getSelectedItem().toString() : null;

                BillUtils.updateBill(getContext(), bill, currentDate, currentFare, currentNoteContent, currentTime, selectedCategory, null, new BillUtils.UpdateBillCallback() {
                    @Override
                    public void onUpdateSuccess(int rowsAffected) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (rowsAffected > 0) {
                                    refreshBillData();
                                    if (getActivity() instanceof MainActivity) {
                                        HeadFragment hf = ((MainActivity) getActivity()).getHeadFragment();
                                        com.example.couplecredit.fragment.ReportFragment rf = hf != null ? hf.getReportFragment() : null;
                                        if (rf != null) { rf.refreshChartData(); }
                                    }
                                    mDialog.dismiss();
                                }
                            });
                        }
                    }

                    @Override
                    public void onUpdateError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> Log.e("ClassicModelFragment", "更新账单失败: " + error));
                        }
                    }
                });
                BillUtils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel, tvCategoryName, spinnerCategory, incomeType);
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (etFare != null) {
                    etFare.setText("￥" + String.format("%.2f", fare));
                }
                if (etNoteContent != null) {
                    etNoteContent.setText(noteTitle != null ? noteTitle : "");
                }
                if (tvFare != null) {
                    tvFare.setText("￥ " + String.format("%.2f", fare));
                }
                if (tvNoteContent != null) {
                    tvNoteContent.setText(noteTitle != null ? noteTitle : "");
                }
                if (spinnerCategory != null) {
                    int idx = categories.indexOf(categoryName);
                    spinnerCategory.setSelection(idx >= 0 ? idx : 0);
                }
                BillUtils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent, btnEdit, btnDelete, btnConfirm, btnCancel, tvCategoryName, spinnerCategory, incomeType);
            });
        }
    }

    private void showWarningDialog(BillBean bill) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除账单（此举不可逆）")
                .setPositiveButton("确定", (dialog, which) -> BillUtils.deleteBill(getContext(), bill, new BillUtils.DeleteBillCallback() {
                    @Override
                    public void onDeleteSuccess(int rowsDeleted) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (rowsDeleted > 0) {
                                    refreshBillData();
                                    if (getActivity() instanceof MainActivity) {
                                        HeadFragment hf = ((MainActivity) getActivity()).getHeadFragment();
                                        com.example.couplecredit.fragment.ReportFragment rf = hf != null ? hf.getReportFragment() : null;
                                        if (rf != null) { rf.refreshChartData(); }
                                    }
                                    if (mDialog != null) {
                                        mDialog.dismiss();
                                    }
                                }
                            });
                        }
                    }

                    @Override
                    public void onDeleteError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> Log.e("ClassicModelFragment", "删除账单失败: " + error));
                        }
                    }
                }))
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showLoginPrompt() {
        if (tvLoginPrompt != null) {
            tvLoginPrompt.setVisibility(View.VISIBLE);
        }
        if (rvBillList != null) {
            rvBillList.setVisibility(View.GONE);
        }
    }

    private void hideLoginPrompt() {
        if (tvLoginPrompt != null) {
            tvLoginPrompt.setVisibility(View.GONE);
        }
        if (rvBillList != null) {
            rvBillList.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onItemClick(View view, int position, BillBean bill) {
        processDialog(bill);
    }
}
