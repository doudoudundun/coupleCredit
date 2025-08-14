package com.example.couplecredit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import java.util.List;

/**
 * 背景图片网格适配器
 * 用于在GridView中显示预设的背景图片
 */
public class BackgroundImageAdapter extends BaseAdapter {
    
    private Context context;                    // 上下文对象
    private List<Integer> backgroundImages;     // 背景图片资源ID列表
    private LayoutInflater inflater;            // 布局填充器
    
    /**
     * 构造函数
     * @param context 上下文对象
     * @param backgroundImages 背景图片资源ID列表
     */
    public BackgroundImageAdapter(Context context, List<Integer> backgroundImages) {
        this.context = context;
        this.backgroundImages = backgroundImages;
        this.inflater = LayoutInflater.from(context);
    }
    
    /**
     * 获取数据项总数
     * @return 背景图片总数
     */
    @Override
    public int getCount() {
        return backgroundImages.size();
    }
    
    /**
     * 获取指定位置的数据项
     * @param position 位置索引
     * @return 对应位置的背景图片资源ID
     */
    @Override
    public Object getItem(int position) {
        return backgroundImages.get(position);
    }
    
    /**
     * 获取指定位置的项ID
     * @param position 位置索引
     * @return 位置索引作为ID
     */
    @Override
    public long getItemId(int position) {
        return position;
    }
    
    /**
     * 创建并返回指定位置的视图
     * @param position 位置索引
     * @param convertView 可复用的视图
     * @param parent 父容器
     * @return 配置好的视图
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        
        // 使用ViewHolder模式优化性能
        if (convertView == null) {
            // 首次创建视图
            convertView = inflater.inflate(R.layout.item_background_image, parent, false);
            holder = new ViewHolder();
            holder.imageView = convertView.findViewById(R.id.iv_background);
            convertView.setTag(holder);
        } else {
            // 复用已有视图
            holder = (ViewHolder) convertView.getTag();
        }
        
        // 设置背景图片
        holder.imageView.setImageResource(backgroundImages.get(position));
        
        return convertView;
    }
    
    /**
     * ViewHolder静态内部类
     * 用于缓存视图组件，提高列表滚动性能
     */
    static class ViewHolder {
        ImageView imageView;  // 背景图片显示组件
    }
}