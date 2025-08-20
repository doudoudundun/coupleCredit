package com.example.couplecredit.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.couplecredit.R;

/**
 * 自适应网格布局，根据屏幕宽度自动计算列数
 * 确保在不同屏幕尺寸上都能均匀分布
 */
public class AdaptiveGridLayout extends ViewGroup {
    
    private int minColumnWidth; // 最小列宽度（dp）
    private int maxColumns;     // 最大列数
    private int minColumns;     // 最小列数
    private int columnCount;    // 当前计算出的列数
    private int horizontalSpacing = 8; // 水平间距（dp）
    private int verticalSpacing = 8;   // 垂直间距（dp）
    
    public AdaptiveGridLayout(@NonNull Context context) {
        this(context, null);
    }
    
    public AdaptiveGridLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public AdaptiveGridLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttributes(context, attrs);
        calculateColumnCount();
    }
    
    /**
     * 初始化自定义属性
     */
    private void initAttributes(Context context, AttributeSet attrs) {
        // 设置默认值
        minColumnWidth = dpToPx(80); // 默认最小列宽80dp
        maxColumns = 6;              // 默认最大6列
        minColumns = 3;              // 默认最小3列
        
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AdaptiveGridLayout);
            try {
                minColumnWidth = a.getDimensionPixelSize(R.styleable.AdaptiveGridLayout_minColumnWidth, minColumnWidth);
                maxColumns = a.getInt(R.styleable.AdaptiveGridLayout_maxColumns, maxColumns);
                minColumns = a.getInt(R.styleable.AdaptiveGridLayout_minColumns, minColumns);
                horizontalSpacing = a.getDimensionPixelSize(R.styleable.AdaptiveGridLayout_horizontalSpacing, dpToPx(horizontalSpacing));
                verticalSpacing = a.getDimensionPixelSize(R.styleable.AdaptiveGridLayout_verticalSpacing, dpToPx(verticalSpacing));
            } finally {
                a.recycle();
            }
        }
    }
    
    /**
     * 根据屏幕宽度计算最适合的列数
     */
    private void calculateColumnCount() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        
        // 减去左右padding
        int availableWidth = screenWidth - getPaddingLeft() - getPaddingRight();
        
        // 计算理论上能容纳的列数
        int theoreticalColumns = availableWidth / (minColumnWidth + horizontalSpacing);
        
        // 限制在最小和最大列数之间
        columnCount = Math.max(minColumns, Math.min(maxColumns, theoreticalColumns));
        
        // 确保至少有1列
        if (columnCount < 1) {
            columnCount = 1;
        }
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        
        // 重新计算列数（屏幕旋转时可能会变化）
        calculateColumnCount();
        
        // 计算每列的实际宽度
        int availableWidth = width - getPaddingLeft() - getPaddingRight() - (columnCount - 1) * horizontalSpacing;
        int columnWidth = availableWidth / columnCount;
        
        int childCount = getChildCount();
        int rowCount = (childCount + columnCount - 1) / columnCount; // 向上取整
        
        // 测量所有子视图
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                int childWidthSpec = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY);
                int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                child.measure(childWidthSpec, childHeightSpec);
            }
        }
        
        // 计算总高度
        int maxChildHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight());
            }
        }
        
        int totalHeight = getPaddingTop() + getPaddingBottom() + 
                         rowCount * maxChildHeight + 
                         (rowCount - 1) * verticalSpacing;
        
        setMeasuredDimension(width, totalHeight);
    }
    
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int availableWidth = width - getPaddingLeft() - getPaddingRight() - (columnCount - 1) * horizontalSpacing;
        int columnWidth = availableWidth / columnCount;
        
        int childCount = getChildCount();
        int currentRow = 0;
        int currentColumn = 0;
        
        // 计算子视图的最大高度（用于统一行高）
        int maxChildHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                maxChildHeight = Math.max(maxChildHeight, child.getMeasuredHeight());
            }
        }
        
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            
            // 计算子视图的位置
            int childLeft = getPaddingLeft() + currentColumn * (columnWidth + horizontalSpacing);
            int childTop = getPaddingTop() + currentRow * (maxChildHeight + verticalSpacing);
            int childRight = childLeft + columnWidth;
            int childBottom = childTop + child.getMeasuredHeight();
            
            // 布局子视图
            child.layout(childLeft, childTop, childRight, childBottom);
            
            // 更新行列位置
            currentColumn++;
            if (currentColumn >= columnCount) {
                currentColumn = 0;
                currentRow++;
            }
        }
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.round(dp * metrics.density);
    }
    
    /**
     * 获取当前列数（用于调试）
     */
    public int getColumnCount() {
        return columnCount;
    }
}