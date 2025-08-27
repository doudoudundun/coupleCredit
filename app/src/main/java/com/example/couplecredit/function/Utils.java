package com.example.couplecredit.function;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.util.Log;
import android.view.Window;
import android.widget.NumberPicker;
import android.widget.TextView;

import com.example.couplecredit.BillBean;
import com.example.couplecredit.database.BillDatabaseHelper;
import com.example.couplecredit.R;
import com.example.couplecredit.database.CoupleRelationshipHelper;

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
        UserInfoManager.getCurrentUserInfo(context, new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(BillDatabaseHelper.USER_ID, userId);
                values.put(BillDatabaseHelper.COLUMN_TITLE, title);
                values.put(BillDatabaseHelper.COLUMN_TYPE, type);
                values.put(BillDatabaseHelper.COLUMN_AMOUNT, amount);
                values.put(BillDatabaseHelper.COLUMN_DATE, date);
                values.put(BillDatabaseHelper.COLUMN_TIME, time);
                values.put(BillDatabaseHelper.COLUMN_INCOME_TYPE, incomeType);
                
                // 根据billOwner设置is_help字段：自己和共同为0，对方为1
                int isHelpValue = "对方".equals(billOwner) ? 1 : 0;
                values.put("is_help", isHelpValue);
                
                // 添加relationship_id信息
                if (relationshipId != null) {
                    values.put("relationship_id", relationshipId);
                }
                
                // 根据billOwner设置owner字段
                if ("自己".equals(billOwner)) {
                    // 为自己记账，需要查询自己在情侣关系中的角色
                    if (relationshipId != null) {
                        CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
                        coupleHelper.getUserRole(userId, new CoupleRelationshipHelper.UserRoleCallback() {
                            @Override
                            public void onRoleFound(int ownerId) {
                                // 设置为自己
                                values.put("owner", ownerId);
                                insertBillToDatabase(context, values, relationshipId, userId, callback);
                            }
                            
                            @Override
                            public void onNoRelationshipFound() {
                                Log.e("Utils", "未找到情侣关系");
                                if (callback != null) {
                                    callback.onInsertError("未找到情侣关系");
                                }
                            }
                            
                            @Override
                            public void onError(String error) {
                                Log.e("Utils", "查询用户角色失败: " + error);
                                if (callback != null) {
                                    callback.onInsertError("查询用户角色失败: " + error);
                                }
                            }
                        });
                    } else {
                        // 没有情侣关系，使用默认值1
                        // 设置为自己(无关系)
                        values.put("owner", 1);
                        insertBillToDatabase(context, values, relationshipId, userId, callback);
                    }
                } else if ("对方".equals(billOwner) && relationshipId != null) {
                    // 为对方记账，需要查询对方在情侣关系中的角色
                    CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
                    coupleHelper.getUserRole(userId, new CoupleRelationshipHelper.UserRoleCallback() {
                        @Override
                        public void onRoleFound(int currentUserOwnerId) {
                            // 对方的角色与当前用户相反：如果当前用户是1(邀请者)，对方就是2(被邀请者)，反之亦然
                            int partnerOwnerId = (currentUserOwnerId == 1) ? 2 : 1;
                            // 设置为对方
                            values.put("owner", partnerOwnerId);
                            insertBillToDatabase(context, values, relationshipId, userId, callback);
                        }
                        
                        @Override
                        public void onNoRelationshipFound() {
                            Log.e("Utils", "未找到情侣关系，无法为对方记账");
                            if (callback != null) {
                                callback.onInsertError("未找到情侣关系，无法为对方记账");
                            }
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e("Utils", "查询用户角色失败: " + error);
                            if (callback != null) {
                                callback.onInsertError("查询用户角色失败: " + error);
                            }
                        }
                    });
                    return; // 异步处理，直接返回
                } else if ("共同".equals(billOwner) && relationshipId != null) {
                    // 共同账单，owner设置为3
                    // 设置为共同
                    values.put("owner", 3);
                    insertBillToDatabase(context, values, relationshipId, userId, callback);
                } else {
                    Log.e("Utils", "不支持的billOwner类型或缺少情侣关系: " + billOwner + ", relationshipId=" + relationshipId);
                    if (callback != null) {
                        callback.onInsertError("不支持的billOwner类型或缺少情侣关系: " + billOwner);
                    }
                }

                // 这部分代码已移动到insertBillToDatabase方法中
            }
            
            @Override
            public void onError(String error) {
                Log.e("Utils", "获取用户信息失败: " + error);
                if (callback != null) {
                    callback.onInsertError("获取用户信息失败: " + error);
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
