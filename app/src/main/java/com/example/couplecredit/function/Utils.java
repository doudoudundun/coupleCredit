package com.example.couplecredit.function;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Window;
import android.widget.NumberPicker;
import android.widget.TextView;

import com.example.couplecredit.BillBean;
import com.example.couplecredit.BillDatabaseHelper;
import com.example.couplecredit.BillProvider;
import com.example.couplecredit.R;

import java.util.Calendar;

public final class Utils {
    public static void insertBill(Context context,int userId, String title, String type, double amount, String date, String time, int incomeType) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(BillDatabaseHelper.USER_ID, userId);
        values.put(BillDatabaseHelper.COLUMN_TITLE, title);
        values.put(BillDatabaseHelper.COLUMN_TYPE, type);
        values.put(BillDatabaseHelper.COLUMN_AMOUNT, amount);
        values.put(BillDatabaseHelper.COLUMN_DATE, date);
        values.put(BillDatabaseHelper.COLUMN_TIME, time);
        values.put(BillDatabaseHelper.COLUMN_INCOME_TYPE, incomeType);

        BillDatabaseHelper billHelper = new BillDatabaseHelper(context);
        billHelper.insertBill(values, new BillDatabaseHelper.BillInsertCallback() {
            @Override
            public void onInsertSuccess(long id) {
                Log.d("Utils", "账单插入成功，ID: " + id);
            }

            @Override
            public void onInsertError(String error) {
                Log.e("Utils", "账单插入失败: " + error);
            }
        });
    }
    //加入多用户之后需要切分逻辑
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
        // 直接调用BillDatabaseHelper进行删除
        BillDatabaseHelper dbHelper = new BillDatabaseHelper(context);
        String selection = BillDatabaseHelper.COLUMN_ID + "=?";
        String[] selectionArgs = {String.valueOf(bill.getBillId())};
        
        Log.d("Utils", "开始删除账单，ID: " + bill.getBillId());
        Log.d("Utils", "删除条件: " + selection + ", 参数: " + java.util.Arrays.toString(selectionArgs));
        
        dbHelper.deleteBill(selection, selectionArgs, new BillDatabaseHelper.BillDeleteCallback() {
            @Override
            public void onDeleteSuccess(int rowsDeleted) {
                Log.d("Utils", "删除成功，影响行数: " + rowsDeleted);
                if (callback != null) {
                    callback.onDeleteSuccess(rowsDeleted);
                }
            }
            
            @Override
            public void onDeleteError(String error) {
                Log.e("Utils", "删除账单失败: " + error);
                if (callback != null) {
                    callback.onDeleteError(error);
                }
            }
        });
    }
    
    public static void updateBill(Context context, BillBean bill, String newDate, double newFare, String newNoteContent, String newTime, UpdateBillCallback callback) {
        // 使用bill_id作为唯一标识进行更新
        String selection = BillDatabaseHelper.COLUMN_ID + "=?";
        
        String[] selectionArgs = {
            String.valueOf(bill.getBillId())
        };
        
        // 构建要更新的值
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(BillDatabaseHelper.COLUMN_DATE, newDate);
        values.put(BillDatabaseHelper.COLUMN_AMOUNT, newFare);
        values.put(BillDatabaseHelper.COLUMN_TITLE, newNoteContent);
        values.put(BillDatabaseHelper.COLUMN_TIME, newTime);
        
        BillDatabaseHelper billHelper = new BillDatabaseHelper(context);
        billHelper.updateBill(values, selection, selectionArgs, new BillDatabaseHelper.BillUpdateCallback() {
            @Override
            public void onUpdateSuccess(int rowsAffected) {
                if (callback != null) {
                    callback.onUpdateSuccess(rowsAffected);
                }
            }

            @Override
            public void onUpdateError(String error) {
                if (callback != null) {
                    callback.onUpdateError(error);
                }
            }
        });
    }
    
    public interface UpdateBillCallback {
        void onUpdateSuccess(int rowsAffected);
        void onUpdateError(String error);
    }

    public interface DatePickerCallback {
        void onDateSelected(String formattedDate);

    }
    
    public static void showDatePicker(Context context, String dateString, DatePickerCallback callback) {
        Calendar selectedDate = Calendar.getInstance();
        int year = selectedDate.get(Calendar.YEAR);
        int month = selectedDate.get(Calendar.MONTH);
        int day = selectedDate.get(Calendar.DAY_OF_MONTH);
        
        // 解析当前日期文本
        if (dateString != null && !dateString.isEmpty()) {
            try {
                String[] dateParts = dateString.split("-");
                if (dateParts.length == 3) {
                    year = Integer.parseInt(dateParts[0]);
                    month = Integer.parseInt(dateParts[1]) - 1; // Calendar月份从0开始
                    day = Integer.parseInt(dateParts[2]);
                }
            } catch (Exception e) {
                // 使用当前日期作为默认值
            }
        }
        
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                context,
                (view, selectedYear, selectedMonth, selectedDay) -> {
                    // 格式化选择的日期为YYYY-MM-DD格式
                    String formattedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay);
                    if (callback != null) {
                        callback.onDateSelected(formattedDate);
                    }
                },
                year, month, day
        );

        datePickerDialog.setTitle("选择日期");
        datePickerDialog.show();
        
        // 设置按钮颜色
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE).setTextColor(
                    context.getResources().getColor(android.R.color.holo_blue_dark));
        }
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE).setTextColor(
                    context.getResources().getColor(android.R.color.holo_red_dark));
        }
    }
    public interface MonthPickerCallback{
        void onMonthSelected(int year, int month);
    }
    public static void showDatePickerDialog(Context context, int currentYear, int currentMonth, MonthPickerCallback callback) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_date_picker);

        // 获取对话框中的控件
        NumberPicker yearPicker = dialog.findViewById(R.id.np_year);
        NumberPicker monthPicker = dialog.findViewById(R.id.np_month);
        TextView tvCancel = dialog.findViewById(R.id.tv_cancel);
        TextView tvConfirm = dialog.findViewById(R.id.tv_confirm);

        // 设置年份选择器
        yearPicker.setMinValue(2020);
        yearPicker.setMaxValue(2080);
        yearPicker.setValue(currentYear);

        // 设置月份选择器
        String[] monthDisplayValues = {"01", "02", "03", "04", "05", "06",
                "07", "08", "09", "10", "11", "12"};
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setDisplayedValues(monthDisplayValues);
        monthPicker.setValue(currentMonth);

        // 取消按钮
        tvCancel.setOnClickListener(v -> dialog.dismiss());

        // 确认按钮
        tvConfirm.setOnClickListener(v -> {
            int year = yearPicker.getValue();
            int month = monthPicker.getValue();
            callback.onMonthSelected(year, month);
            dialog.dismiss();
        });

        dialog.show();
    }

}
