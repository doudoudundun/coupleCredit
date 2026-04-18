package com.example.couplecredit.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.couplecredit.model.BillBean;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.CategoryIconMapper;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 经典模式账单列表 ViewModel
 * 管理数据与业务逻辑，Fragment 只负责展示与交互
 */
public class ClassicViewModel extends AndroidViewModel {

    private final List<Object> displayItems = new ArrayList<>();
    private final List<BillBean> billItems = new ArrayList<>();

    private final MutableLiveData<Integer> displayVersion = new MutableLiveData<>(0);
    private final MutableLiveData<Double> totalIncome = new MutableLiveData<>(0.0);
    private final MutableLiveData<Double> totalExpense = new MutableLiveData<>(0.0);
    private final MutableLiveData<Boolean> isLoggedIn = new MutableLiveData<>(false);
    private final MutableLiveData<String> monthTitle = new MutableLiveData<>("");

    private int currentYear;
    private int currentMonth;

    public ClassicViewModel(@NonNull Application application) {
        super(application);
        Calendar calendar = Calendar.getInstance();
        currentYear = calendar.get(Calendar.YEAR);
        currentMonth = calendar.get(Calendar.MONTH) + 1; // Calendar.MONTH 从0开始
        updateMonthTitle();
    }

    // 初始化与首次加载
    public void initialize() {
        firstLoadBills();
    }

    public void firstLoadBills() {
        loadBillData(currentYear, currentMonth);
    }

    public void refreshCurrentMonth() {
        loadBillData(currentYear, currentMonth);
    }

    public void setYearMonth(int year, int month) {
        this.currentYear = year;
        this.currentMonth = month;
        updateMonthTitle();
        loadBillData(year, month);
    }

    public int getCurrentYear() { return currentYear; }
    public int getCurrentMonth() { return currentMonth; }

    public List<Object> getDisplayItemsRef() { return displayItems; }

    public LiveData<Integer> getDisplayVersion() { return displayVersion; }
    public LiveData<Double> getTotalIncome() { return totalIncome; }
    public LiveData<Double> getTotalExpense() { return totalExpense; }
    public LiveData<Boolean> getIsLoggedIn() { return isLoggedIn; }
    public LiveData<String> getMonthTitle() { return monthTitle; }

    private void bumpVersion() {
        Integer v = displayVersion.getValue();
        displayVersion.postValue(v == null ? 1 : v + 1);
    }

    private void updateMonthTitle() {
        String[] months = {"1月", "2月", "3月", "4月", "5月", "6月",
                "7月", "8月", "9月", "10月", "11月", "12月"};
        String text = "我们的" + months[Math.max(1, Math.min(12, currentMonth)) - 1] + " >";
        monthTitle.postValue(text);
    }

    public void loadBillData(int year, int month) {
        billItems.clear();
        String monthPattern = String.format(Locale.getDefault(), "%04d-%02d-%%", year, month);

        Log.d("ClassicViewModel", "开始加载账单数据: " + year + "-" + month);
        long loadStartTime = System.currentTimeMillis();

        // 登录检查
        boolean loggedIn = UserInfoManager.isUserLoggedIn(getApplication());
        isLoggedIn.postValue(loggedIn);
        Log.d("ClassicViewModel", "登录状态: " + loggedIn);

        if (!loggedIn) {
            // 未登录：清空数据
            displayItems.clear();
            bumpVersion();
            totalIncome.postValue(0.0);
            totalExpense.postValue(0.0);
            return;
        }

        // 检查是否有数据库配置
        Log.d("ClassicViewModel", "使用 HTTP API 模式");
        loadBillDataViaHttpApi(year, month);
    }

    /**
     * 通过 HTTP API 加载账单数据（外网模式）
     */
    private void loadBillDataViaHttpApi(int year, int month) {
        int userId = UserInfoManager.getCurrentUserId(getApplication());
        if (userId < 0) {
            Log.e("ClassicViewModel", "获取用户ID失败");
            displayItems.clear();
            totalIncome.postValue(0.0);
            totalExpense.postValue(0.0);
            bumpVersion();
            return;
        }

        Log.d("ClassicViewModel", "使用 HTTP API 查询账单: userId=" + userId + ", year=" + year + ", month=" + month);

        AuthApiClient.queryBills(getApplication(), userId, year, month, new AuthApiClient.BillsQueryCallback() {
            @Override
            public void onSuccess(AuthApiModels.BillsQueryResponse response) {
                // 缓存关系状态到本地
                if (response.data != null && response.data.relationshipId != null) {
                    UserInfoManager.saveRelationshipId(getApplication(), response.data.relationshipId);
                }

                if (response.data == null || response.data.bills == null) {
                    Log.w("ClassicViewModel", "API 返回无账单数据");
                    billItems.clear();
                    displayItems.clear();
                    totalIncome.postValue(0.0);
                    totalExpense.postValue(0.0);
                    bumpVersion();
                    return;
                }

                billItems.clear();
                for (AuthApiModels.BillData bill : response.data.bills) {
                    try {
                        // 解析日期 (格式: "2026-04-12T16:00:00.000Z" 或 "2026-04-12")
                        String dateStr = bill.date;
                        int y, m, d;
                        if (dateStr.contains("T")) {
                            dateStr = dateStr.substring(0, 10);
                        }
                        String[] dateParts = dateStr.split("-");
                        y = Integer.parseInt(dateParts[0]);
                        m = Integer.parseInt(dateParts[1]);
                        d = Integer.parseInt(dateParts[2]);

                        int iconResId = CategoryIconMapper.getIconForCategory(bill.type);
                        billItems.add(new BillBean(
                            bill.billId,
                            bill.amount,
                            y, m, d,
                            bill.owner,
                            bill.userId,
                            bill.type,
                            bill.title,
                            iconResId,
                            bill.incomeType,
                            bill.time,
                            bill.title,
                            bill.isHelp
                        ));
                    } catch (Exception e) {
                        Log.e("ClassicViewModel", "解析账单失败: " + e.getMessage(), e);
                    }
                }

                processAndDisplayData();
                sumAmounts();
                bumpVersion();

                Log.d("ClassicViewModel", "HTTP API 查询完成，账单数: " + billItems.size());
            }

            @Override
            public void onError(String message) {
                Log.e("ClassicViewModel", "HTTP API 查询失败: " + message);
                displayItems.clear();
                totalIncome.postValue(0.0);
                totalExpense.postValue(0.0);
                bumpVersion();
            }
        });
    }

    // 与原Fragment一致的分组与展示结构，生成 List<Map<String, List<BillBean>>> 供BillAdapter使用
    private void processAndDisplayData() {
        // 按日期yyyy-MM-dd分组（保持查询结果的倒序顺序）
        Map<String, List<BillBean>> grouped = new LinkedHashMap<>();
        for (BillBean bill : billItems) {
            String key = String.format(Locale.getDefault(), "%04d-%02d-%02d", bill.getYear(), bill.getMonth(), bill.getDay());
            if (!grouped.containsKey(key)) grouped.put(key, new ArrayList<>());
            grouped.get(key).add(bill);
        }

        // 每个日期内按billId倒序，保持原先展示习惯
        for (List<BillBean> list : grouped.values()) {
            Collections.sort(list, new Comparator<BillBean>() {
                @Override public int compare(BillBean o1, BillBean o2) {
                    return Long.compare(o2.getBillId(), o1.getBillId());
                }
            });
        }

        displayItems.clear();
        for (Map.Entry<String, List<BillBean>> entry : grouped.entrySet()) {
            Map<String, List<BillBean>> dateGroup = new LinkedHashMap<>();
            dateGroup.put(entry.getKey(), entry.getValue());
            displayItems.add(dateGroup);
        }
    }

    // 统计本月收支
    private void sumAmounts() {
        double income = 0.0;
        double expense = 0.0;
        for (BillBean bill : billItems) {
            if (bill.getIncomeType() == 1) income += bill.getFare();
            else expense += bill.getFare();
        }
        totalIncome.postValue(income);
        totalExpense.postValue(expense);
    }

}