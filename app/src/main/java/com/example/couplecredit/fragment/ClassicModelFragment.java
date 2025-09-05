package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.ViewModelProvider;

import com.example.couplecredit.adapter.BillAdapter;
import com.example.couplecredit.BillBean;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.activity.MainActivity;
import com.example.couplecredit.R;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.viewmodel.ClassicViewModel;
import com.transsion.widgetslib.dialog.PromptDialog;
import com.example.couplecredit.utils.CategoryIconMapper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ClassicModelFragment extends Fragment implements BillAdapter.OnItemClickListener {
    private RecyclerView rvBillList;
    private BillAdapter billAdapter;
    private List<Object> displayItems; // 混合数据：String(日期) 和 BillBean，由ViewModel维护
    private TextView tvMonthTitle;
    private int currentYear;  // 用于与ReportFragment交互的同步字段
    private int currentMonth; // 用于与ReportFragment交互的同步字段
    private TextView tvExpenseAmount;
    private TextView tvIncomeAmount;
    private TextView tvLoginPrompt;

    private PromptDialog mDialog;
    private ClassicViewModel viewModel;



    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.classic_model_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取当前年月（用于与ReportFragment交互时传值）
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH从0开始

        // 初始化视图
        tvMonthTitle = view.findViewById(R.id.tv_month_title);
        rvBillList = view.findViewById(R.id.rv_bill_list);
        tvExpenseAmount = view.findViewById(R.id.tv_expense_amount);
        tvIncomeAmount = view.findViewById(R.id.tv_income_amount);
        tvLoginPrompt = view.findViewById(R.id.tv_login_prompt);
        
        // 设置卡片点击监听器
        LinearLayout llExpenseCard = view.findViewById(R.id.ll_expense_card);
        LinearLayout llIncomeCard = view.findViewById(R.id.ll_income_card);
        
        llExpenseCard.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity != null) {
                // 跳转到ReportFragment并同步月份
                ReportFragment reportFragment = mainActivity.getReportFragment();
                mainActivity.showFragment(reportFragment);

                // 更新底部导航栏选中状态
                com.transsion.widgetslib.widget.FootOperationBar footBar =
                        (com.transsion.widgetslib.widget.FootOperationBar) mainActivity.findViewById(R.id.bottom_nav);
                if (footBar != null) {
                    footBar.setItemSelectState(2);
                }
                // 更新ReportFragment的月份显示
                reportFragment.updateDisplay(currentYear, currentMonth, "expense");
            }
        });
        
        llIncomeCard.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity != null) {
                // 跳转到ReportFragment并同步月份
                ReportFragment reportFragment = mainActivity.getReportFragment();
                mainActivity.showFragment(reportFragment);
                // 更新ReportFragment的月份显示
                reportFragment.updateDisplay(currentYear, currentMonth, "income");
                // 更新底部导航栏选中状态
                com.transsion.widgetslib.widget.FootOperationBar footBar = 
                    (com.transsion.widgetslib.widget.FootOperationBar) mainActivity.findViewById(R.id.bottom_nav);
                if (footBar != null) {
                    footBar.setItemSelectState(2);
                }
            }
        });


        // 移除初始化时的弹窗创建，改为在点击时创建


        //统计收入和支出

        // 由 ViewModel 的 monthTitle 管理，无需本地 updateMonthTitle 调用
        // 移除此处的月份点击事件绑定，统一在下方基于 ViewModel 初始化后设置

        // 初始化RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rvBillList.setLayoutManager(layoutManager);

        // ================= MVVM接入 =================
        viewModel = new ViewModelProvider(this).get(ClassicViewModel.class);

        // 适配器使用ViewModel维护的displayItems引用，确保显示结构不变
        displayItems = viewModel.getDisplayItemsRef();
        billAdapter = new BillAdapter(getContext(), displayItems, (v1, position, bill) -> processDialog(bill));
        rvBillList.setAdapter(billAdapter);

        // 观察列表版本，刷新适配器
        viewModel.getDisplayVersion().observe(getViewLifecycleOwner(), v2 -> {
            if (billAdapter != null) billAdapter.notifyDataSetChanged();
        });
        // 观察收入/支出合计
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
        // 观察登录状态，切换提示与列表
        viewModel.getIsLoggedIn().observe(getViewLifecycleOwner(), loggedIn -> {
            if (Boolean.TRUE.equals(loggedIn)) {
                hideLoginPrompt();
            } else {
                showLoginPrompt();
            }
        });
        // 观察月份标题
        viewModel.getMonthTitle().observe(getViewLifecycleOwner(), title -> {
            if (tvMonthTitle != null && title != null) tvMonthTitle.setText(title);
        });

        // 首次加载
        viewModel.initialize();

        // 更新月份选择器点击
        tvMonthTitle.setOnClickListener(v -> showDatePickerDialog());
    }


    
    // 公共方法：刷新当前月份的账单数据
    public void refreshBillData() {
        if (getContext() != null) {
            // 由ViewModel统一刷新当前月份数据
            if (viewModel != null) {
                viewModel.refreshCurrentMonth();
            }
            // 同步更新给ReportFragment时取ViewModel中的年月
            currentYear = viewModel != null ? viewModel.getCurrentYear() : currentYear;
            currentMonth = viewModel != null ? viewModel.getCurrentMonth() : currentMonth;
        }
    }

    private void showDatePickerDialog() {
        if (viewModel != null) {
            Utils.showDatePickerDialog(getContext(), viewModel.getCurrentYear(), viewModel.getCurrentMonth(), 
                (selectedYear, selectedMonth) -> {
                    if (viewModel != null) {
                        viewModel.setYearMonth(selectedYear, selectedMonth);
                    }
                    // 同步本地字段用于与ReportFragment交互
                    currentYear = selectedYear;
                    currentMonth = selectedMonth;
                });
        }
    }

    public void processDialog(BillBean bill){

        double fare = bill.getFare();
        String incomeType = bill.getIncomeType() == 1 ? "收入" : "支出";
        String categoryName = bill.getCategoryName();
        int day = bill.getDay();
        int month = bill.getMonth();
        int year = bill.getYear();
        String date = String.format("%04d-%02d-%02d", year, month, day);

        // 创建对话框
        mDialog = new PromptDialog.Builder(getContext())
                .setTitle("账单详情")
                .setView(R.layout.dialog_layout)
                .show();

        // 获取对话框中的视图组件
        ImageView ivCategoryIcon = mDialog.findViewById(R.id.iv_category_icon);
        TextView tvCategoryName = mDialog.findViewById(R.id.tv_category_name);
        TextView tvDate = mDialog.findViewById(R.id.tv_date);
        TextView tvFare = mDialog.findViewById(R.id.tv_fare);
        TextView tvNoteContent = mDialog.findViewById(R.id.tv_note_content);
        

//        EditText etDate = mDialog.findViewById(R.id.et_date);
        EditText etFare = mDialog.findViewById(R.id.et_fare);
        EditText etNoteContent = mDialog.findViewById(R.id.et_note_content);
        
        Button btnDelete = mDialog.findViewById(R.id.btn_delete);
        Button btnEdit = mDialog.findViewById(R.id.btn_edit);
        ImageButton btnConfirm = mDialog.findViewById(R.id.btn_confirm);
        ImageButton btnCancel = mDialog.findViewById(R.id.btn_cancel);
        LinearLayout llNoteCard = mDialog.findViewById(R.id.ll_note_card);

        // 设置数据
        if (ivCategoryIcon != null) {
            // 统一使用CategoryIconMapper获取图标
            int iconResId = CategoryIconMapper.getIconForCategory(categoryName);
            ivCategoryIcon.setImageResource(iconResId);
            ivCategoryIcon.setBackground(null); // 移除背景色，显示图标
        }
        if (tvCategoryName != null) tvCategoryName.setText(incomeType + "-" + categoryName);
        if (tvDate != null) tvDate.setText(date);
        if (tvFare != null) tvFare.setText("￥ " + String.format("%.2f", fare));
        
        // 设置EditText的初始值
//        if (etDate != null) etDate.setText(date);
        if (etFare != null) etFare.setText("￥" + String.format("%.2f", fare));
        
        // 处理备注显示
        String noteTitle = bill.getTitle();
        if (noteTitle != null && !noteTitle.trim().isEmpty() && !noteTitle.equals(categoryName)) {
            // 有备注且备注不等于分类名称时显示备注卡片
            if (llNoteCard != null) llNoteCard.setVisibility(View.VISIBLE);
            if (tvNoteContent != null) tvNoteContent.setText(noteTitle);
            if (etNoteContent != null) etNoteContent.setText(noteTitle);
        } else {
            // 没有备注或备注等于分类名称时隐藏备注卡片
            if (llNoteCard != null) llNoteCard.setVisibility(View.GONE);
        }
        if (btnDelete != null) btnDelete.setOnClickListener(v -> {
            showWarningDialog(bill);
        });
        
        if (btnEdit != null) btnEdit.setOnClickListener(v -> {
            // 进入修改模式
            Utils.enterEditMode(getContext(), mDialog, tvDate, tvFare, tvNoteContent,
                         etFare, etNoteContent,
                         btnEdit, btnDelete, btnConfirm, btnCancel);
        });
        
        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> {
            // 确认修改并更新数据库
            String currentDate = tvDate.getText().toString();
            String fareText = etFare.getText().toString();
            // 判空逻辑：如果包含货币符号则去掉，否则直接解析
            double currentFare = fareText.startsWith("￥") ? 
                Double.parseDouble(fareText.substring(1)) : 
                Double.parseDouble(fareText);
            String currentNoteContent = etNoteContent.getText().toString();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTime = timeFormat.format(new Date());
            
            Utils.updateBill(getContext(), bill, currentDate, currentFare, currentNoteContent, currentTime, new Utils.UpdateBillCallback() {
                @Override
                public void onUpdateSuccess(int rowsAffected) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (rowsAffected > 0) {
                                // 更新成功，刷新数据
                                refreshBillData();
                                // 刷新ReportFragment的图表数据
                                if (getActivity() instanceof MainActivity) {
                                    MainActivity mainActivity = (MainActivity) getActivity();
                                    ReportFragment reportFragment = mainActivity.getReportFragment();
                                    if (reportFragment != null) {
                                        reportFragment.refreshChartData();
                                    }
                                }
                            }
                        });
                    }
                }

                @Override
                public void onUpdateError(String error) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Log.e("ClassicModelFragment", "更新账单失败: " + error);
                        });
                    }
                }
            });
            
            Utils.exitEditMode(mDialog, tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent,
                        btnEdit, btnDelete, btnConfirm, btnCancel);
        });
        
        if (btnCancel != null) btnCancel.setOnClickListener(v -> {
            // 取消修改，恢复原始数据
//            if (etDate != null) etDate.setText(date);
            if (etFare != null) etFare.setText("￥" + String.format("%.2f", fare));
            if (etNoteContent != null) etNoteContent.setText(noteTitle != null ? noteTitle : "");
            
            // 更新TextView显示原始数据
            if (tvFare != null) tvFare.setText("￥ " + String.format("%.2f", fare));
            if (tvNoteContent != null) tvNoteContent.setText(noteTitle != null ? noteTitle : "");
            
            Utils.exitEditMode(mDialog,tvDate, tvFare, tvNoteContent,
                        etFare, etNoteContent,
                        btnEdit, btnDelete, btnConfirm, btnCancel);
        });
    }
    
    private void showWarningDialog(BillBean bill) {
        new PromptDialog.Builder(getContext())
                .setTitle("删除账单（此举不可逆）")
                .setPositiveButton("确定", (dialog, which) ->{
                    Utils.deleteBill(getContext(), bill, new Utils.DeleteBillCallback() {
                        @Override
                        public void onDeleteSuccess(int rowsDeleted) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (rowsDeleted > 0) {
                                        // 删除成功，刷新数据
                                        refreshBillData();
                                        // 刷新ReportFragment的图表数据
                                        if (getActivity() instanceof MainActivity) {
                                            MainActivity mainActivity = (MainActivity) getActivity();
                                            ReportFragment reportFragment = mainActivity.getReportFragment();
                                            if (reportFragment != null) {
                                                reportFragment.refreshChartData();
                                            }
                                        }
                                        // 关闭主对话框
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
                                getActivity().runOnUiThread(() -> {
                                    Log.e("ClassicModelFragment", "删除账单失败: " + error);
                                });
                            }
                        }
                    });
                    dialog.dismiss();
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }
    
    private void showLoginPrompt() {
        // 显示登录提示
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
        // 处理账单项点击事件
        processDialog(bill);
    }
}
