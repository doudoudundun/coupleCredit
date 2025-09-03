package com.example.couplecredit.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.couplecredit.R;
import com.example.couplecredit.function.CustomToast;
import com.example.couplecredit.function.Utils;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.utils.MessageTextParser;
import com.example.couplecredit.utils.CategoryIconMapper;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天记账弹窗
 * 从聊天消息中快速创建账单记录
 * 
 * 功能特性：
 * 1. 智能解析消息内容，自动填充表单字段
 * 2. 支持手动调整所有字段
 * 3. 复用首页记账的UI设计和数据结构
 * 4. 完整的数据验证和异常处理
 * 5. 支持单身用户和情侣用户的不同记账模式
 */
public class ChatBillingDialog extends Dialog {
    
    private Context context;
    private ChatMessage sourceMessage;
    private OnBillSavedListener listener;
    
    // UI组件
    private TextView tvExpense, tvIncome;
    private EditText etAmount, etNote;
    private Spinner spinnerCategory;
    private TextView tvDate, tvTime;
    private TextView tvPayerSelf, tvPayerPartner, tvPayerShared;
    private Button btnCancel, btnSave;
    private ImageView ivClose, ivDatePicker, ivTimePicker;
    
    // 数据状态
    private boolean isExpense = true; // 默认支出
    private String selectedPayer = "自己"; // 默认自己
    private MessageTextParser.ParseResult parseResult;
    
    // 分类数据 - 使用CategoryIconMapper统一管理
    private List<String> expenseCategories = CategoryIconMapper.getExpenseCategories();
    private List<String> incomeCategories = CategoryIconMapper.getIncomeCategories();
    
    /**
     * 账单保存回调接口
     */
    public interface OnBillSavedListener {
        /**
         * 账单保存成功回调
         * @param billId 新创建的账单ID
         */
        void onBillSaved(long billId);
        
        /**
         * 账单保存失败回调
         * @param error 错误信息
         */
        void onBillSaveError(String error);
    }
    
    /**
     * 构造函数
     * @param context 上下文
     * @param message 源消息
     */
    public ChatBillingDialog(@NonNull Context context, ChatMessage message) {
        super(context, R.style.CustomDialogStyle);
        this.context = context;
        this.sourceMessage = message;
        
        initDialog();
        parseMessageAndFillForm();
    }
    
    /**
     * 设置账单保存监听器
     */
    public void setOnBillSavedListener(OnBillSavedListener listener) {
        this.listener = listener;
    }
    
    /**
     * 初始化弹窗
     */
    private void initDialog() {
        // 设置弹窗样式
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        // 加载布局
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_chat_billing, null);
        setContentView(view);
        
        // 设置弹窗大小
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        
        // 初始化UI组件
        initViews(view);
        
        // 设置事件监听器
        setupEventListeners();
        
        // 初始化分类下拉框
        setupCategorySpinner();
        
        // 设置默认日期时间
        setDefaultDateTime();
    }
    
    /**
     * 初始化UI组件
     */
    private void initViews(View view) {
        // 收支类型切换
        tvExpense = view.findViewById(R.id.tv_expense);
        tvIncome = view.findViewById(R.id.tv_income);
        
        // 输入框
        etAmount = view.findViewById(R.id.et_amount);
        etNote = view.findViewById(R.id.et_note);
        
        // 分类选择
        spinnerCategory = view.findViewById(R.id.spinner_category);
        
        // 日期时间
        tvDate = view.findViewById(R.id.tv_date);
        tvTime = view.findViewById(R.id.tv_time);
        ivDatePicker = view.findViewById(R.id.iv_date_picker);
        ivTimePicker = view.findViewById(R.id.iv_time_picker);
        
        // 支付方选择
        tvPayerSelf = view.findViewById(R.id.tv_payer_self);
        tvPayerPartner = view.findViewById(R.id.tv_payer_partner);
        tvPayerShared = view.findViewById(R.id.tv_payer_shared);
        
        // 操作按钮
        btnCancel = view.findViewById(R.id.btn_cancel);
        btnSave = view.findViewById(R.id.btn_save);
        ivClose = view.findViewById(R.id.iv_close);
    }
    
    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 收支类型切换
        tvExpense.setOnClickListener(v -> switchToExpense());
        tvIncome.setOnClickListener(v -> switchToIncome());
        
        // 日期时间选择
        ivDatePicker.setOnClickListener(v -> showDatePicker());
        tvDate.setOnClickListener(v -> showDatePicker());
        ivTimePicker.setOnClickListener(v -> showTimePicker());
        tvTime.setOnClickListener(v -> showTimePicker());
        
        // 支付方选择
        tvPayerSelf.setOnClickListener(v -> selectPayer("自己"));
        tvPayerPartner.setOnClickListener(v -> selectPayer("对方"));
        tvPayerShared.setOnClickListener(v -> selectPayer("共同"));
        
        // 操作按钮
        btnCancel.setOnClickListener(v -> dismiss());
        btnSave.setOnClickListener(v -> saveBill());
        ivClose.setOnClickListener(v -> dismiss());
    }
    
    /**
     * 设置分类下拉框
     */
    private void setupCategorySpinner() {
        updateCategorySpinner();
    }
    
    /**
     * 更新分类下拉框内容
     */
    private void updateCategorySpinner() {
        List<String> categories = isExpense ? expenseCategories : incomeCategories;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, 
            android.R.layout.simple_spinner_item, categories);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(adapter);
    }
    
    /**
     * 设置默认日期时间
     */
    private void setDefaultDateTime() {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        
        tvDate.setText(dateFormat.format(calendar.getTime()));
        tvTime.setText(timeFormat.format(calendar.getTime()));
    }
    
    /**
     * 解析消息并填充表单
     */
    private void parseMessageAndFillForm() {
        if (sourceMessage == null || sourceMessage.getContent() == null) {
            return;
        }
        
        // 使用智能文本解析器解析消息
        parseResult = MessageTextParser.parseMessage(sourceMessage.getContent());
        
        // 填充解析结果到表单
        fillFormWithParseResult();
    }
    
    /**
     * 将解析结果填充到表单
     */
    private void fillFormWithParseResult() {
        if (parseResult == null) {
            return;
        }
        
        // 填充金额
        if (parseResult.hasAmount()) {
            etAmount.setText(String.valueOf(parseResult.getAmount()));
        }
        
        // 填充日期时间
        if (parseResult.hasDate()) {
            tvDate.setText(parseResult.getDate());
        }
        if (!parseResult.getTime().isEmpty()) {
            tvTime.setText(parseResult.getTime());
        }
        
        // 设置收支类型
        if (parseResult.getIncomeType() == 1) {
            switchToIncome();
        } else {
            switchToExpense();
        }
        
        // 填充分类
        if (parseResult.hasCategory()) {
            selectCategoryInSpinner(parseResult.getCategory());
        }
        
        // 填充备注（原始消息内容）
        etNote.setText(parseResult.getNote());
    }
    
    /**
     * 在下拉框中选择指定分类
     */
    private void selectCategoryInSpinner(String category) {
        List<String> categories = isExpense ? expenseCategories : incomeCategories;
        int position = categories.indexOf(category);
        if (position >= 0) {
            spinnerCategory.setSelection(position);
        }
    }
    
    /**
     * 切换到支出模式
     */
    private void switchToExpense() {
        isExpense = true;
        
        // 更新UI状态
        tvExpense.setBackgroundResource(R.drawable.tab_selected_background);
        tvExpense.setTextColor(context.getResources().getColor(android.R.color.white));
        tvIncome.setBackgroundResource(R.drawable.tab_unselected_background);
        tvIncome.setTextColor(context.getResources().getColor(R.color.text_secondary));
        
        // 更新分类列表
        updateCategorySpinner();
        
        // 如果有解析结果且是支出分类，重新选择
        if (parseResult != null && parseResult.hasCategory() && parseResult.getIncomeType() == 0) {
            selectCategoryInSpinner(parseResult.getCategory());
        }
    }
    
    /**
     * 切换到收入模式
     */
    private void switchToIncome() {
        isExpense = false;
        
        // 更新UI状态
        tvIncome.setBackgroundResource(R.drawable.tab_selected_background);
        tvIncome.setTextColor(context.getResources().getColor(android.R.color.white));
        tvExpense.setBackgroundResource(R.drawable.tab_unselected_background);
        tvExpense.setTextColor(context.getResources().getColor(R.color.text_secondary));
        
        // 更新分类列表
        updateCategorySpinner();
    }
    
    /**
     * 选择支付方
     */
    private void selectPayer(String payer) {
        selectedPayer = payer;
        
        // 重置所有按钮状态
        tvPayerSelf.setBackgroundResource(R.drawable.payer_unselected_background);
        tvPayerSelf.setTextColor(context.getResources().getColor(R.color.text_secondary));
        tvPayerPartner.setBackgroundResource(R.drawable.payer_unselected_background);
        tvPayerPartner.setTextColor(context.getResources().getColor(R.color.text_secondary));
        tvPayerShared.setBackgroundResource(R.drawable.payer_unselected_background);
        tvPayerShared.setTextColor(context.getResources().getColor(R.color.text_secondary));
        
        // 设置选中状态
        TextView selectedView = null;
        switch (payer) {
            case "自己":
                selectedView = tvPayerSelf;
                break;
            case "对方":
                selectedView = tvPayerPartner;
                break;
            case "共同":
                selectedView = tvPayerShared;
                break;
        }
        
        if (selectedView != null) {
            selectedView.setBackgroundResource(R.drawable.payer_selected_background);
            selectedView.setTextColor(context.getResources().getColor(android.R.color.white));
        }
    }
    
    /**
     * 显示日期选择器
     */
    private void showDatePicker() {
        // 解析当前日期
        String currentDate = tvDate.getText().toString();
        Calendar calendar = Calendar.getInstance();
        
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Date date = dateFormat.parse(currentDate);
            if (date != null) {
                calendar.setTime(date);
            }
        } catch (Exception e) {
            // 使用当前日期
        }
        
        // 显示日期选择器
        android.app.DatePickerDialog datePickerDialog = new android.app.DatePickerDialog(
            context,
            (view, year, month, dayOfMonth) -> {
                String dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", 
                    year, month + 1, dayOfMonth);
                tvDate.setText(dateStr);
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        );
        
        // 设置对话框标题
        datePickerDialog.setTitle("选择日期");
        
        // 确保按钮可见 - 使用OnShowListener设置按钮样式
        datePickerDialog.setOnShowListener(dialog -> {
            // 获取确认按钮并设置样式
            android.widget.Button positiveButton = datePickerDialog.getButton(android.app.DatePickerDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setText("确认");
                positiveButton.setTextColor(context.getResources().getColor(android.R.color.holo_blue_dark));
                positiveButton.setTextSize(16);
            }
            
            // 获取取消按钮并设置样式
            android.widget.Button negativeButton = datePickerDialog.getButton(android.app.DatePickerDialog.BUTTON_NEGATIVE);
            if (negativeButton != null) {
                negativeButton.setText("取消");
                negativeButton.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                negativeButton.setTextSize(16);
            }
        });
        
        datePickerDialog.show();
    }
    
    /**
     * 显示时间选择器
     */
    private void showTimePicker() {
        // 解析当前时间
        String currentTime = tvTime.getText().toString();
        Calendar calendar = Calendar.getInstance();
        
        try {
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            Date time = timeFormat.parse(currentTime);
            if (time != null) {
                calendar.setTime(time);
            }
        } catch (Exception e) {
            // 使用当前时间
        }
        
        // 创建时间选择器
        android.app.TimePickerDialog timePickerDialog = new android.app.TimePickerDialog(
            context,
            (view, hourOfDay, minute) -> {
                String timeStr = String.format(Locale.getDefault(), "%02d:%02d:00", 
                    hourOfDay, minute);
                tvTime.setText(timeStr);
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        );
        
        // 设置对话框标题
        timePickerDialog.setTitle("选择时间");
        
        // 确保按钮可见 - 使用OnShowListener设置按钮样式
        timePickerDialog.setOnShowListener(dialog -> {
            // 获取确认按钮并设置样式
            android.widget.Button positiveButton = timePickerDialog.getButton(android.app.TimePickerDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setText("确认");
                positiveButton.setTextColor(context.getResources().getColor(android.R.color.holo_blue_dark));
                positiveButton.setTextSize(16);
            }
            
            // 获取取消按钮并设置样式
            android.widget.Button negativeButton = timePickerDialog.getButton(android.app.TimePickerDialog.BUTTON_NEGATIVE);
            if (negativeButton != null) {
                negativeButton.setText("取消");
                negativeButton.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                negativeButton.setTextSize(16);
            }
        });
        
        timePickerDialog.show();
    }
    
    /**
     * 保存账单
     */
    private void saveBill() {
        // 验证输入
        if (!validateInput()) {
            return;
        }
        
        try {
            // 获取表单数据
            double amount = Double.parseDouble(etAmount.getText().toString().trim());
            String category = (String) spinnerCategory.getSelectedItem();
            String date = tvDate.getText().toString();
            String time = tvTime.getText().toString();
            String note = etNote.getText().toString().trim();
            int incomeType = isExpense ? 0 : 1;
            
            // 如果备注为空，使用分类作为标题
            String title = note.isEmpty() ? category : note;
            
            // 调用Utils.insertBill保存账单
            Utils.insertBill(context, title, category, amount, date, time, 
                incomeType, selectedPayer, new Utils.BillInsertCallback() {
                    @Override
                    public void onInsertSuccess(long id) {
                        // 保存成功
                        CustomToast.show(context, "账单保存成功");
                        
                        if (listener != null) {
                            listener.onBillSaved(id);
                        }
                        
                        dismiss();
                    }
                    
                    @Override
                    public void onInsertError(String error) {
                        // 保存失败
                        Toast.makeText(context, "账单保存失败: " + error, Toast.LENGTH_SHORT).show();
                        
                        if (listener != null) {
                            listener.onBillSaveError(error);
                        }
                    }
                });
                
        } catch (NumberFormatException e) {
            CustomToast.show(context, "请输入有效的金额");
        } catch (Exception e) {
            Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 验证输入数据
     */
    private boolean validateInput() {
        // 验证金额
        String amountStr = etAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            CustomToast.show(context, "请输入金额");
            etAmount.requestFocus();
            return false;
        }
        
        try {
            double amount = Double.parseDouble(amountStr);
            if (amount <= 0) {
                CustomToast.show(context, "金额必须大于0");
                etAmount.requestFocus();
                return false;
            }
        } catch (NumberFormatException e) {
            CustomToast.show(context, "请输入有效的金额");
            etAmount.requestFocus();
            return false;
        }
        
        // 验证分类
        if (spinnerCategory.getSelectedItem() == null) {
            CustomToast.show(context, "请选择分类");
            return false;
        }
        
        // 验证日期
        String date = tvDate.getText().toString();
        if (date.isEmpty()) {
            CustomToast.show(context, "请选择日期");
            return false;
        }
        
        return true;
    }
}