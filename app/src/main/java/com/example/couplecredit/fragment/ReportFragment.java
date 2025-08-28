package com.example.couplecredit.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.R;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.function.UserInfoManager;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import com.transsion.widgetslib.widget.OSSegmentedTab;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.Calendar;

public class ReportFragment extends Fragment {
    private int currentYear;
    private int currentMonth;
    private TextView tv_month_choose;
    private TextView tv_trend_title;
    private TextView tv_remainer;
    private TextView tv_total_amount;
    private TextView tv_filter_all, tv_filter_self, tv_filter_partner, tv_filter_shared;
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();
    private LineChart TrendChart;
    private LinearLayout TrendContainer;
    private int income_type;
    private String currentFilter = "all"; // 当前筛选状态
    private int currentUserId = -1;
    private Integer currentRelationshipId = null;
    private int currentUserRole = -1; // 1=邀请者, 2=被邀请者

    private OSSegmentedTab.OnTabSelectedListener onTabSelectedListener = new OSSegmentedTab.OnTabSelectedListener() {
        @Override
        public void onTabSelected(int position) {
            // 根据选中的标签页更新内容
            if (position == 0) {
                // 显示支出内容
                income_type = 0;
                tv_trend_title.setText("支出趋势");
                loadTrendData();
                getRemainer(new RemainingCallback() {
                @Override
                public void onResult(double remaining) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            tv_remainer.setText("结余：￥" + String.format("%.2f", remaining));
                        });
                    }
                }
            });
            } else if (position == 1) {
                // 显示收入内容
                income_type = 1;
                tv_trend_title.setText("收入趋势");
                loadTrendData();
            }
        }
    };
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_report, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 在这里初始化UI组件和设置监听器
        // 例如：图表、统计数据等
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1;

        Bundle args = getArguments();
        if (args != null) {
            String type = args.getString("type");
            int year = args.getInt("year");
            int month = args.getInt("month");
            // 使用这些数据
            currentYear = year;
            currentMonth = month;
        }
        tv_trend_title = view.findViewById(R.id.tv_trend_title);
        tv_month_choose = view.findViewById(R.id.tv_month_choose);
        tv_remainer = view.findViewById(R.id.tv_remainer);
        tv_total_amount = view.findViewById(R.id.tv_total_amount);
        
        // 初始化筛选按钮
        tv_filter_all = view.findViewById(R.id.tv_filter_all);
        tv_filter_self = view.findViewById(R.id.tv_filter_self);
        tv_filter_partner = view.findViewById(R.id.tv_filter_partner);
        tv_filter_shared = view.findViewById(R.id.tv_filter_shared);
        
        setupFilterButtons();
        // 设置初始选中状态
        selectFilter("all", tv_filter_all);
        tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        tv_month_choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.showDatePickerDialog(getActivity(), currentYear, currentMonth, (selectedYear, selectedMonth)-> {
                    tv_month_choose.setText(selectedYear + "年" + selectedMonth + "月 >");
                    currentYear = selectedYear;
                    currentMonth = selectedMonth;
                    loadTrendData();
                });
            }
        });
        segmentedTab = view.findViewById(R.id.segmented_tab);
        if (currentTabs.isEmpty()) {
            currentTabs.add("支出");
            currentTabs.add("收入");
        }
        setupSegmentedTab();
        
        // 初始化收入趋势图表
        TrendContainer = view.findViewById(R.id.ll_trend_container);
        TrendChart = view.findViewById(R.id.trend_chart);
        setupTrendChart();
        
        // 初始化用户信息
        initUserInfo();

    }
    
    private void initUserInfo() {
        // 获取用户基本信息
        currentUserId = UserInfoManager.getCurrentUserId(getContext());
        
        // 异步获取完整用户信息（包括关系ID和角色）
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                currentRelationshipId = relationshipId;
                
                // 如果有情侣关系，获取用户角色
                if (relationshipId != null) {
                    CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
                    coupleHelper.getUserRole(userId, new CoupleRelationshipHelper.UserRoleCallback() {
                        @Override
                        public void onRoleFound(int ownerId) {
                            currentUserRole = ownerId;
                        }
                        
                        @Override
                        public void onNoRelationshipFound() {
                            currentUserRole = 1; // 默认为1
                        }
                        
                        @Override
                        public void onError(String error) {
                            currentUserRole = 1; // 默认为1
                        }
                    });
                } else {
                    currentUserRole = 1; // 无情侣关系时默认为1
                }
            }
            
            @Override
            public void onError(String error) {
                currentRelationshipId = null;
                currentUserRole = 1;
            }
        });
    }
    
    // 公共方法：更新月份显示
    public void updateDisplay(int year, int month, String type) {
        currentYear = year;
        currentMonth = month;
        if (tv_month_choose != null) {
            tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        }
        loadTrendData();
    }
    
    private void updateTotalAmount(float totalAmount) {
        if (tv_total_amount != null) {
            String typeText = (income_type == 0) ? "支出" : "收入";
            tv_total_amount.setText(typeText + ":￥" + String.format("%.2f", totalAmount));
        }
    }
    
    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);
        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(onTabSelectedListener);
    }
    
    private void setupFilterButtons() {
        // 设置点击监听器
        tv_filter_all.setOnClickListener(v -> selectFilter("all", tv_filter_all));
        tv_filter_self.setOnClickListener(v -> selectFilter("self", tv_filter_self));
        tv_filter_partner.setOnClickListener(v -> selectFilter("partner", tv_filter_partner));
        tv_filter_shared.setOnClickListener(v -> selectFilter("shared", tv_filter_shared));
    }
    
    private void selectFilter(String filterType, TextView selectedView) {
        // 重置所有按钮样式
        resetFilterButtons();
        
        // 根据按钮位置设置选中样式
        if (selectedView == tv_filter_all) {
            selectedView.setBackgroundResource(R.drawable.filter_button_left_selected);
        } else if (selectedView == tv_filter_self) {
            selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
        } else if (selectedView == tv_filter_partner) {
            selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
        } else if (selectedView == tv_filter_shared) {
            selectedView.setBackgroundResource(R.drawable.filter_button_right_selected);
        }
        selectedView.setTextColor(getResources().getColor(android.R.color.white));
        selectedView.setElevation(8f); // 提升选中按钮到最上层
        
        // 保存当前筛选状态
        currentFilter = filterType;
        
        // 重新加载数据
        loadTrendData();
    }
    
    private void resetFilterButtons() {
        int defaultTextColor = getResources().getColor(android.R.color.darker_gray);
        
        tv_filter_all.setBackgroundResource(R.drawable.filter_button_left_unselected);
        tv_filter_all.setTextColor(defaultTextColor);
        tv_filter_all.setElevation(2f);
        tv_filter_self.setBackgroundResource(R.drawable.filter_button_middle_unselected);
        tv_filter_self.setTextColor(defaultTextColor);
        tv_filter_self.setElevation(1f);
        tv_filter_partner.setBackgroundResource(R.drawable.filter_button_middle_unselected);
        tv_filter_partner.setTextColor(defaultTextColor);
        tv_filter_partner.setElevation(1f);
        tv_filter_shared.setBackgroundResource(R.drawable.filter_button_right_unselected);
        tv_filter_shared.setTextColor(defaultTextColor);
        tv_filter_shared.setElevation(1f);
    }
    
    private void setupTrendChart() {
        // 配置图表样式
        TrendChart.getDescription().setEnabled(false);
        TrendChart.setTouchEnabled(true);
        TrendChart.setDragEnabled(true);
        TrendChart.setScaleEnabled(true);
        TrendChart.setPinchZoom(true);
        
        // 配置X轴
        XAxis xAxis = TrendChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setAxisMinimum(1f);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value) + "日";
            }
        });
        
        // 配置Y轴
        YAxis leftAxis = TrendChart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        TrendChart.getAxisRight().setEnabled(false);
    }
    
    private void loadTrendData() {
        // 首先检查用户是否已登录
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            // 用户未登录，显示空数据
            List<com.github.mikephil.charting.data.Entry> entries = new ArrayList<>();
            Calendar calendar = Calendar.getInstance();
            calendar.set(currentYear, currentMonth - 1, 1);
            int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
            
            // 为每一天都添加0值数据点
            for (int day = 1; day <= daysInMonth; day++) {
                entries.add(new Entry(day, 0));
            }
            updateChart(entries);
            return;
        }
        
        // 获取当前用户信息和relationship状态
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 优化：一次查询获取整月数据，避免多次数据库连接
                loadMonthlyTrendData(userId, relationshipId);
            }
            
            @Override
            public void onError(String error) {
                // 获取用户信息失败，显示空数据
                List<com.github.mikephil.charting.data.Entry> entries = new ArrayList<>();
                Calendar calendar = Calendar.getInstance();
                calendar.set(currentYear, currentMonth - 1, 1);
                int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
                
                for (int day = 1; day <= daysInMonth; day++) {
                    entries.add(new Entry(day, 0));
                }
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateChart(entries);
                        updateTotalAmount(0);
                    });
                }
            }
        });
    }
    
    // 优化后的方法：一次查询获取整月趋势数据
    private void loadMonthlyTrendData(int userId, Integer relationshipId) {
        if (getContext() == null) {
            return;
        }
        
        BillDatabaseHelper billHelper = new BillDatabaseHelper(getContext());
        
        // 构建月份筛选条件
        String monthPattern = String.format("%04d-%02d-%%", currentYear, currentMonth);
        String monthSelection = "date LIKE ? AND income_type = ?";
        String[] monthSelectionArgs = new String[]{monthPattern, String.valueOf(income_type)};
        
        billHelper.queryBillsWithUserFilter(userId, relationshipId, monthSelection, monthSelectionArgs, new BillDatabaseHelper.QueryCallback() {
            @Override
            public void onSuccess(List<Map<String, Object>> bills) {
                // 获取当前月份的天数
                Calendar calendar = Calendar.getInstance();
                calendar.set(currentYear, currentMonth - 1, 1);
                int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
                
                // 初始化每日金额数组
                float[] dailyAmounts = new float[daysInMonth + 1]; // 索引0不用，从1开始
                
                // 按日期分组统计金额
                for (Map<String, Object> bill : bills) {
                    // 根据筛选条件过滤账单
                    if (!shouldIncludeBill(bill)) {
                        continue;
                    }
                    
                    String dateStr = (String) bill.get("date");
                    Double amount = (Double) bill.get("amount");
                    
                    if (dateStr != null && amount != null) {
                        try {
                            // 提取日期中的天数
                            String[] dateParts = dateStr.split("-");
                            if (dateParts.length == 3) {
                                int day = Integer.parseInt(dateParts[2]);
                                if (day >= 1 && day <= daysInMonth) {
                                    dailyAmounts[day] += amount.floatValue();
                                }
                            }
                        } catch (NumberFormatException e) {
                            android.util.Log.w("ReportFragment", "日期解析失败: " + dateStr);
                        }
                    }
                }
                
                // 构建图表数据
                List<com.github.mikephil.charting.data.Entry> entries = new ArrayList<>();
                float totalAmount = 0;
                for (int day = 1; day <= daysInMonth; day++) {
                    entries.add(new Entry(day, dailyAmounts[day]));
                    totalAmount += dailyAmounts[day];
                }
                
                final float finalTotalAmount = totalAmount;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        updateChart(entries);
                        updateTotalAmount(finalTotalAmount);
                    });
                }
            }
            
            @Override
            public void onError(String error) {
                android.util.Log.e("ReportFragment", "查询月度趋势数据失败: " + error);
                // 显示空数据
                Calendar calendar = Calendar.getInstance();
                calendar.set(currentYear, currentMonth - 1, 1);
                int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
                
                List<com.github.mikephil.charting.data.Entry> entries = new ArrayList<>();
                for (int day = 1; day <= daysInMonth; day++) {
                    entries.add(new Entry(day, 0));
                }
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateChart(entries));
                }
            }
        });
    }
    
    private void updateChart(List<com.github.mikephil.charting.data.Entry> entries) {
        
        // 现在总是有数据点（包括0值），所以不需要检查空数据
        LineDataSet dataSet = new LineDataSet(entries, "金额");
        if(income_type == 1){
            dataSet.setLabel("收入金额");
            dataSet.setColor(0xFFF44336);
            dataSet.setCircleColor(0xFFF44336);
            dataSet.setFillColor(0xFFF44336);
        }else{
//            LineDataSet dataSet = new LineDataSet(entries, "支出趋势");
            dataSet.setLabel("支出金额");
            dataSet.setColor(0xFF4CAF50); // 绿色
            dataSet.setCircleColor(0xFF4CAF50);
            dataSet.setFillColor(0xFF4CAF50);
        }
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawCircleHole(false);
        dataSet.setDrawFilled(false);
        
        // 为非零金额显示数值标签
        dataSet.setDrawValues(true);
        dataSet.setValueTextSize(10f);
        dataSet.setValueTextColor(0xFF333333);
        
        // 自定义值格式化器，只显示非零值
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                if (value == 0) {
                    return ""; // 零值不显示
                }
                return String.format("%.0f", value); // 显示整数金额
            }
        });
        
        LineData lineData = new LineData(dataSet);
        TrendChart.setData(lineData);
        // 添加从下到上的动画效果，持续时间300毫秒
        TrendChart.animateY(300);
        TrendChart.invalidate(); // 刷新图表
    }
    private void getRemainer(RemainingCallback callback){
        // 首先检查用户是否已登录
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            callback.onResult(0.0);
            return;
        }
        
        // 获取当前用户信息
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 优化：一次查询获取整月数据，避免多次数据库连接
                getMonthlyRemaining(userId, relationshipId, callback);
            }
            
            @Override
            public void onError(String error) {
                callback.onResult(0.0);
            }
        });
    }
    
    // 优化后的方法：一次查询获取整月数据
    private void getMonthlyRemaining(int userId, Integer relationshipId, RemainingCallback callback) {
        if (getContext() == null) {
            callback.onResult(0.0);
            return;
        }
        
        BillDatabaseHelper billHelper = new BillDatabaseHelper(getContext());
        
        // 构建月份筛选条件
        String monthPattern = String.format("%04d-%02d-%%", currentYear, currentMonth);
        String monthSelection = "date LIKE ?";
        String[] monthSelectionArgs = new String[]{monthPattern};
        
        billHelper.queryBillsWithUserFilter(userId, relationshipId, monthSelection, monthSelectionArgs, new BillDatabaseHelper.QueryCallback() {
            @Override
            public void onSuccess(List<Map<String, Object>> bills) {
                double totalIncome = 0;
                double totalExpense = 0;
                
                for (Map<String, Object> bill : bills) {
                    Integer incomeType = (Integer) bill.get("income_type");
                    Double amount = (Double) bill.get("amount");
                    
                    if (incomeType != null && amount != null) {
                        if (incomeType == 1) {
                            totalIncome += amount;
                        } else if (incomeType == 0) {
                            totalExpense += amount;
                        }
                    }
                }
                
                callback.onResult(totalIncome - totalExpense);
            }
            
            @Override
            public void onError(String error) {
                android.util.Log.e("ReportFragment", "查询月度账单失败: " + error);
                callback.onResult(0.0);
            }
        });
    }
    
    // 回调接口
    private interface RemainingCallback {
        void onResult(double remaining);
    }
    
    /**
     * 刷新图表数据
     * 在账单数据发生变更时调用
     */
    public void refreshChartData() {
        if (getContext() != null) {
            // 重新加载趋势数据
            loadTrendData();
            // 重新加载结余数据（如果当前显示的是支出）
            if (income_type == 0) {
                getRemainer(new RemainingCallback() {
                    @Override
                    public void onResult(double remaining) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                tv_remainer.setText("结余：￥" + String.format("%.2f", remaining));
                            });
                        }
                    }
                });
            }
        }
    }
    
    // 根据当前筛选条件判断是否包含该账单
    private boolean shouldIncludeBill(Map<String, Object> bill) {
        if ("all".equals(currentFilter)) {
            return true;
        }
        
        Integer owner = (Integer) bill.get("owner");
        if (owner == null) {
            return false;
        }
        
        // 参考BillAdapter中setOwnerText的逻辑
        if (currentRelationshipId == null) {
            // 无情侣关系时，所有账单都是"自己"的
            return "self".equals(currentFilter);
        }
        
        String billType;
        if (owner == 3) {
            billType = "shared"; // 共同账单
        } else if (owner == 1) {
            // 邀请者的账单
            billType = (currentUserRole == 1) ? "self" : "partner";
        } else if (owner == 2) {
            // 被邀请者的账单
            billType = (currentUserRole == 2) ? "self" : "partner";
        } else {
            return false;
        }
        
        return billType.equals(currentFilter);
    }
}