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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.R;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.adapter.ReportAdapter;
import com.example.couplecredit.adapter.CategoryDetailAdapter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import android.database.Cursor;
import com.transsion.widgetslib.widget.OSSegmentedTab;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.util.Calendar;
import java.util.Random;

public class ReportFragment extends Fragment {
    private int currentYear;
    private int currentMonth;
    private TextView tv_month_choose;
    private TextView tv_trend_title;
    private TextView tv_remainer;
    private TextView tv_total_amount;
    private TextView tv_filter_all, tv_filter_self, tv_filter_partner, tv_filter_shared;
    private TextView tv_filter_all_pie, tv_filter_self_pie, tv_filter_partner_pie, tv_filter_shared_pie;
    private OSSegmentedTab segmentedTab;
    private List<String> currentTabs = new ArrayList<>();
    private LineChart TrendChart;
    private LinearLayout TrendContainer;
    private RecyclerView rvReportContent;
    private ReportAdapter reportAdapter;
    private PieChart currentPieChart;
    private TextView currentTitleView;
    private RecyclerView rvCategoryList;
    private CategoryDetailAdapter categoryDetailAdapter;
    private int income_type;
    private String currentChartFilter = "all"; // 折线图当前筛选状态
    private String currentPieFilter = "all"; // 饼图当前筛选状态
    
    // 缓存当月账单数据
    private List<Map<String, Object>> monthlyBills = new ArrayList<>();
    
    // 月度数据加载回调接口
    private interface MonthlyDataCallback {
        void onDataLoaded();
        void onError(String error);
    }
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
                if (tv_trend_title != null) {
                    tv_trend_title.setText("支出趋势");
                }
                loadTrendData();
                // 刷新饼状图和类目列表
                refreshPieChart();
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
                if (tv_trend_title != null) {
                    tv_trend_title.setText("收入趋势");
                }
                loadTrendData();
                // 刷新饼状图和类目列表
                refreshPieChart();
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
        tv_month_choose = view.findViewById(R.id.tv_month_choose);
        tv_remainer = view.findViewById(R.id.tv_remainer);
        
        // 初始化RecyclerView
        rvReportContent = view.findViewById(R.id.rv_report_content);
        rvReportContent.setLayoutManager(new LinearLayoutManager(getContext()));
        
        // 创建Adapter并设置回调
        reportAdapter = new ReportAdapter(new ReportAdapter.ChartViewHolderCallback() {
            @Override
            public void onChartViewHolderCreated(ReportAdapter.ChartViewHolder holder) {
                // 保存图表相关的View引用
                tv_trend_title = holder.tvTrendTitle;
                tv_total_amount = holder.tvTotalAmount;
                tv_filter_all = holder.tvFilterAll;
                tv_filter_self = holder.tvFilterSelf;
                tv_filter_partner = holder.tvFilterPartner;
                tv_filter_shared = holder.tvFilterShared;
                TrendChart = holder.trendChart;
                TrendContainer = holder.llTrendContainer;
                
                // 设置筛选按钮和图表
                setupFilterButtons();
                setupTrendChart();
                selectChartFilter("all", tv_filter_all);
            }
        }, new ReportAdapter.AdditionalViewHolderCallback() {
            @Override
            public void onAdditionalViewHolderCreated(ReportAdapter.AdditionalViewHolder holder) {
                // 保存引用
                currentPieChart = holder.pieChartCategory;
                currentTitleView = holder.tvAdditionalTitle;
                rvCategoryList = holder.rvCategoryList;
                tv_filter_all_pie = holder.tvFilterAllPie;
                tv_filter_self_pie = holder.tvFilterSelfPie;
                tv_filter_partner_pie = holder.tvFilterPartnerPie;
                tv_filter_shared_pie = holder.tvFilterSharedPie;
                
                // 设置饼状图filter按钮
                setupPieFilterButtons();
                selectPieFilter("all", tv_filter_all_pie);
                
                // 设置饼状图
                setupPieChart(holder.pieChartCategory);
                
                // 设置分类列表
                setupCategoryList();
                
                // 加载饼状图数据
                loadCategoryData(holder.pieChartCategory, holder.tvAdditionalTitle);
            }
        });
        rvReportContent.setAdapter(reportAdapter);
        tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        tv_month_choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.showDatePickerDialog(getActivity(), currentYear, currentMonth, (selectedYear, selectedMonth)-> {
                    tv_month_choose.setText(selectedYear + "年" + selectedMonth + "月 >");
                    currentYear = selectedYear;
                    currentMonth = selectedMonth;
                    // 更新CategoryDetailAdapter的年月信息
                    if (categoryDetailAdapter != null) {
                        categoryDetailAdapter.updateYearMonth(currentYear, currentMonth);
                        android.util.Log.d("ReportFragment", "月份选择器更新CategoryDetailAdapter年月信息: " + currentYear + "年" + currentMonth + "月");
                    }
                    loadTrendData();
                    refreshPieChart();
                });
            }
        });
        segmentedTab = view.findViewById(R.id.segmented_tab);
        if (currentTabs.isEmpty()) {
            currentTabs.add("支出");
            currentTabs.add("收入");
        }
        setupSegmentedTab();
        
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
        // 更新CategoryDetailAdapter的年月信息
        if (categoryDetailAdapter != null) {
            categoryDetailAdapter.updateYearMonth(currentYear, currentMonth);
            android.util.Log.d("ReportFragment", "更新CategoryDetailAdapter年月信息: " + currentYear + "年" + currentMonth + "月");
        } else {
            android.util.Log.w("ReportFragment", "categoryDetailAdapter为null，无法更新年月信息");
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
        if (tv_filter_all != null) {
            tv_filter_all.setOnClickListener(v -> {
                selectChartFilter("all", tv_filter_all);
            });
        }
        if (tv_filter_self != null) {
            tv_filter_self.setOnClickListener(v -> {
                selectChartFilter("self", tv_filter_self);
            });
        }
        if (tv_filter_partner != null) {
            tv_filter_partner.setOnClickListener(v -> {
                selectChartFilter("partner", tv_filter_partner);
            });
        }
        if (tv_filter_shared != null) {
            tv_filter_shared.setOnClickListener(v -> {
                selectChartFilter("shared", tv_filter_shared);
            });
        }
    }
    
    private void selectChartFilter(String filterType, TextView selectedView) {
        // 重置所有按钮样式
        resetChartFilterButtons();
        
        if (selectedView != null) {
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
        }
        
        // 保存当前筛选状态
        currentChartFilter = filterType;
        
        // 重新加载数据
        loadTrendData();
    }
    
    private void resetChartFilterButtons() {
        int defaultTextColor = getResources().getColor(android.R.color.darker_gray);
        
        if (tv_filter_all != null) {
            tv_filter_all.setBackgroundResource(R.drawable.filter_button_left_unselected);
            tv_filter_all.setTextColor(defaultTextColor);
            tv_filter_all.setElevation(2f);
        }
        if (tv_filter_self != null) {
            tv_filter_self.setBackgroundResource(R.drawable.filter_button_middle_unselected);
            tv_filter_self.setTextColor(defaultTextColor);
            tv_filter_self.setElevation(1f);
        }
        if (tv_filter_partner != null) {
            tv_filter_partner.setBackgroundResource(R.drawable.filter_button_middle_unselected);
            tv_filter_partner.setTextColor(defaultTextColor);
            tv_filter_partner.setElevation(1f);
        }
        if (tv_filter_shared != null) {
            tv_filter_shared.setBackgroundResource(R.drawable.filter_button_right_unselected);
            tv_filter_shared.setTextColor(defaultTextColor);
            tv_filter_shared.setElevation(1f);
        }
    }
    
    private void setupTrendChart() {
        if (TrendChart == null) {
            return;
        }
        
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
                // 统一加载月度数据，然后更新两个图表
                loadMonthlyBillsData(userId, relationshipId, new MonthlyDataCallback() {
                    @Override
                    public void onDataLoaded() {
                        // 数据加载完成后，更新折线图
                        loadMonthlyTrendData(userId, relationshipId);
                        // 饼状图有自己独立的过滤器和刷新机制，不需要在这里更新
                    }
                    
                    @Override
                    public void onError(String error) {
                        handleTrendDataError(error);
                    }
                });
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
    
    // 基于缓存数据的折线图数据加载
    private void loadMonthlyTrendData(int userId, Integer relationshipId) {
        // 获取当前月份的天数
        Calendar calendar = Calendar.getInstance();
        calendar.set(currentYear, currentMonth - 1, 1);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        
        // 初始化每日金额数组
        float[] dailyAmounts = new float[daysInMonth + 1]; // 索引0不用，从1开始
        
        // 按日期分组统计金额
        for (Map<String, Object> bill : monthlyBills) {
            // 根据筛选条件过滤账单
            if (!shouldIncludeBillWithFilter(bill, currentChartFilter)) {
                continue;
            }
            
            String dateStr = (String) bill.get("date");
            Double amount = (Double) bill.get("amount");
            
            if (dateStr != null && amount != null) {
                try {
                    // 解析日期，获取天数
                    String[] dateParts = dateStr.split("-");
                    if (dateParts.length >= 3) {
                        int day = Integer.parseInt(dateParts[2]);
                        if (day >= 1 && day <= daysInMonth) {
                            dailyAmounts[day] += amount.floatValue();
                        }
                    }
                } catch (NumberFormatException e) {
                    // 忽略格式错误的日期
                }
            }
        }
        
        // 创建图表数据
        List<Entry> entries = new ArrayList<>();
        float totalAmount = 0;
        
        for (int day = 1; day <= daysInMonth; day++) {
            entries.add(new Entry(day, dailyAmounts[day]));
            totalAmount += dailyAmounts[day];
        }
        
        // 声明为final变量供lambda使用
        final float finalTotalAmount = totalAmount;
        
        // 更新UI
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                updateChart(entries);
                updateTotalAmount(finalTotalAmount);
            });
        }
    }
    
    // 旧的错误处理方法保留
    private void handleTrendDataError(String error) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 显示空图表
                updateChart(new ArrayList<>());
                updateTotalAmount(0);
            });
        }
    }

    private void updateChart(List<com.github.mikephil.charting.data.Entry> entries) {
        if (TrendChart == null) {
            return;
        }
        
        // 计算总金额并更新显示
        double totalAmount = 0;
        for (Entry entry : entries) {
            totalAmount += entry.getY();
        }
        if (tv_total_amount != null) {
            tv_total_amount.setText("总计：￥" + String.format("%.2f", totalAmount));
        }
        
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
        // 添加从下到上的动画效果，持续时间500毫秒
        TrendChart.animateY(500);
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
            // 重新加载饼状图数据
            refreshPieChart();
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
        return shouldIncludeBillWithFilter(bill, currentChartFilter);
    }
    
    // 根据指定筛选条件判断是否包含该账单
    private boolean shouldIncludeBillWithFilter(Map<String, Object> bill, String filterType) {
        if ("all".equals(filterType)) {
            return true;
        }
        
        Integer owner = (Integer) bill.get("owner");
        if (owner == null) {
            return false;
        }
        
        // 参考BillAdapter中setOwnerText的逻辑
        if (currentRelationshipId == null) {
            // 无情侣关系时，所有账单都是"自己"的
            return "self".equals(filterType);
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
        
        return billType.equals(filterType);
    }
    
    // 设置饼状图样式
    private void setupPieChart(PieChart pieChart) {
        if (pieChart == null) return;
        
        // 设置基本属性
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setExtraOffsets(30, 0, 30, 0); // 左，上，右，下边距，单独缩短上边距
        
        // 设置饼状图绘制半径为80dp，加上外边距
        float density = getResources().getDisplayMetrics().density;
        int radiusInPx = (int) (145 * density);
        int offsetInPx = (int) (30 * density); // 外边距30dp
        int totalSize = radiusInPx * 2 + offsetInPx * 2; // 直径 + 两边外边距
        pieChart.setMinimumWidth(totalSize);
        pieChart.setMinimumHeight(totalSize);
        pieChart.getLayoutParams().width = totalSize;
        pieChart.getLayoutParams().height = totalSize;
        
        // XML布局已经设置了居中，无需手动调整边距
        
        // 设置拖拽和缩放
        pieChart.setDragDecelerationFrictionCoef(0.95f);
        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleColor(android.graphics.Color.WHITE);
        pieChart.setHoleRadius(58f);
        pieChart.setTransparentCircleRadius(61f);
        
        // 设置中心文字
        pieChart.setDrawCenterText(true);
        pieChart.setCenterText("类目占比");
        pieChart.setCenterTextSize(24f);
        pieChart.setCenterTextColor(android.graphics.Color.parseColor("#333333"));
        pieChart.setCenterTextTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        
        // 设置旋转 - 从0°开始
        pieChart.setRotationAngle(0);
        pieChart.setRotationEnabled(true);
        pieChart.setHighlightPerTapEnabled(true);
        
        // 隐藏图例
        pieChart.getLegend().setEnabled(false);
        
        // 设置标签显示在外部
        pieChart.setDrawEntryLabels(true);
        pieChart.setEntryLabelColor(android.graphics.Color.parseColor("#333333"));
        pieChart.setEntryLabelTextSize(12f);
    }
    
    // 加载类别数据
    private void loadCategoryData(PieChart pieChart, TextView titleView) {
        if (getContext() == null || pieChart == null) {
            return;
        }
        
        // 设置当前饼状图实例
        currentPieChart = pieChart;
        currentTitleView = titleView;
        
        // 获取当前用户信息
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 先加载月度账单数据，然后加载类别数据
                loadMonthlyBillsData(userId, relationshipId, new MonthlyDataCallback() {
                    @Override
                    public void onDataLoaded() {
                        loadMonthlyCategoryData(userId, relationshipId, pieChart, titleView);
                    }
                    
                    @Override
                    public void onError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                setupEmptyPieChart(pieChart);
                            });
                        }
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                // 显示空数据
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        setupEmptyPieChart(pieChart);
                    });
                }
            }
        });
    }
    
    // 统一加载当月账单数据
    private void loadMonthlyBillsData(int userId, Integer relationshipId, MonthlyDataCallback callback) {
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
                monthlyBills.clear();
                monthlyBills.addAll(bills);
                
                // 设置当前用户信息
                currentUserId = userId;
                currentRelationshipId = relationshipId;
                
                // 数据加载完成，执行回调
                if (callback != null) {
                    callback.onDataLoaded();
                }
            }
            
            @Override
            public void onError(String error) {
                monthlyBills.clear();
                currentUserId = userId;
                currentRelationshipId = relationshipId;
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }
    
    // 基于缓存数据加载月度类别数据
    private void loadMonthlyCategoryData(int userId, Integer relationshipId, PieChart pieChart, TextView titleView) {
        // 统计各类别金额和数量
        Map<String, Float> categoryAmounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        float totalAmount = 0;
        
        for (Map<String, Object> bill : monthlyBills) {
            // 根据筛选条件过滤账单
            if (!shouldIncludeBillWithFilter(bill, currentPieFilter)) {
                continue;
            }
            
            String category = (String) bill.get("type");
            Double amount = (Double) bill.get("amount");
            
            if (category != null && amount != null) {
                float amountFloat = amount.floatValue();
                categoryAmounts.put(category, categoryAmounts.getOrDefault(category, 0f) + amountFloat);
                categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
                totalAmount += amountFloat;
            }
        }
        
        // 声明为final变量供lambda使用
        final Map<String, Float> finalCategoryAmounts = categoryAmounts;
        final Map<String, Integer> finalCategoryCounts = categoryCounts;
        final float finalTotalAmount = totalAmount;
        
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                Map<String, Integer> categoryColors = updatePieChart(pieChart, finalCategoryAmounts, finalTotalAmount, titleView);
                updateCategoryList(finalCategoryAmounts, finalCategoryCounts, finalTotalAmount, categoryColors);
            });
        }
    }
    
    // 更新饼状图数据
    private Map<String, Integer> updatePieChart(PieChart pieChart, Map<String, Float> categoryAmounts, float totalAmount, TextView titleView) {
        Map<String, Integer> categoryColors = new HashMap<>();
        
        if (pieChart == null) return categoryColors;
        
        if (categoryAmounts.isEmpty() || totalAmount == 0) {
            setupEmptyPieChart(pieChart);
            return categoryColors;
        }
        
        // 转换为列表并按金额排序
        List<Map.Entry<String, Float>> sortedEntries = new ArrayList<>(categoryAmounts.entrySet());
        sortedEntries.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        
        // 创建饼状图数据
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        
        // 预定义颜色
        int[] pieColors = {
            android.graphics.Color.parseColor("#FF6B6B"),
            android.graphics.Color.parseColor("#4ECDC4"),
            android.graphics.Color.parseColor("#45B7D1"),
            android.graphics.Color.parseColor("#96CEB4"),
            android.graphics.Color.parseColor("#CCCCCC") // 其他类别的颜色
        };
        
        // 只显示前四大类别，其他归为"其他"
        float othersAmount = 0f;
        int maxCategories = 4;
        
        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, Float> entry = sortedEntries.get(i);
            if (i < maxCategories) {
                float percentage = (entry.getValue() / totalAmount) * 100;
                entries.add(new PieEntry(percentage, entry.getKey()));
                colors.add(pieColors[i]);
                categoryColors.put(entry.getKey(), pieColors[i]);
            } else {
                othersAmount += entry.getValue();
            }
        }
        
        // 如果有其他类别，添加"其他"项
        if (othersAmount > 0) {
            float othersPercentage = (othersAmount / totalAmount) * 100;
            entries.add(new PieEntry(othersPercentage, "其他"));
            int randomColor = generateRandomColor(); // 使用随机颜色
            colors.add(randomColor);
            categoryColors.put("其他", randomColor);
        }
        
        // 创建数据集
        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(colors);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(android.graphics.Color.parseColor("#333333"));
        dataSet.setValueFormatter(new PercentFormatter(pieChart));
        dataSet.setSliceSpace(2f);
        dataSet.setSelectionShift(5f);
        
        // 设置标签位置在外部，用虚线连接
        dataSet.setValueLinePart1OffsetPercentage(80f); // 第一段线的偏移
        dataSet.setValueLinePart1Length(0.3f); // 第一段线的长度
        dataSet.setValueLinePart2Length(0.4f); // 第二段线的长度
        dataSet.setValueLineColor(android.graphics.Color.parseColor("#CCCCCC")); // 连接线颜色
        dataSet.setValueLineWidth(1f); // 连接线宽度
        dataSet.setUsingSliceColorAsValueLineColor(false); // 不使用扇形颜色作为连接线颜色
        dataSet.setYValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE); // 标签位置在外部
        dataSet.setXValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE); // 标签位置在外部
        
        // 创建数据
        PieData data = new PieData(dataSet);
        pieChart.setData(data);
        
        // 设置中心文字为总金额
        String formattedAmount = "￥" + String.format("%.2f", totalAmount);
        pieChart.setCenterText(formattedAmount);
        pieChart.setCenterTextTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        
        // 添加从头加载的动画效果
        pieChart.animateY(800); // Y轴动画，持续800毫秒
        pieChart.invalidate();
        
        // 更新标题
        if (titleView != null) {
            String typeText = (income_type == 0) ? "支出" : "收入";
            titleView.setText(typeText + "类目占比");
        }
        
        return categoryColors;
    }
    
    // 设置空饼状图
    private void setupEmptyPieChart(PieChart pieChart) {
        if (pieChart == null) return;
        
        List<PieEntry> entries = new ArrayList<>();
        entries.add(new PieEntry(100f, "暂无数据"));
        
        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColor(android.graphics.Color.parseColor("#E0E0E0"));
        dataSet.setValueTextSize(14f);
        dataSet.setValueTextColor(android.graphics.Color.parseColor("#999999"));
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return "";
            }
        });
        
        PieData data = new PieData(dataSet);
        pieChart.setData(data);
        
        // 设置中心文字为0金额
        String formattedAmount = "￥" + String.format("%.2f", 0f);
        pieChart.setCenterText(formattedAmount);
        pieChart.setCenterTextTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        
        // 添加动画效果
        pieChart.animateY(800);
        pieChart.invalidate();
    }
    
    // 设置分类列表
    private void setupCategoryList() {
        if (rvCategoryList != null) {
            categoryDetailAdapter = new CategoryDetailAdapter(getContext(), new ArrayList<>(), currentYear, currentMonth);
            rvCategoryList.setLayoutManager(new LinearLayoutManager(getContext()));
            rvCategoryList.setAdapter(categoryDetailAdapter);
        }
    }
    
    private void updateCategoryList(Map<String, Float> categoryAmounts, Map<String, Integer> categoryCounts, float totalAmount, Map<String, Integer> categoryColors) {
        if (categoryDetailAdapter == null) {
            return;
        }
        
        List<CategoryDetailAdapter.CategoryDetail> categoryDetails = new ArrayList<>();
        
        // 如果有数据，则添加到列表中
        if (!categoryAmounts.isEmpty()) {
            for (Map.Entry<String, Float> entry : categoryAmounts.entrySet()) {
                String category = entry.getKey();
                float amount = entry.getValue();
                int count = categoryCounts.getOrDefault(category, 0);
                float percentage = totalAmount > 0 ? (amount / totalAmount) * 100 : 0;
                
                // 如果没有颜色映射，生成随机颜色
                int color;
                if (categoryColors.containsKey(category)) {
                    color = categoryColors.get(category);
                } else {
                    color = generateRandomColor();
                }
                
                categoryDetails.add(new CategoryDetailAdapter.CategoryDetail(
                    category, count, amount, percentage, color
                ));
            }
            
            // 按金额降序排序
            categoryDetails.sort((a, b) -> Float.compare(b.getAmount(), a.getAmount()));
        }
        
        // 无论有没有数据都更新适配器，没有数据时传入空列表清空显示
        categoryDetailAdapter.updateData(categoryDetails);
    }
    
    // 刷新饼状图
    private void refreshPieChart() {
        if (currentPieChart != null && currentTitleView != null) {
            loadCategoryData(currentPieChart, currentTitleView);
        }
    }
    
    // 设置饼状图filter按钮
    private void setupPieFilterButtons() {
        if (tv_filter_all_pie != null) {
            tv_filter_all_pie.setOnClickListener(v -> {
                selectPieFilter("all", tv_filter_all_pie);
                refreshPieChart();
            });
        }
        
        if (tv_filter_self_pie != null) {
            tv_filter_self_pie.setOnClickListener(v -> {
                selectPieFilter("self", tv_filter_self_pie);
                refreshPieChart();
            });
        }
        
        if (tv_filter_partner_pie != null) {
            tv_filter_partner_pie.setOnClickListener(v -> {
                selectPieFilter("partner", tv_filter_partner_pie);
                refreshPieChart();
            });
        }
        
        if (tv_filter_shared_pie != null) {
            tv_filter_shared_pie.setOnClickListener(v -> {
                selectPieFilter("shared", tv_filter_shared_pie);
                refreshPieChart();
            });
        }
    }
    
    private void selectPieFilter(String filterType, TextView selectedView) {
        // 重置所有按钮样式
        resetPieFilterButtons();
        
        if (selectedView != null) {
            if (selectedView == tv_filter_all_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_left_selected);
            } else if (selectedView == tv_filter_self_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
            } else if (selectedView == tv_filter_partner_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
            } else if (selectedView == tv_filter_shared_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_right_selected);
            }
            selectedView.setTextColor(getResources().getColor(android.R.color.white));
            selectedView.setElevation(8f); // 提升选中按钮到最上层
        }
        
        // 保存当前筛选状态
        currentPieFilter = filterType;
    }
    
    private void resetPieFilterButtons() {
        int defaultTextColor = getResources().getColor(android.R.color.darker_gray);
        
        if (tv_filter_all_pie != null) {
            tv_filter_all_pie.setBackgroundResource(R.drawable.filter_button_left_unselected);
            tv_filter_all_pie.setTextColor(defaultTextColor);
            tv_filter_all_pie.setElevation(2f);
        }
        if (tv_filter_self_pie != null) {
            tv_filter_self_pie.setBackgroundResource(R.drawable.filter_button_middle_unselected);
            tv_filter_self_pie.setTextColor(defaultTextColor);
            tv_filter_self_pie.setElevation(1f);
        }
        if (tv_filter_partner_pie != null) {
            tv_filter_partner_pie.setBackgroundResource(R.drawable.filter_button_middle_unselected);
            tv_filter_partner_pie.setTextColor(defaultTextColor);
            tv_filter_partner_pie.setElevation(1f);
        }
        if (tv_filter_shared_pie != null) {
            tv_filter_shared_pie.setBackgroundResource(R.drawable.filter_button_right_unselected);
            tv_filter_shared_pie.setTextColor(defaultTextColor);
            tv_filter_shared_pie.setElevation(1f);
        }
    }

    // 生成随机颜色
    private int generateRandomColor() {
        Random random = new Random();
        // 生成较为鲜艳的颜色，避免过于暗淡
        int red = random.nextInt(156) + 100;   // 100-255
        int green = random.nextInt(156) + 100; // 100-255
        int blue = random.nextInt(156) + 100;  // 100-255
        return android.graphics.Color.rgb(red, green, blue);
    }
}