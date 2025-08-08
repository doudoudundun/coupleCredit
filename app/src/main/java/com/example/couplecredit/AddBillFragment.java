package com.example.couplecredit;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class AddBillFragment extends Fragment {

    private static final int REQUEST_IMAGE_PICK = 1001;
    private static final int REQUEST_PERMISSION = 1002;
    
    private TextView tvExpense, tvIncome;
    private TextView tvAmountDisplay;
    private EditText etNote;
    private TextView tvDate, tvPhoto, tvSelf, tvPartner, tvShared;
    private View indicatorExpense, indicatorIncome;
    private GridLayout gridCategories;
    
    private String selectedCategory = "";
    private String billType = "支出"; // 默认支出
    private String billOwner = "自己"; // 默认自己
    private StringBuilder currentAmount = new StringBuilder("0");
    private boolean isExpense = true;
    private Calendar selectedDate = Calendar.getInstance(); // 选中的日期

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_add_bill, container, false);

        // 初始化UI组件
        initViews(view);
        
        // 设置监听器
        setupListeners();
        
        // 初始化默认状态
        updateDateDisplay();
        updateAmountDisplay();
        switchToExpense(); // 默认显示支出模式
        
        // 初始化分类项的样式
        initializeCategoryStyles(view);
        
        return view;
    }

    private void initViews(View view) {
        // 支出/收入切换
        tvExpense = view.findViewById(R.id.tv_expense);
        tvIncome = view.findViewById(R.id.tv_income);
        indicatorExpense = view.findViewById(R.id.indicator_expense);
        indicatorIncome = view.findViewById(R.id.indicator_income);
        
        // 分类选择区域
        gridCategories = view.findViewById(R.id.grid_categories);
        
        // 金额显示和备注
        tvAmountDisplay = view.findViewById(R.id.tv_amount_display);
        etNote = view.findViewById(R.id.et_note);
        
        // 底部操作按钮
        tvDate = view.findViewById(R.id.tv_date);
        tvPhoto = view.findViewById(R.id.tv_photo);
        tvSelf = view.findViewById(R.id.tv_self);
        tvPartner = view.findViewById(R.id.tv_partner);
        tvShared = view.findViewById(R.id.tv_shared);
        
        // 设置分类选择监听器
        setupCategoryListeners(view);
        
        // 设置数字键盘监听器
        setupKeypadListeners(view);
    }

    private void setupListeners() {
        // 支出/收入切换
        tvExpense.setOnClickListener(v -> switchToExpense());
        tvIncome.setOnClickListener(v -> switchToIncome());
        
        // 日期选择
        tvDate.setOnClickListener(v -> showDatePicker());
        
        // 照片选择
        tvPhoto.setOnClickListener(v -> selectPhoto());
        
        // 账单归属选择
        tvSelf.setOnClickListener(v -> selectOwner("自己", tvSelf));
        tvPartner.setOnClickListener(v -> selectOwner("对方", tvPartner));
        tvShared.setOnClickListener(v -> selectOwner("共同", tvShared));
    }
    
    private void switchToExpense() {
        isExpense = true;
        billType = "支出";
        tvExpense.setTextColor(getResources().getColor(android.R.color.holo_red_light));
        tvExpense.setTextSize(16);
        tvExpense.setTypeface(null, Typeface.BOLD); // 支出字体加粗
        tvIncome.setTextColor(getResources().getColor(android.R.color.darker_gray));
        tvIncome.setTextSize(16);
        tvIncome.setTypeface(null, Typeface.NORMAL); // 收入字体不加粗
        
        // 显示支出指示器，隐藏收入指示器
        indicatorExpense.setVisibility(View.VISIBLE);
        indicatorIncome.setVisibility(View.INVISIBLE);
        
        // 显示支出分类
        showExpenseCategories();
        updateAmountDisplay();
    }
    
    private void switchToIncome() {
        isExpense = false;
        billType = "收入";
        tvIncome.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        tvIncome.setTextSize(16);
        tvIncome.setTypeface(null, Typeface.BOLD); // 收入字体加粗
        tvExpense.setTextColor(getResources().getColor(android.R.color.darker_gray));
        tvExpense.setTextSize(16);
        tvExpense.setTypeface(null, Typeface.NORMAL); // 支出字体不加粗
        
        // 显示收入指示器，隐藏支出指示器
        indicatorIncome.setVisibility(View.VISIBLE);
        indicatorIncome.setBackgroundColor(getResources().getColor(android.R.color.holo_orange_light));
        indicatorExpense.setVisibility(View.INVISIBLE);
        
        // 显示收入分类
        showIncomeCategories();
        updateAmountDisplay();
    }
    
    private void selectOwner(String owner, TextView selectedView) {
        billOwner = owner;
        
        // 重置所有按钮样式
        resetOwnerButtons();
        
        // 设置选中样式
        selectedView.setBackgroundColor(getResources().getColor(android.R.color.holo_orange_light));
        selectedView.setTextColor(getResources().getColor(android.R.color.white));
    }
    
    private void resetOwnerButtons() {
        int defaultBg = getResources().getColor(android.R.color.darker_gray);
        int defaultTextColor = getResources().getColor(android.R.color.black);
        
        tvSelf.setBackgroundColor(defaultBg);
        tvSelf.setTextColor(defaultTextColor);
        tvPartner.setBackgroundColor(defaultBg);
        tvPartner.setTextColor(defaultTextColor);
        tvShared.setBackgroundColor(defaultBg);
        tvShared.setTextColor(defaultTextColor);
    }
    
    private void selectPhoto() {
        // 检查权限
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), 
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_PERMISSION);
            return;
        }
        
        // 打开相册
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_IMAGE_PICK && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedImage = data.getData();
            if (selectedImage != null) {
                Toast.makeText(getContext(), "照片选择成功", Toast.LENGTH_SHORT).show();
                // 这里可以添加显示选中照片的逻辑
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                selectPhoto();
            } else {
                Toast.makeText(getContext(), "需要存储权限才能选择照片", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupCategoryListeners(View view) {
        // 餐品
        LinearLayout categoryFood = view.findViewById(R.id.category_food);
        categoryFood.setOnClickListener(v -> {
            try {
                Log.d("AddBillFragment", "Category food clicked");
                selectedCategory = "餐品";
                updateCategorySelection(view, categoryFood);
                Log.d("AddBillFragment", "Category food selection completed");
            } catch (Exception e) {
                Log.e("AddBillFragment", "Error in category food click", e);
            }
        });

        // 饮品
        LinearLayout categoryDrink = view.findViewById(R.id.category_drink);
        categoryDrink.setOnClickListener(v -> {
            selectedCategory = "饮品";
            updateCategorySelection(view, categoryDrink);
        });

        // 水果
        LinearLayout categoryFruit = view.findViewById(R.id.category_fruit);
        categoryFruit.setOnClickListener(v -> {
            selectedCategory = "水果";
            updateCategorySelection(view, categoryFruit);
        });

        // 购物
        LinearLayout categoryShopping = view.findViewById(R.id.category_shopping);
        categoryShopping.setOnClickListener(v -> {
            selectedCategory = "购物";
            updateCategorySelection(view, categoryShopping);
        });

        // 交通
        LinearLayout categoryTransport = view.findViewById(R.id.category_transport);
        categoryTransport.setOnClickListener(v -> {
            selectedCategory = "交通";
            updateCategorySelection(view, categoryTransport);
        });

        // 住宿
        LinearLayout categoryHotel = view.findViewById(R.id.category_hotel);
        categoryHotel.setOnClickListener(v -> {
            selectedCategory = "住宿";
            updateCategorySelection(view, categoryHotel);
        });

        // 日常
        LinearLayout categoryDaily = view.findViewById(R.id.category_daily);
        categoryDaily.setOnClickListener(v -> {
            selectedCategory = "日常";
            updateCategorySelection(view, categoryDaily);
        });

        // 学习
        LinearLayout categoryStudy = view.findViewById(R.id.category_study);
        categoryStudy.setOnClickListener(v -> {
            selectedCategory = "学习";
            updateCategorySelection(view, categoryStudy);
        });

        // 娱乐
        LinearLayout categoryEntertainment = view.findViewById(R.id.category_entertainment);
        categoryEntertainment.setOnClickListener(v -> {
            selectedCategory = "娱乐";
            updateCategorySelection(view, categoryEntertainment);
        });

        // 化妆
        LinearLayout categoryCosmetic = view.findViewById(R.id.category_cosmetic);
        categoryCosmetic.setOnClickListener(v -> {
            selectedCategory = "化妆";
            updateCategorySelection(view, categoryCosmetic);
        });

        // 旅游
        LinearLayout categoryTravel = view.findViewById(R.id.category_travel);
        categoryTravel.setOnClickListener(v -> {
            selectedCategory = "旅游";
            updateCategorySelection(view, categoryTravel);
        });

        // 医疗
        LinearLayout categoryMedical = view.findViewById(R.id.category_medical);
        categoryMedical.setOnClickListener(v -> {
            selectedCategory = "医疗";
            updateCategorySelection(view, categoryMedical);
        });

        // 会员
        LinearLayout categoryMember = view.findViewById(R.id.category_member);
        categoryMember.setOnClickListener(v -> {
            selectedCategory = "会员";
            updateCategorySelection(view, categoryMember);
        });

        // 通讯
        LinearLayout categoryCommunication = view.findViewById(R.id.category_communication);
        categoryCommunication.setOnClickListener(v -> {
            selectedCategory = "通讯";
            updateCategorySelection(view, categoryCommunication);
        });

        // 社交
        LinearLayout categorySocial = view.findViewById(R.id.category_social);
        categorySocial.setOnClickListener(v -> {
            selectedCategory = "社交";
            updateCategorySelection(view, categorySocial);
        });

        // 投资
        LinearLayout categoryInvestment = view.findViewById(R.id.category_investment);
        categoryInvestment.setOnClickListener(v -> {
            selectedCategory = "投资";
            updateCategorySelection(view, categoryInvestment);
        });

        // 育儿
        LinearLayout categoryParenting = view.findViewById(R.id.category_parenting);
        categoryParenting.setOnClickListener(v -> {
            selectedCategory = "育儿";
            updateCategorySelection(view, categoryParenting);
        });

        // 宠物
        LinearLayout categoryPet = view.findViewById(R.id.category_pet);
        categoryPet.setOnClickListener(v -> {
            selectedCategory = "宠物";
            updateCategorySelection(view, categoryPet);
        });

        // 装修
        LinearLayout categoryDecoration = view.findViewById(R.id.category_decoration);
        categoryDecoration.setOnClickListener(v -> {
            selectedCategory = "装修";
            updateCategorySelection(view, categoryDecoration);
        });

        // 其他
        LinearLayout categoryOther = view.findViewById(R.id.category_other);
        categoryOther.setOnClickListener(v -> {
            selectedCategory = "其他";
            updateCategorySelection(view, categoryOther);
        });
    }
    
    private void setupKeypadListeners(View view) {
        // 数字键
        view.findViewById(R.id.key_0).setOnClickListener(v -> inputNumber("0"));
        view.findViewById(R.id.key_1).setOnClickListener(v -> inputNumber("1"));
        view.findViewById(R.id.key_2).setOnClickListener(v -> inputNumber("2"));
        view.findViewById(R.id.key_3).setOnClickListener(v -> inputNumber("3"));
        view.findViewById(R.id.key_4).setOnClickListener(v -> inputNumber("4"));
        view.findViewById(R.id.key_5).setOnClickListener(v -> inputNumber("5"));
        view.findViewById(R.id.key_6).setOnClickListener(v -> inputNumber("6"));
        view.findViewById(R.id.key_7).setOnClickListener(v -> inputNumber("7"));
        view.findViewById(R.id.key_8).setOnClickListener(v -> inputNumber("8"));
        view.findViewById(R.id.key_9).setOnClickListener(v -> inputNumber("9"));
        
        // 小数点
        view.findViewById(R.id.key_dot).setOnClickListener(v -> inputDot());
        
        // 删除
        view.findViewById(R.id.key_delete).setOnClickListener(v -> deleteLastChar());
        
        // 加减号
        view.findViewById(R.id.key_plus).setOnClickListener(v -> inputOperator("+"));
        view.findViewById(R.id.key_minus).setOnClickListener(v -> inputOperator("-"));
        
        // 保存
        view.findViewById(R.id.key_save).setOnClickListener(v -> saveBill());
        
        // 再记
        view.findViewById(R.id.key_again).setOnClickListener(v -> clearAndContinue());
    }
    
    private void inputNumber(String number) {
        if (currentAmount.toString().equals("0")) {
            currentAmount = new StringBuilder(number);
        } else {
            currentAmount.append(number);
        }
        updateAmountDisplay();
    }
    
    private void inputDot() {
        if (!currentAmount.toString().contains(".")) {
            currentAmount.append(".");
            updateAmountDisplay();
        }
    }
    
    private void deleteLastChar() {
        if (currentAmount.length() > 1) {
            currentAmount.deleteCharAt(currentAmount.length() - 1);
        } else {
            currentAmount = new StringBuilder("0");
        }
        updateAmountDisplay();
    }
    
    private void inputOperator(String operator) {
        // 简单实现：暂时只显示提示
        Toast.makeText(getContext(), "运算功能待实现", Toast.LENGTH_SHORT).show();
    }
    
    private void updateAmountDisplay() {
        String prefix = isExpense ? "¥" : "¥";
        tvAmountDisplay.setText(prefix + currentAmount.toString());
        
        int color = isExpense ? 
            getResources().getColor(android.R.color.holo_red_light) : 
            getResources().getColor(android.R.color.holo_green_light);
        tvAmountDisplay.setTextColor(color);
    }
    
    private void showDatePicker() {
        int year = selectedDate.get(Calendar.YEAR);
        int month = selectedDate.get(Calendar.MONTH);
        int day = selectedDate.get(Calendar.DAY_OF_MONTH);
        
        DatePickerDialog datePickerDialog = new DatePickerDialog(
            getContext(),
            new DatePickerDialog.OnDateSetListener() {
                @Override
                public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                    selectedDate.set(Calendar.YEAR, year);
                    selectedDate.set(Calendar.MONTH, month);
                    selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    updateDateDisplay();
                    Toast.makeText(getContext(), "日期已更新", Toast.LENGTH_SHORT).show();
                }
            },
            year, month, day
        );
        
        // 设置对话框标题
        datePickerDialog.setTitle("选择日期");
        
        // 设置确定按钮文本和行为
        datePickerDialog.setButton(DatePickerDialog.BUTTON_POSITIVE, "确定", datePickerDialog);
        
        // 设置取消按钮文本
        datePickerDialog.setButton(DatePickerDialog.BUTTON_NEGATIVE, "取消", 
            (dialog, which) -> {
                dialog.dismiss();
                Toast.makeText(getContext(), "已取消选择", Toast.LENGTH_SHORT).show();
            });
        
        datePickerDialog.show();
        
        // 设置按钮颜色，确保按钮文字可见
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE).setTextColor(
                getResources().getColor(android.R.color.holo_blue_dark));
        }
        if (datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE) != null) {
            datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE).setTextColor(
                getResources().getColor(android.R.color.holo_red_dark));
        }
    }
    
    private void updateDateDisplay() {
        SimpleDateFormat sdf = new SimpleDateFormat("M月d日", Locale.CHINA);
        tvDate.setText(sdf.format(selectedDate.getTime()));
    }

    private void updateCategorySelection(View rootView, LinearLayout selectedView) {
        try {
            Log.d("AddBillFragment", "updateCategorySelection called");
            
            // 重置所有分类的文字颜色
            resetCategoryTextColors(rootView);
            Log.d("AddBillFragment", "resetCategoryTextColors completed");
            
            // 设置选中分类的文字颜色为黄色
            if (selectedView != null) {
                TextView textView = selectedView.findViewById(R.id.tv_category_name);
                if (textView != null) {
                    textView.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                    Log.d("AddBillFragment", "Selected category text color set to yellow");
                } else {
                    Log.w("AddBillFragment", "TextView not found in selectedView");
                }
            } else {
                Log.w("AddBillFragment", "selectedView is null");
            }
        } catch (Exception e) {
            Log.e("AddBillFragment", "Error in updateCategorySelection", e);
        }
    }

    private void resetCategoryTextColors(View rootView) {
        try {
            Log.d("AddBillFragment", "resetCategoryTextColors called");
            
            // 重置所有分类文字颜色为默认颜色
            int defaultTextColor = getResources().getColor(android.R.color.darker_gray);
            
            LinearLayout[] categories = {
                rootView.findViewById(R.id.category_food),
                rootView.findViewById(R.id.category_drink),
                rootView.findViewById(R.id.category_fruit),
                rootView.findViewById(R.id.category_shopping),
                rootView.findViewById(R.id.category_transport),
                rootView.findViewById(R.id.category_hotel),
                rootView.findViewById(R.id.category_daily),
                rootView.findViewById(R.id.category_study),
                rootView.findViewById(R.id.category_entertainment),
                rootView.findViewById(R.id.category_cosmetic),
                rootView.findViewById(R.id.category_travel),
                rootView.findViewById(R.id.category_medical),
                rootView.findViewById(R.id.category_member),
                rootView.findViewById(R.id.category_communication),
                rootView.findViewById(R.id.category_social),
                rootView.findViewById(R.id.category_investment),
                rootView.findViewById(R.id.category_parenting),
                rootView.findViewById(R.id.category_pet),
                rootView.findViewById(R.id.category_decoration),
                rootView.findViewById(R.id.category_other)
            };
            
            for (int i = 0; i < categories.length; i++) {
                LinearLayout category = categories[i];
                if (category != null) {
                    TextView textView = category.findViewById(R.id.tv_category_name);
                    if (textView != null) {
                        textView.setTextColor(defaultTextColor);
                    }
                } else {
                    Log.w("AddBillFragment", "Category at index " + i + " is null");
                }
            }
            
            Log.d("AddBillFragment", "resetCategoryTextColors completed successfully");
        } catch (Exception e) {
            Log.e("AddBillFragment", "Error in resetCategoryTextColors", e);
        }
    }

    private void resetCategoryBackgrounds(View rootView) {
        try {
            Log.d("AddBillFragment", "resetCategoryBackgrounds called");
            
            // 重置所有分类背景
            int defaultBackground = android.R.attr.selectableItemBackground;
            
            LinearLayout[] categories = {
                rootView.findViewById(R.id.category_food),
                rootView.findViewById(R.id.category_drink),
                rootView.findViewById(R.id.category_fruit),
                rootView.findViewById(R.id.category_shopping),
                rootView.findViewById(R.id.category_transport),
                rootView.findViewById(R.id.category_hotel),
                rootView.findViewById(R.id.category_daily),
                rootView.findViewById(R.id.category_study),
                rootView.findViewById(R.id.category_entertainment),
                rootView.findViewById(R.id.category_cosmetic),
                rootView.findViewById(R.id.category_travel),
                rootView.findViewById(R.id.category_medical),
                rootView.findViewById(R.id.category_member),
                rootView.findViewById(R.id.category_communication),
                rootView.findViewById(R.id.category_social),
                rootView.findViewById(R.id.category_investment),
                rootView.findViewById(R.id.category_parenting),
                rootView.findViewById(R.id.category_pet),
                rootView.findViewById(R.id.category_decoration),
                rootView.findViewById(R.id.category_other)
            };
            
            for (int i = 0; i < categories.length; i++) {
                LinearLayout category = categories[i];
                if (category != null) {
                    category.setBackgroundResource(defaultBackground);
                } else {
                    Log.w("AddBillFragment", "Category at index " + i + " is null");
                }
            }
            
            Log.d("AddBillFragment", "resetCategoryBackgrounds completed successfully");
        } catch (Exception e) {
            Log.e("AddBillFragment", "Error in resetCategoryBackgrounds", e);
        }
    }

    private void saveBill() {
        String note = etNote.getText().toString().trim();
        
        // 验证输入
        if (currentAmount.toString().equals("0") || currentAmount.toString().isEmpty()) {
            Toast.makeText(getContext(), "请输入金额", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (selectedCategory.isEmpty()) {
            Toast.makeText(getContext(), "请选择分类", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            double amount = Double.parseDouble(currentAmount.toString());
            if (amount <= 0) {
                Toast.makeText(getContext(), "金额必须大于0", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 这里可以添加保存账单到数据库的逻辑
            Toast.makeText(getContext(), 
                String.format("账单保存成功！\n类型：%s\n金额：%.2f\n分类：%s\n归属：%s\n备注：%s", 
                    billType, amount, selectedCategory, billOwner, note.isEmpty() ? "无" : note), 
                Toast.LENGTH_LONG).show();
            
            // 清空输入
            clearInputs();
            
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void clearAndContinue() {
        // 保持分类和归属选择，只清空金额和备注
        currentAmount = new StringBuilder("0");
        etNote.setText("");
        updateAmountDisplay();
        Toast.makeText(getContext(), "已清空，可继续记账", Toast.LENGTH_SHORT).show();
    }
    
    private void clearInputs() {
        currentAmount = new StringBuilder("0");
        etNote.setText("");
        selectedCategory = "";
        billOwner = "自己";
        
        // 重置UI状态
        updateAmountDisplay();
        resetOwnerButtons();
        tvSelf.setBackgroundColor(getResources().getColor(android.R.color.holo_orange_light));
        tvSelf.setTextColor(getResources().getColor(android.R.color.white));
        
        if (getView() != null) {
            resetCategoryTextColors(getView());
        }
    }
    
    private void showExpenseCategories() {
         // 显示所有支出分类项
         View rootView = getView();
         if (rootView != null) {
             // 恢复支出分类的原始样式
             setCategoryStyle(rootView, R.id.category_food, "餐品", R.drawable.circle_bg_orange, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_drink, "饮品", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_fruit, "水果", R.drawable.circle_bg_green, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_shopping, "购物", R.drawable.circle_bg_purple, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_transport, "交通", R.drawable.circle_bg_yellow, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_hotel, "住宿", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_daily, "日常", R.drawable.circle_bg_red, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_study, "学习", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_entertainment, "娱乐", R.drawable.circle_bg_teal, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_cosmetic, "化妆", R.drawable.circle_bg_pink, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_travel, "旅游", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_medical, "医疗", R.drawable.circle_bg_green, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_member, "会员", R.drawable.circle_bg_yellow, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_communication, "通讯", R.drawable.circle_bg_gray, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_social, "人情", R.drawable.circle_bg_red, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_investment, "投资", R.drawable.circle_bg_red, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_parenting, "亲子", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_pet, "宠物", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_decoration, "装修", R.drawable.circle_bg_gray, R.drawable.ic_favorite);
             setCategoryStyle(rootView, R.id.category_other, "其他", R.drawable.circle_bg_green, R.drawable.ic_favorite);

             // 显示所有支出分类项
             rootView.findViewById(R.id.category_food).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_drink).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_fruit).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_shopping).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_transport).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_hotel).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_daily).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_study).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_entertainment).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_cosmetic).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_travel).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_medical).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_member).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_communication).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_social).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_investment).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_parenting).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_pet).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_decoration).setVisibility(View.VISIBLE);
             rootView.findViewById(R.id.category_other).setVisibility(View.VISIBLE);
             
             // 隐藏收入分类项（如果存在）
             hideIncomeOnlyCategories(rootView);
         }
     }
    
    private void showIncomeCategories() {
        // 隐藏支出专用分类
        View rootView = getView();
        if (rootView != null) {
            rootView.findViewById(R.id.category_food).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_drink).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_fruit).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_shopping).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_transport).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_hotel).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_daily).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_study).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_entertainment).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_cosmetic).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_travel).setVisibility(View.GONE);
            rootView.findViewById(R.id.category_medical).setVisibility(View.GONE);
            
            // 显示收入分类项
            showIncomeOnlyCategories(rootView);
        }
    }
    
    private void hideIncomeOnlyCategories(View rootView) {
        // 隐藏收入专用分类项（如果存在的话）
        // 这里可以根据需要添加收入专用分类的隐藏逻辑
    }
    
    private void showIncomeOnlyCategories(View rootView) {
         // 显示收入分类项，重用现有的分类项并修改样式
         
         // 为收入分类设置不同的样式
         setCategoryStyle(rootView, R.id.category_food, "工资", R.drawable.circle_bg_yellow, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_drink, "兼职", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_fruit, "理财", R.drawable.circle_bg_red, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_shopping, "礼金", R.drawable.circle_bg_orange, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_transport, "其他", R.drawable.circle_bg_green, R.drawable.ic_favorite);
         
         // 显示收入分类项（只显示第一行的5个）
         rootView.findViewById(R.id.category_food).setVisibility(View.VISIBLE);
         rootView.findViewById(R.id.category_drink).setVisibility(View.VISIBLE);
         rootView.findViewById(R.id.category_fruit).setVisibility(View.VISIBLE);
         rootView.findViewById(R.id.category_shopping).setVisibility(View.VISIBLE);
         rootView.findViewById(R.id.category_transport).setVisibility(View.VISIBLE);
         
         // 隐藏所有其他分类项
         rootView.findViewById(R.id.category_hotel).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_daily).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_study).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_entertainment).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_cosmetic).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_travel).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_medical).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_member).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_communication).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_social).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_investment).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_parenting).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_pet).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_decoration).setVisibility(View.GONE);
         rootView.findViewById(R.id.category_other).setVisibility(View.GONE);
     }
     
     private void updateCategoryText(View rootView, int categoryId, String newText) {
         View categoryView = rootView.findViewById(categoryId);
         if (categoryView != null) {
             TextView textView = categoryView.findViewById(R.id.tv_category_name);
             if (textView != null) {
                 textView.setText(newText);
             }
         }
     }
     
     private void initializeCategoryStyles(View rootView) {
         // 为每个分类项设置不同的背景颜色、图标和文本
         setCategoryStyle(rootView, R.id.category_food, "餐品", R.drawable.circle_bg_orange, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_drink, "饮品", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_fruit, "水果", R.drawable.circle_bg_green, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_shopping, "购物", R.drawable.circle_bg_purple, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_transport, "交通", R.drawable.circle_bg_yellow, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_hotel, "住宿", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_daily, "日常", R.drawable.circle_bg_red, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_study, "学习", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_entertainment, "娱乐", R.drawable.circle_bg_teal, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_cosmetic, "化妆", R.drawable.circle_bg_pink, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_travel, "旅游", R.drawable.circle_bg_blue, R.drawable.ic_profile);
         setCategoryStyle(rootView, R.id.category_medical, "医疗", R.drawable.circle_bg_green, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_member, "会员", R.drawable.circle_bg_yellow, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_communication, "通讯", R.drawable.circle_bg_gray, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_social, "人情", R.drawable.circle_bg_red, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_investment, "投资", R.drawable.circle_bg_red, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_parenting, "亲子", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_pet, "宠物", R.drawable.circle_bg_blue, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_decoration, "装修", R.drawable.circle_bg_gray, R.drawable.ic_favorite);
         setCategoryStyle(rootView, R.id.category_other, "其他", R.drawable.circle_bg_green, R.drawable.ic_favorite);
     }
     
     private void setCategoryStyle(View rootView, int categoryId, String text, int backgroundRes, int iconRes) {
         try {
             View categoryView = rootView.findViewById(categoryId);
             if (categoryView == null) {
                 Log.w("AddBillFragment", "Category view not found for ID: " + categoryId);
                 return;
             }
             
             // 设置文本
             TextView textView = categoryView.findViewById(R.id.tv_category_name);
             if (textView != null) {
                 textView.setText(text);
             } else {
                 Log.w("AddBillFragment", "TextView not found in category: " + categoryId);
             }
             
             // 设置背景颜色
             FrameLayout frameLayout = categoryView.findViewById(R.id.frame_category_icon);
             if (frameLayout != null) {
                 frameLayout.setBackgroundResource(backgroundRes);
             } else {
                 Log.w("AddBillFragment", "FrameLayout not found in category: " + categoryId);
             }
             
             // 设置图标
             ImageView imageView = categoryView.findViewById(R.id.iv_category_icon);
             if (imageView != null) {
                 imageView.setImageResource(iconRes);
             } else {
                 Log.w("AddBillFragment", "ImageView not found in category: " + categoryId);
             }
         } catch (Exception e) {
             Log.e("AddBillFragment", "Error setting category style for ID: " + categoryId, e);
         }
     }
}
