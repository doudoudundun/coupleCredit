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
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();
    private LineChart TrendChart;
    private LinearLayout TrendContainer;
    private int income_type;

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


    private void setupSegmentedTab() {
        segmentedTab.addTabs(currentTabs);
        // 设置选中监听
        segmentedTab.setOnTabSelectedListener(onTabSelectedListener);
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
                    getActivity().runOnUiThread(() -> updateChart(entries));
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
                for (int day = 1; day <= daysInMonth; day++) {
                    entries.add(new Entry(day, dailyAmounts[day]));
                }
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateChart(entries));
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

    
}