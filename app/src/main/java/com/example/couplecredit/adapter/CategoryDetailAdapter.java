package com.example.couplecredit.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;

import java.util.ArrayList;
import java.util.List;

public class CategoryDetailAdapter extends RecyclerView.Adapter<CategoryDetailAdapter.CategoryViewHolder> {
    
    private List<CategoryDetail> categoryDetails;
    private Context context;
    
    public CategoryDetailAdapter(Context context, List<CategoryDetail> categoryDetails) {
        this.context = context;
        this.categoryDetails = categoryDetails;
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
    }
    
    @Override
    public int getItemCount() {
        return categoryDetails != null ? categoryDetails.size() : 0;
    }
    
    public void updateData(List<CategoryDetail> newCategoryDetails) {
        this.categoryDetails = newCategoryDetails;
        notifyDataSetChanged();
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
            case "人情": return R.drawable.img_category_social;
            case "亲子": return R.drawable.img_category_parenting;
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
    
    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        TextView tvCategoryName;
        TextView tvBillCount;
        TextView tvCategoryAmount;
        TextView tvCategoryPercentage;
        ImageView ivCategoryIcon;
        
        public CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCategoryName = itemView.findViewById(R.id.tv_category_name);
            tvBillCount = itemView.findViewById(R.id.tv_bill_count);
            tvCategoryAmount = itemView.findViewById(R.id.tv_category_amount);
            tvCategoryPercentage = itemView.findViewById(R.id.tv_category_percentage);
            ivCategoryIcon = itemView.findViewById(R.id.iv_category_icon);
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