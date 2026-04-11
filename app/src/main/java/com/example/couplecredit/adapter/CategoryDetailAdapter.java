package com.example.couplecredit.adapter;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.activity.CategoriesBillViewActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class CategoryDetailAdapter extends RecyclerView.Adapter<CategoryDetailAdapter.CategoryViewHolder> {
    
    private List<CategoryDetail> categoryDetails;
    private Context context;
    private Map<String, Integer> categoryColorMap = new HashMap<>();
    private int currentYear;
    private int currentMonth;
    
    public CategoryDetailAdapter(Context context, List<CategoryDetail> categoryDetails) {
        this.context = context;
        this.categoryDetails = categoryDetails;
    }
    
    public CategoryDetailAdapter(Context context, List<CategoryDetail> categoryDetails, int year, int month) {
        this.context = context;
        this.categoryDetails = categoryDetails;
        this.currentYear = year;
        this.currentMonth = month;
    }
    
    public CategoryDetailAdapter() {
        this.categoryDetails = new ArrayList<>();
    }
    
    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context ctx = context != null ? context : parent.getContext();
        View view = LayoutInflater.from(ctx).inflate(R.layout.item_category_detail, parent, false);
        return new CategoryViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        CategoryDetail detail = categoryDetails.get(position);
        
        // 设置分类名称
        holder.tvCategoryName.setText(detail.getCategoryName());
        
        // 设置账单数量
        holder.tvBillCount.setText(detail.getBillCount() + "笔");
        
        // 设置金额
        holder.tvCategoryAmount.setText("￥" + String.format("%.2f", detail.getAmount()));
        
        // 设置百分比
        holder.tvCategoryPercentage.setText(String.format("%.2f%%", detail.getPercentage()));
        
        // 设置分类图标
        int iconRes = getCategoryIcon(detail.getCategoryName());
        holder.ivCategoryIcon.setImageResource(iconRes);
        
        // 设置进度条
        holder.progressCategoryPercentage.setProgress((int) detail.getPercentage());
//        holder.seekbarCategoryPercentage.setProgress((int) detail.getPercentage());
//        holder.seekbarCategoryPercentage.setNoThumb(true);
        // 设置进度条颜色，如果没有颜色映射则使用固定的随机颜色
        int progressColor = detail.getColor();
        if (progressColor == 0) {
            progressColor = getOrGenerateColorForCategory(detail.getCategoryName());
        }

//        holder.seekbarCategoryPercentage.setSecondTrackColor(progressColor);
        holder.progressCategoryPercentage.setProgressTintList(ColorStateList.valueOf(progressColor));
        
        // 设置点击事件
        holder.itemView.setOnClickListener(v -> {
            Context ctx = context != null ? context : v.getContext();
            Intent intent = new Intent(ctx, CategoriesBillViewActivity.class);
            intent.putExtra("categoryName", detail.getCategoryName());
            intent.putExtra("year", currentYear);
            intent.putExtra("month", currentMonth);
            
            // 添加调试日志
            android.util.Log.d("CategoryDetailAdapter", "点击分类: " + detail.getCategoryName() + ", 年份: " + currentYear + ", 月份: " + currentMonth);
            
            ctx.startActivity(intent);
        });
    }
    
    @Override
    public int getItemCount() {
        return categoryDetails != null ? categoryDetails.size() : 0;
    }
    
    public void updateData(List<CategoryDetail> newCategoryDetails) {
        this.categoryDetails = newCategoryDetails;
        notifyDataSetChanged();
    }
    
    public void updateYearMonth(int year, int month) {
        this.currentYear = year;
        this.currentMonth = month;
        android.util.Log.d("CategoryDetailAdapter", "updateYearMonth调用 - 年份: " + year + ", 月份: " + month);
    }
    
    private int getCategoryIcon(String categoryName) {
        switch (categoryName) {
            case "餐品": return R.drawable.img_category_food;
            case "饮品": return R.drawable.img_category_drink;
            case "水果": return R.drawable.img_category_fruit;
            case "购物": return R.drawable.img_category_shopping;
            case "交通": return R.drawable.img_category_transport;
            case "住宿": return R.drawable.img_category_hotel;
            case "娱乐": return R.drawable.img_category_entertainment;
            case "化妆": return R.drawable.img_category_cosmetic;
            case "旅游": return R.drawable.img_category_travel;
            case "医疗": return R.drawable.img_category_medical;
            case "会员": return R.drawable.img_category_member;
            case "通讯": return R.drawable.img_category_communication;
            case "人情":
            case "社交": return R.drawable.img_category_social;
            case "亲子":
            case "育儿": return R.drawable.img_category_parenting;
            case "宠物": return R.drawable.img_category_pet;
            case "装修": return R.drawable.img_category_decoration;
            case "投资": return R.drawable.img_category_investment;
            case "日常": return R.drawable.img_category_daily;
            case "学习": return R.drawable.img_category_study;
            case "工资": return R.drawable.img_category_salary;
            case "礼金": return R.drawable.img_category_cashgift;
            case "兼职": return R.drawable.img_category_parttime;
            case "理财": return R.drawable.img_category_financial;
            case "其他": return R.drawable.img_category_other;
            default: return R.drawable.img_category_other;
        }
    }

    // 为分类获取或生成固定的随机颜色
    private int getOrGenerateColorForCategory(String categoryName) {
        if (!categoryColorMap.containsKey(categoryName)) {
            categoryColorMap.put(categoryName, generateRandomColor());
        }
        return categoryColorMap.get(categoryName);
    }
    
    // 生成随机颜色
    private int generateRandomColor() {
        Random random = new Random();
        // 生成较为鲜艳的颜色，避免过于暗淡
        int red = random.nextInt(156) + 100;   // 100-255
        int green = random.nextInt(156) + 100; // 100-255
        int blue = random.nextInt(156) + 100;  // 100-255
        return Color.rgb(red, green, blue);
    }

    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        TextView tvCategoryName;
        TextView tvBillCount;
        TextView tvCategoryAmount;
        TextView tvCategoryPercentage;
        ImageView ivCategoryIcon;
        ProgressBar progressCategoryPercentage;
//        OSSeekbar seekbarCategoryPercentage;

        public CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCategoryName = itemView.findViewById(R.id.tv_category_name);
            tvBillCount = itemView.findViewById(R.id.tv_bill_count);
            tvCategoryAmount = itemView.findViewById(R.id.tv_category_amount);
            tvCategoryPercentage = itemView.findViewById(R.id.tv_category_percentage);
            ivCategoryIcon = itemView.findViewById(R.id.iv_category_icon);
            progressCategoryPercentage = itemView.findViewById(R.id.progress_category_percentage);
        }
    }
    
    // 分类详情数据类
    public static class CategoryDetail {
        private String categoryName;
        private int billCount;
        private float amount;
        private float percentage;
        private int color;
        
        public CategoryDetail(String categoryName, int billCount, float amount, float percentage, int color) {
            this.categoryName = categoryName;
            this.billCount = billCount;
            this.amount = amount;
            this.percentage = percentage;
            this.color = color;
        }
        
        // Getters
        public String getCategoryName() { return categoryName; }
        public int getBillCount() { return billCount; }
        public float getAmount() { return amount; }
        public float getPercentage() { return percentage; }
        public int getColor() { return color; }
    }
}