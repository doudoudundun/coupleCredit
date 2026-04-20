package com.example.couplecredit.utils;

import android.app.Dialog;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.NumberPicker;
import android.widget.TextView;

import com.example.couplecredit.model.BillBean;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.R;

import androidx.appcompat.app.AlertDialog;

import java.util.Calendar;

public final class BillUtils {
    /**
     * 账单插入回调接口
     */
    public interface BillInsertCallback {
        void onInsertSuccess(long id);
        void onInsertError(String error);
    }

    /**
     * 插入账单（新版本 - 自动获取当前用户信息）
     */
    public static void insertBill(Context context, String title, String type, double amount, String date, String time, int incomeType, String billOwner, BillInsertCallback callback) {
        insertBill(context, title, type, amount, date, time, incomeType, billOwner, null, callback);
    }

    public static void insertBill(Context context, String title, String type, double amount, String date, String time, int incomeType, String billOwner, Integer sharedPlanId, BillInsertCallback callback) {
        if (context == null) {
            if (callback != null) {
                callback.onInsertError("页面状态异常，请重新进入记账页");
            }
            return;
        }

        if (!UserInfoManager.isUserLoggedIn(context)) {
            Log.e("Utils", "用户未登录，禁止记账");
            if (callback != null) {
                callback.onInsertError("请先登录后再记账");
            }
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            Log.e("Utils", "获取用户信息失败");
            if (callback != null) {
                callback.onInsertError("获取用户信息失败");
            }
            return;
        }

        AuthApiClient.createBill(context, userId, billOwner, sharedPlanId, title, type, amount, date, time, incomeType, new AuthApiClient.BillCallback() {
            @Override
            public void onSuccess(com.example.couplecredit.api.AuthApiModels.BillResponse response) {
                if (response == null || response.data == null) {
                    if (callback != null) {
                        callback.onInsertError("服务器未返回账单信息");
                    }
                    return;
                }

                if (callback != null) {
                    callback.onInsertSuccess(response.data.billId);
                }
            }

            @Override
            public void onError(String message) {
                Log.e("Utils", "账单插入失败: " + message);
                if (callback != null) {
                    callback.onInsertError(message);
                }
            }
        });
    }

    /**
     * 插入账单（兼容旧版本）
     * @deprecated 请使用新版本的insertBill方法
     */

    // 辅助方法已移除（JDBC路径不再使用）

    @Deprecated
    public static void insertBill(Context context,int userId, String title, String type, double amount, String date, String time, int incomeType) {
        insertBill(context, title, type, amount, date, time, incomeType, "自己", null);
    }

    public static int getUserId(String username){
        if(username.equals("自己")){
            return 1;
        }
        else if(username.equals("对方")){
            return 2;
        }
        else{
            return 3;
        }
    }

    public interface DeleteBillCallback {
        void onDeleteSuccess(int rowsDeleted);
        void onDeleteError(String error);
    }

    public static void deleteBill(Context context, BillBean bill, DeleteBillCallback callback) {
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) callback.onDeleteError("用户未登录");
            return;
        }

        AuthApiClient.deleteBill(context, (int) bill.getBillId(), userId, new AuthApiClient.DeleteBillCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) callback.onDeleteSuccess(1);
            }

            @Override
            public void onError(String message) {
                Log.e("Utils", "删除账单失败: " + message);
                if (callback != null) callback.onDeleteError(message);
            }
        });
    }


    public static void updateBill(Context context, BillBean bill, String newDate, double newFare, String newNoteContent, String newTime, UpdateBillCallback callback) {
        updateBill(context, bill, newDate, newFare, newNoteContent, newTime, null, callback);
    }

    public static void updateBill(Context context, BillBean bill, String newDate, double newFare, String newNoteContent, String newTime, Integer isHelp, UpdateBillCallback callback) {
        String titleToUpdate = (newNoteContent == null || newNoteContent.trim().isEmpty()) ? bill.getCategoryName() : newNoteContent;
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            if (callback != null) {
                callback.onUpdateError("用户未登录");
            }
            return;
        }

        Integer incomeType = bill.getIncomeType();
        AuthApiClient.updateBill(context, (int) bill.getBillId(), userId, titleToUpdate, bill.getCategoryName(), newFare, newDate, newTime, incomeType, new AuthApiClient.UpdateBillCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) {
                    callback.onUpdateSuccess(1);
                }
            }

            @Override
            public void onError(String message) {
                Log.e("Utils", "更新账单失败: " + message);
                if (callback != null) {
                    callback.onUpdateError(message);
                }
            }
        });
    }

    public interface DatePickerCallback {
        void onDateSelected(String formattedDate);

    }

    public static void showDatePicker(Context context, String dateString, DatePickerCallback callback) {
        Calendar selectedDate = Calendar.getInstance();
        int year = selectedDate.get(Calendar.YEAR);
        int month = selectedDate.get(Calendar.MONTH);
        int day = selectedDate.get(Calendar.DAY_OF_MONTH);

        if (dateString != null && !dateString.isEmpty()) {
            try {
                String[] dateParts = dateString.split("-");
                if (dateParts.length == 3) {
                    year = Integer.parseInt(dateParts[0]);
                    month = Integer.parseInt(dateParts[1]) - 1;
                    day = Integer.parseInt(dateParts[2]);
                }
            } catch (Exception e) {
            }
        }

        android.widget.DatePicker datePicker = new android.widget.DatePicker(context);
        datePicker.init(year, month, day, null);

        new android.app.AlertDialog.Builder(context)
                .setTitle("选择日期")
                .setView(datePicker)
                .setPositiveButton("确定", (dialog, which) -> {
                    int selectedYear = datePicker.getYear();
                    int selectedMonth = datePicker.getMonth();
                    int selectedDay = datePicker.getDayOfMonth();

                    String formattedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay);
                    if (callback != null) {
                        callback.onDateSelected(formattedDate);
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .show();
    }
    public interface MonthPickerCallback{
        void onMonthSelected(int year, int month);
    }
    public static void showDatePickerDialog(Context context, int currentYear, int currentMonth, MonthPickerCallback callback) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_date_picker);

        NumberPicker yearPicker = dialog.findViewById(R.id.np_year);
        NumberPicker monthPicker = dialog.findViewById(R.id.np_month);
        TextView tvCancel = dialog.findViewById(R.id.tv_cancel);
        TextView tvConfirm = dialog.findViewById(R.id.tv_confirm);

        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(2080);
        yearPicker.setValue(currentYear);

        String[] monthDisplayValues = {"01", "02", "03", "04", "05", "06",
                "07", "08", "09", "10", "11", "12"};
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setDisplayedValues(monthDisplayValues);
        monthPicker.setValue(currentMonth);

        tvCancel.setOnClickListener(v -> dialog.dismiss());

        tvConfirm.setOnClickListener(v -> {
            int year = yearPicker.getValue();
            int month = monthPicker.getValue();
            callback.onMonthSelected(year, month);
            dialog.dismiss();
        });

        dialog.show();
    }


    public interface UpdateBillCallback {
        void onUpdateSuccess(int rowsAffected);
        void onUpdateError(String error);
    }

    public static void enterEditMode(Context context, AlertDialog mDialog, TextView tvDate, TextView tvFare, TextView tvNoteContent,
                                     EditText etFare, EditText etNoteContent,
                                     Button btnEdit, Button btnDelete, ImageButton btnConfirm, ImageButton btnCancel){
        if (tvDate != null) tvDate.setOnClickListener(v-> {
            BillUtils.showDatePicker(context, tvDate.getText().toString(),
                    formattedDate -> tvDate.setText(formattedDate));
        });
        if (tvFare != null) tvFare.setVisibility(View.GONE);
        if (tvNoteContent != null) tvNoteContent.setVisibility(View.GONE);

        if (etFare != null) etFare.setVisibility(View.VISIBLE);
        if (etNoteContent != null) etNoteContent.setVisibility(View.VISIBLE);

        if (btnEdit != null) btnEdit.setVisibility(View.GONE);
        if (btnDelete != null) btnDelete.setVisibility(View.GONE);
        if (btnConfirm != null) btnConfirm.setVisibility(View.VISIBLE);
        if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
        mDialog.setTitle("修改账单");
    }

    public static void exitEditMode(AlertDialog mDialog, TextView tvDate, TextView tvFare, TextView tvNoteContent,
                              EditText etFare, EditText etNoteContent,
                              Button btnEdit, Button btnDelete, ImageButton btnConfirm, ImageButton btnCancel) {
        if (tvFare != null && etFare != null) {
            String fareText = etFare.getText().toString();
            if (!fareText.startsWith("￥")) {
                tvFare.setText("￥" + fareText);
            } else {
                tvFare.setText(fareText);
            }
        }
        if (tvNoteContent != null && etNoteContent != null) {
            tvNoteContent.setText(etNoteContent.getText().toString());
        }

        if (tvDate != null) tvDate.setVisibility(View.VISIBLE);
        if (tvFare != null) tvFare.setVisibility(View.VISIBLE);
        if (tvNoteContent != null) tvNoteContent.setVisibility(View.VISIBLE);

        if (etFare != null) etFare.setVisibility(View.GONE);
        if (etNoteContent != null) etNoteContent.setVisibility(View.GONE);

        if (btnEdit != null) btnEdit.setVisibility(View.VISIBLE);
        if (btnDelete != null) btnDelete.setVisibility(View.VISIBLE);
        if (btnConfirm != null) btnConfirm.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        mDialog.setTitle("账单详情");
    }
}
