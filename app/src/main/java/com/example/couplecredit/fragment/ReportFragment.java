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

import com.example.couplecredit.R;
import com.example.couplecredit.adapter.CategoryDetailAdapter;
import com.example.couplecredit.adapter.ReportAdapter;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.function.Utils;
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
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private TabLayout segmentedTab;
    private final List<String> currentTabs = new ArrayList<>();
    private LineChart TrendChart;
    private LinearLayout TrendContainer;
    private RecyclerView rvReportContent;
    private ReportAdapter reportAdapter;
    private PieChart currentPieChart;
    private TextView currentTitleView;
    private RecyclerView rvCategoryList;
    private CategoryDetailAdapter categoryDetailAdapter;
    private int income_type = 0;
    private String currentChartFilter = "all";
    private String currentPieFilter = "all";
    private final List<Map<String, Object>> monthlyBills = new ArrayList<>();

    private interface MonthlyDataCallback {
        void onDataLoaded();
        void onError(String error);
    }

    private int currentUserRole = -1;

    private final TabLayout.OnTabSelectedListener onTabSelectedListener = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            int position = tab.getPosition();
            if (position == 0) {
                income_type = 0;
                if (tv_trend_title != null) {
                    tv_trend_title.setText("支出趋势");
                }
                loadTrendData();
                refreshPieChart();
                getRemainer(new RemainingCallback() {
                    @Override
                    public void onResult(double remaining) {
                        if (getActivity() != null && tv_remainer != null) {
                            getActivity().runOnUiThread(() -> tv_remainer.setText("结余：￥" + String.format("%.2f", remaining)));
                        }
                    }
                });
            } else if (position == 1) {
                income_type = 1;
                if (tv_trend_title != null) {
                    tv_trend_title.setText("收入趋势");
                }
                loadTrendData();
                refreshPieChart();
            }
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {
        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {
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
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1;

        Bundle args = getArguments();
        if (args != null) {
            currentYear = args.getInt("year", currentYear);
            currentMonth = args.getInt("month", currentMonth);
        }

        tv_month_choose = view.findViewById(R.id.tv_month_choose);
        tv_remainer = view.findViewById(R.id.tv_remainer);
        rvReportContent = view.findViewById(R.id.rv_report_content);
        rvReportContent.setLayoutManager(new LinearLayoutManager(getContext()));

        reportAdapter = new ReportAdapter(new ReportAdapter.ChartViewHolderCallback() {
            @Override
            public void onChartViewHolderCreated(ReportAdapter.ChartViewHolder holder) {
                tv_trend_title = holder.tvTrendTitle;
                tv_total_amount = holder.tvTotalAmount;
                tv_filter_all = holder.tvFilterAll;
                tv_filter_self = holder.tvFilterSelf;
                tv_filter_partner = holder.tvFilterPartner;
                tv_filter_shared = holder.tvFilterShared;
                TrendChart = holder.trendChart;
                TrendContainer = holder.llTrendContainer;

                setupFilterButtons();
                setupTrendChart();
                selectChartFilter("all", tv_filter_all);
            }
        }, new ReportAdapter.AdditionalViewHolderCallback() {
            @Override
            public void onAdditionalViewHolderCreated(ReportAdapter.AdditionalViewHolder holder) {
                currentPieChart = holder.pieChartCategory;
                currentTitleView = holder.tvAdditionalTitle;
                rvCategoryList = holder.rvCategoryList;
                tv_filter_all_pie = holder.tvFilterAllPie;
                tv_filter_self_pie = holder.tvFilterSelfPie;
                tv_filter_partner_pie = holder.tvFilterPartnerPie;
                tv_filter_shared_pie = holder.tvFilterSharedPie;

                setupPieFilterButtons();
                setupPieChart(holder.pieChartCategory);
                setupCategoryList();
                selectPieFilter("all", tv_filter_all_pie);
            }
        });
        rvReportContent.setAdapter(reportAdapter);

        if (tv_month_choose != null) {
            tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
            tv_month_choose.setOnClickListener(v -> Utils.showDatePickerDialog(getActivity(), currentYear, currentMonth, (selectedYear, selectedMonth) -> {
                currentYear = selectedYear;
                currentMonth = selectedMonth;
                tv_month_choose.setText(selectedYear + "年" + selectedMonth + "月 >");
                if (categoryDetailAdapter != null) {
                    categoryDetailAdapter.updateYearMonth(currentYear, currentMonth);
                }
                loadTrendData();
                refreshPieChart();
            }));
        }

        segmentedTab = view.findViewById(R.id.segmented_tab);
        if (currentTabs.isEmpty()) {
            currentTabs.add("支出");
            currentTabs.add("收入");
        }
        setupSegmentedTab();
        initUserInfo();
    }

    private void initUserInfo() {
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                if (relationshipId != null) {
                    CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
                    coupleHelper.getUserRole(userId, new CoupleRelationshipHelper.UserRoleCallback() {
                        @Override
                        public void onRoleFound(int ownerId) {
                            currentUserRole = ownerId;
                        }

                        @Override
                        public void onNoRelationshipFound() {
                            currentUserRole = 1;
                        }

                        @Override
                        public void onError(String error) {
                            currentUserRole = 1;
                        }
                    });
                } else {
                    currentUserRole = 1;
                }
            }

            @Override
            public void onError(String error) {
                currentUserRole = 1;
            }
        });
    }

    public void updateDisplay(int year, int month, String type) {
        currentYear = year;
        currentMonth = month;
        if (tv_month_choose != null) {
            tv_month_choose.setText(currentYear + "年" + currentMonth + "月 >");
        }
        if (categoryDetailAdapter != null) {
            categoryDetailAdapter.updateYearMonth(currentYear, currentMonth);
        }
        if (segmentedTab != null) {
            TabLayout.Tab targetTab = "income".equals(type) ? segmentedTab.getTabAt(1) : segmentedTab.getTabAt(0);
            if (targetTab != null) {
                targetTab.select();
            }
        }
        loadTrendData();
        refreshPieChart();
    }

    private void setupSegmentedTab() {
        if (segmentedTab == null) {
            return;
        }
        if (segmentedTab.getTabCount() == 0) {
            for (String tabTitle : currentTabs) {
                segmentedTab.addTab(segmentedTab.newTab().setText(tabTitle));
            }
        }
        TabLayout.Tab defaultTab = segmentedTab.getTabAt(income_type == 1 ? 1 : 0);
        if (defaultTab != null) {
            defaultTab.select();
        }
        segmentedTab.addOnTabSelectedListener(onTabSelectedListener);
    }

    private void setupFilterButtons() {
        if (tv_filter_all != null) {
            tv_filter_all.setOnClickListener(v -> selectChartFilter("all", tv_filter_all));
        }
        if (tv_filter_self != null) {
            tv_filter_self.setOnClickListener(v -> selectChartFilter("self", tv_filter_self));
        }
        if (tv_filter_partner != null) {
            tv_filter_partner.setOnClickListener(v -> selectChartFilter("partner", tv_filter_partner));
        }
        if (tv_filter_shared != null) {
            tv_filter_shared.setOnClickListener(v -> selectChartFilter("shared", tv_filter_shared));
        }
    }

    private void selectChartFilter(String filterType, TextView selectedView) {
        resetChartFilterButtons();
        if (selectedView != null) {
            if (selectedView == tv_filter_all) {
                selectedView.setBackgroundResource(R.drawable.filter_button_left_selected);
            } else if (selectedView == tv_filter_self || selectedView == tv_filter_partner) {
                selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
            } else if (selectedView == tv_filter_shared) {
                selectedView.setBackgroundResource(R.drawable.filter_button_right_selected);
            }
            selectedView.setTextColor(getResources().getColor(android.R.color.white));
            selectedView.setElevation(8f);
        }
        currentChartFilter = filterType;
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

        TrendChart.getDescription().setEnabled(false);
        TrendChart.setTouchEnabled(true);
        TrendChart.setDragEnabled(true);
        TrendChart.setScaleEnabled(true);
        TrendChart.setPinchZoom(true);

        XAxis xAxis = TrendChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setAxisMinimum(1f);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return ((int) value) + "日";
            }
        });

        YAxis leftAxis = TrendChart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        TrendChart.getAxisRight().setEnabled(false);
    }

    private void loadTrendData() {
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            List<Entry> entries = buildEmptyMonthEntries();
            updateChart(entries);
            updateTotalAmount(0f);
            return;
        }

        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                loadMonthlyBillsData(userId, relationshipId, new MonthlyDataCallback() {
                    @Override
                    public void onDataLoaded() {
                        loadMonthlyTrendData();
                    }

                    @Override
                    public void onError(String error) {
                        handleTrendDataError();
                    }
                });
            }

            @Override
            public void onError(String error) {
                handleTrendDataError();
            }
        });
    }

    private List<Entry> buildEmptyMonthEntries() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(currentYear, currentMonth - 1, 1);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        List<Entry> entries = new ArrayList<>();
        for (int day = 1; day <= daysInMonth; day++) {
            entries.add(new Entry(day, 0));
        }
        return entries;
    }

    private void loadMonthlyTrendData() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(currentYear, currentMonth - 1, 1);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        float[] dailyAmounts = new float[daysInMonth + 1];

        for (Map<String, Object> bill : monthlyBills) {
            if (!shouldIncludeBillWithFilter(bill, currentChartFilter)) {
                continue;
            }
            String dateStr = (String) bill.get("date");
            Double amount = (Double) bill.get("amount");
            if (dateStr == null || amount == null) {
                continue;
            }
            try {
                String[] dateParts = dateStr.split("-");
                if (dateParts.length >= 3) {
                    int day = Integer.parseInt(dateParts[2]);
                    if (day >= 1 && day <= daysInMonth) {
                        dailyAmounts[day] += amount.floatValue();
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        List<Entry> entries = new ArrayList<>();
        float totalAmount = 0f;
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

    private void handleTrendDataError() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                updateChart(buildEmptyMonthEntries());
                updateTotalAmount(0f);
            });
        }
    }

    private void updateChart(List<Entry> entries) {
        if (TrendChart == null) {
            return;
        }

        LineDataSet dataSet = new LineDataSet(entries, income_type == 1 ? "收入金额" : "支出金额");
        int color = income_type == 1 ? 0xFFF44336 : 0xFF4CAF50;
        dataSet.setColor(color);
        dataSet.setCircleColor(color);
        dataSet.setFillColor(color);
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawCircleHole(false);
        dataSet.setDrawFilled(false);
        dataSet.setDrawValues(true);
        dataSet.setValueTextSize(10f);
        dataSet.setValueTextColor(0xFF333333);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return value == 0 ? "" : String.format("%.0f", value);
            }
        });

        TrendChart.setData(new LineData(dataSet));
        TrendChart.animateY(500);
        TrendChart.invalidate();
    }

    private void updateTotalAmount(float totalAmount) {
        if (tv_total_amount != null) {
            String typeText = income_type == 0 ? "支出" : "收入";
            tv_total_amount.setText(typeText + ":￥" + String.format("%.2f", totalAmount));
        }
    }

    public void refreshChartData() {
        loadTrendData();
        refreshPieChart();
        if (income_type == 0) {
            getRemainer(new RemainingCallback() {
                @Override
                public void onResult(double remaining) {
                    if (getActivity() != null && tv_remainer != null) {
                        getActivity().runOnUiThread(() -> tv_remainer.setText("结余：￥" + String.format("%.2f", remaining)));
                    }
                }
            });
        }
    }

    private void setupPieChart(PieChart pieChart) {
        if (pieChart == null) {
            return;
        }
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleColor(android.graphics.Color.WHITE);
        pieChart.setTransparentCircleRadius(61f);
        pieChart.setEntryLabelTextSize(12f);
        pieChart.setEntryLabelColor(android.graphics.Color.BLACK);
        pieChart.getLegend().setEnabled(false);
    }

    private void setupCategoryList() {
        if (rvCategoryList == null || getContext() == null) {
            return;
        }
        rvCategoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        if (categoryDetailAdapter == null) {
            categoryDetailAdapter = new CategoryDetailAdapter(getContext(), new ArrayList<>(), currentYear, currentMonth);
            rvCategoryList.setAdapter(categoryDetailAdapter);
        }
    }

    private void setupPieFilterButtons() {
        if (tv_filter_all_pie != null) {
            tv_filter_all_pie.setOnClickListener(v -> selectPieFilter("all", tv_filter_all_pie));
        }
        if (tv_filter_self_pie != null) {
            tv_filter_self_pie.setOnClickListener(v -> selectPieFilter("self", tv_filter_self_pie));
        }
        if (tv_filter_partner_pie != null) {
            tv_filter_partner_pie.setOnClickListener(v -> selectPieFilter("partner", tv_filter_partner_pie));
        }
        if (tv_filter_shared_pie != null) {
            tv_filter_shared_pie.setOnClickListener(v -> selectPieFilter("shared", tv_filter_shared_pie));
        }
    }

    private void selectPieFilter(String filterType, TextView selectedView) {
        resetPieFilterButtons();
        if (selectedView != null) {
            if (selectedView == tv_filter_all_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_left_selected);
            } else if (selectedView == tv_filter_self_pie || selectedView == tv_filter_partner_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_middle_selected);
            } else if (selectedView == tv_filter_shared_pie) {
                selectedView.setBackgroundResource(R.drawable.filter_button_right_selected);
            }
            selectedView.setTextColor(getResources().getColor(android.R.color.white));
            selectedView.setElevation(8f);
        }
        currentPieFilter = filterType;
        refreshPieChart();
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

    private void refreshPieChart() {
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            if (currentPieChart != null) {
                currentPieChart.clear();
                currentPieChart.invalidate();
            }
            if (categoryDetailAdapter != null) {
                categoryDetailAdapter.updateData(new ArrayList<>());
            }
            if (currentTitleView != null) {
                currentTitleView.setText(income_type == 1 ? "收入分类" : "支出分类");
            }
            return;
        }

        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                loadMonthlyBillsData(userId, relationshipId, new MonthlyDataCallback() {
                    @Override
                    public void onDataLoaded() {
                        if (currentPieChart != null && currentTitleView != null) {
                            loadCategoryData(currentPieChart, currentTitleView);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (currentPieChart != null) {
                            currentPieChart.clear();
                            currentPieChart.invalidate();
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (currentPieChart != null) {
                    currentPieChart.clear();
                    currentPieChart.invalidate();
                }
            }
        });
    }

    private void loadCategoryData(PieChart pieChart, TextView titleView) {
        Map<String, Float> categoryTotals = new HashMap<>();
        float totalAmount = 0f;

        for (Map<String, Object> bill : monthlyBills) {
            if (!shouldIncludeBillWithFilter(bill, currentPieFilter)) {
                continue;
            }
            String type = (String) bill.get("type");
            Double amount = (Double) bill.get("amount");
            if (type != null && amount != null) {
                float value = amount.floatValue();
                categoryTotals.put(type, categoryTotals.getOrDefault(type, 0f) + value);
                totalAmount += value;
            }
        }

        List<PieEntry> entries = new ArrayList<>();
        List<CategoryDetailAdapter.CategoryDetail> categoryDetails = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        Random random = new Random();

        for (Map.Entry<String, Float> entry : categoryTotals.entrySet()) {
            float amount = entry.getValue();
            float percentage = totalAmount == 0 ? 0 : amount / totalAmount * 100f;
            int color = ColorTemplate.MATERIAL_COLORS[random.nextInt(ColorTemplate.MATERIAL_COLORS.length)];
            entries.add(new PieEntry(amount, entry.getKey()));
            colors.add(color);
            categoryDetails.add(new CategoryDetailAdapter.CategoryDetail(entry.getKey(), countBillsByCategory(entry.getKey()), amount, percentage, color));
        }

        if (entries.isEmpty()) {
            pieChart.clear();
            pieChart.invalidate();
            if (categoryDetailAdapter != null) {
                categoryDetailAdapter.updateData(new ArrayList<>());
            }
            if (titleView != null) {
                titleView.setText(income_type == 1 ? "收入分类" : "支出分类");
            }
            return;
        }

        PieDataSet dataSet = new PieDataSet(entries, "分类统计");
        dataSet.setColors(colors);
        dataSet.setSliceSpace(2f);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(android.graphics.Color.WHITE);
        PieData pieData = new PieData(dataSet);
        pieData.setValueFormatter(new PercentFormatter(pieChart));

        pieChart.setData(pieData);
        pieChart.animateY(500);
        pieChart.invalidate();

        if (categoryDetailAdapter != null) {
            categoryDetailAdapter.updateData(categoryDetails);
        }
        if (titleView != null) {
            titleView.setText(income_type == 1 ? "收入分类" : "支出分类");
        }
    }

    private int countBillsByCategory(String category) {
        int count = 0;
        for (Map<String, Object> bill : monthlyBills) {
            if (category.equals(bill.get("type")) && shouldIncludeBillWithFilter(bill, currentPieFilter)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldIncludeBillWithFilter(Map<String, Object> bill, String filterType) {
        Integer owner = (Integer) bill.get("owner");
        if (owner == null || "all".equals(filterType)) {
            return true;
        }
        if ("shared".equals(filterType)) {
            return owner == 3;
        }
        if ("self".equals(filterType)) {
            return owner == currentUserRole;
        }
        if ("partner".equals(filterType)) {
            return currentUserRole == 1 ? owner == 2 : owner == 1;
        }
        return true;
    }

    private void loadMonthlyBillsData(int userId, Integer relationshipId, MonthlyDataCallback callback) {
        BillDatabaseHelper billHelper = new BillDatabaseHelper(getContext());
        String monthPattern = String.format("%04d-%02d-%%", currentYear, currentMonth);
        String selection = "date LIKE ? AND income_type = ?";
        String[] selectionArgs = new String[]{monthPattern, String.valueOf(income_type)};

        billHelper.queryBillsWithUserFilter(userId, relationshipId, selection, selectionArgs, new BillDatabaseHelper.QueryCallback() {
            @Override
            public void onSuccess(List<Map<String, Object>> results) {
                monthlyBills.clear();
                monthlyBills.addAll(results);
                if (callback != null) {
                    callback.onDataLoaded();
                }
            }

            @Override
            public void onError(String error) {
                monthlyBills.clear();
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    private interface RemainingCallback {
        void onResult(double remaining);
    }

    private void getRemainer(RemainingCallback callback) {
        if (!UserInfoManager.isUserLoggedIn(getContext())) {
            callback.onResult(0);
            return;
        }
        UserInfoManager.getCurrentUserInfo(getContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                BillDatabaseHelper billHelper = new BillDatabaseHelper(getContext());
                String monthPattern = String.format("%04d-%02d-%%", currentYear, currentMonth);
                String selection = "date LIKE ?";
                String[] selectionArgs = new String[]{monthPattern};
                billHelper.queryBillsWithUserFilter(userId, relationshipId, selection, selectionArgs, new BillDatabaseHelper.QueryCallback() {
                    @Override
                    public void onSuccess(List<Map<String, Object>> results) {
                        double income = 0;
                        double expense = 0;
                        for (Map<String, Object> bill : results) {
                            Double amount = (Double) bill.get("amount");
                            Integer incomeType = (Integer) bill.get("income_type");
                            if (amount == null || incomeType == null) {
                                continue;
                            }
                            if (incomeType == 1) {
                                income += amount;
                            } else {
                                expense += amount;
                            }
                        }
                        callback.onResult(income - expense);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onResult(0);
                    }
                });
            }

            @Override
            public void onError(String error) {
                callback.onResult(0);
            }
        });
    }
}
