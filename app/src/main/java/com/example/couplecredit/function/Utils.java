package com.example.couplecredit.function;

import android.app.DatePickerDialog;
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

import com.example.couplecredit.BillBean;
import com.example.couplecredit.activity.CategoriesBillViewActivity;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.R;
import androidx.appcompat.app.AlertDialog;

import java.util.Calendar;

public final class Utils {
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
        int userId = UserInfoManager.getCurrentUserId(context);
        if (userId < 0) {
            Log.e("Utils", "获取用户信息失败");
            if (callback != null) {
                callback.onInsertError("获取用户信息失败");
            }
            return;
        }

        AuthApiClient.createBill(userId, billOwner, title, type, amount, date, time, incomeType, new AuthApiClient.BillCallback() {
            @Override
            public void onSuccess(com.example.couplecredit.api.AuthApiModels.BillResponse response) {
                BillDatabaseHelper.clearCache();
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
    
    // 辅助方法：执行实际的数据库插入操作
    @Deprecated
    private static void insertBillToDatabase(Context context, android.content.ContentValues values, Integer relationshipId, int userId, BillInsertCallback callback) {
        BillDatabaseHelper billHelper = new BillDatabaseHelper(context);
        billHelper.insertBill(values, new BillDatabaseHelper.BillInsertCallback() {
            @Override
            public void onInsertSuccess(long id) {
                // 插入成功，清空缓存
                BillDatabaseHelper.clearCache();
                if (callback != null) {
                    callback.onInsertSuccess(id);
                }
            }

            @Override
            public void onInsertError(String error) {
                Log.e("Utils", "账单插入失败: " + error);
                if (callback != null) {
                    callback.onInsertError(error);
                }
            }
        });
    }
    
    @Deprecated
    public static void insertBill(Context context,int userId, String title, String type, double amount, String date, String time, int incomeType) {
        insertBill(context, title, type, amount, date, time, incomeType, "自己", null);
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
        
        // 删除账单
        
        dbHelper.deleteBill(selection, selectionArgs, new BillDatabaseHelper.BillDeleteCallback() {
            @Override
            public void onDeleteSuccess(int rowsDeleted) {
                // 删除成功，清空缓存
                BillDatabaseHelper.clearCache();
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
        updateBill(context, bill, newDate, newFare, newNoteContent, newTime, null, callback);
    }
    
    public static void updateBill(Context context, BillBean bill, String newDate, double newFare, String newNoteContent, String newTime, Integer isHelp, UpdateBillCallback callback) {
        // 使用bill_id作为唯一标识进行更新
        String selection = BillDatabaseHelper.COLUMN_ID + "=?";
        
        String[] selectionArgs = {
            String.valueOf(bill.getBillId())
        };
        
        // 构建要更新的值
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(BillDatabaseHelper.COLUMN_DATE, newDate);
        values.put(BillDatabaseHelper.COLUMN_AMOUNT, newFare);
        // 如果备注为空，使用原账单的categoryName作为title；否则使用备注内容
        String titleToUpdate = (newNoteContent == null || newNoteContent.trim().isEmpty()) ? bill.getCategoryName() : newNoteContent;
        values.put(BillDatabaseHelper.COLUMN_TITLE, titleToUpdate);
        values.put(BillDatabaseHelper.COLUMN_TIME, newTime);
        if (isHelp != null) {
            values.put("is_help", isHelp);
        }
        
        BillDatabaseHelper billHelper = new BillDatabaseHelper(context);
        billHelper.updateBill(values, selection, selectionArgs, new BillDatabaseHelper.BillUpdateCallback() {
            @Override
            public void onUpdateSuccess(int rowsAffected) {
                // 更新成功，清空缓存
                BillDatabaseHelper.clearCache();
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
        
        // 使用AlertDialog.Builder创建自定义日期选择器，确保按钮显示
        android.widget.DatePicker datePicker = new android.widget.DatePicker(context);
        datePicker.init(year, month, day, null);
        
        new android.app.AlertDialog.Builder(context)
                .setTitle("选择日期")
                .setView(datePicker)
                .setPositiveButton("确定", (dialog, which) -> {
                    int selectedYear = datePicker.getYear();
                    int selectedMonth = datePicker.getMonth();
                    int selectedDay = datePicker.getDayOfMonth();
                    
                    // 格式化选择的日期为YYYY-MM-DD格式
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


    public interface UpdateBillCallback {
        void onUpdateSuccess(int rowsAffected);
        void onUpdateError(String error);
    }

    public static void enterEditMode(Context context, AlertDialog mDialog, TextView tvDate, TextView tvFare, TextView tvNoteContent,
                                     EditText etFare, EditText etNoteContent,
                                     Button btnEdit, Button btnDelete, ImageButton btnConfirm, ImageButton btnCancel){
        // 隐藏TextView，显示EditText
        //if (tvCategoryName != null) tvCategoryName.setVisibility(View.GONE);
        if (tvDate != null) tvDate.setOnClickListener(v-> {//设置日期选择器
            Utils.showDatePicker(context, tvDate.getText().toString(),
                    formattedDate -> tvDate.setText(formattedDate));
        });
        if (tvFare != null) tvFare.setVisibility(View.GONE);
        if (tvNoteContent != null) tvNoteContent.setVisibility(View.GONE);

        //if (etCategoryName != null) etCategoryName.setVisibility(View.VISIBLE);

        if (etFare != null) etFare.setVisibility(View.VISIBLE); //只让输入数字
        if (etNoteContent != null) etNoteContent.setVisibility(View.VISIBLE);

        // 隐藏修改和删除按钮，显示确认和取消按钮
        if (btnEdit != null) btnEdit.setVisibility(View.GONE);
        if (btnDelete != null) btnDelete.setVisibility(View.GONE);
        if (btnConfirm != null) btnConfirm.setVisibility(View.VISIBLE);
        if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
        mDialog.setTitle("修改账单");
    }

    public static void exitEditMode(AlertDialog mDialog, TextView tvDate, TextView tvFare, TextView tvNoteContent,
                              EditText etFare, EditText etNoteContent,
                              Button btnEdit, Button btnDelete, ImageButton btnConfirm, ImageButton btnCancel) {
        // 更新TextView的内容为EditText中的值

        if (tvFare != null && etFare != null) {
            String fareText = etFare.getText().toString();
            // 判空逻辑：如果EditText中没有货币符号，则添加
            if (!fareText.startsWith("￥")) {
                tvFare.setText("￥" + fareText);
            } else {
                tvFare.setText(fareText);
            }
        }
        if (tvNoteContent != null && etNoteContent != null) {
            tvNoteContent.setText(etNoteContent.getText().toString());
        }

        // 显示TextView，隐藏EditText

        if (tvDate != null) tvDate.setVisibility(View.VISIBLE);
        if (tvFare != null) tvFare.setVisibility(View.VISIBLE);
        if (tvNoteContent != null) tvNoteContent.setVisibility(View.VISIBLE);


        if (etFare != null) etFare.setVisibility(View.GONE);
        if (etNoteContent != null) etNoteContent.setVisibility(View.GONE);

        // 显示修改和删除按钮，隐藏确认和取消按钮
        if (btnEdit != null) btnEdit.setVisibility(View.VISIBLE);
        if (btnDelete != null) btnDelete.setVisibility(View.VISIBLE);
        if (btnConfirm != null) btnConfirm.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        mDialog.setTitle("账单详情");
    }
}
