package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.RestaurantFormatUtils;

import java.util.ArrayList;
import java.util.List;

public class RestaurantAdapter extends RecyclerView.Adapter<RestaurantAdapter.ViewHolder> {

    public interface RestaurantActionListener {
        void onItemClick(AuthApiModels.RestaurantItemData item);
        void onEdit(AuthApiModels.RestaurantItemData item);
        void onDelete(AuthApiModels.RestaurantItemData item);
    }

    private final List<AuthApiModels.RestaurantItemData> items = new ArrayList<>();
    private final RestaurantActionListener listener;

    public RestaurantAdapter(RestaurantActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<AuthApiModels.RestaurantItemData> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_restaurant, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.RestaurantItemData item = items.get(position);

        holder.tvName.setText(item.name);

        // Category tag
        if (item.category != null && !item.category.isEmpty()) {
            holder.tvCategoryTag.setText(item.category);
            holder.tvCategoryTag.setVisibility(View.VISIBLE);
        } else {
            holder.tvCategoryTag.setVisibility(View.GONE);
        }

        holder.tvAvgCost.setText(RestaurantFormatUtils.formatAvgCost(item.avgCost));
        holder.tvDistance.setText(RestaurantFormatUtils.formatDistance(item.distance));

        // Address
        if (item.address != null && !item.address.isEmpty()) {
            holder.tvAddress.setText(item.address);
            holder.tvAddress.setVisibility(View.VISIBLE);
        } else {
            holder.tvAddress.setVisibility(View.GONE);
        }

        // Image
        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            String url = ApiConfigManager.resolveResourceUrl(holder.itemView.getContext(), item.imageUrl);
            Glide.with(holder.itemView.getContext()).load(url).into(holder.ivImage);
            holder.ivImage.setVisibility(View.VISIBLE);
        } else {
            holder.ivImage.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onEdit(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvName;
        TextView tvCategoryTag;
        TextView tvAvgCost;
        TextView tvDistance;
        TextView tvAddress;

        ViewHolder(View view) {
            super(view);
            ivImage = view.findViewById(R.id.iv_restaurant_image);
            tvName = view.findViewById(R.id.tv_restaurant_name);
            tvCategoryTag = view.findViewById(R.id.tv_category_tag);
            tvAvgCost = view.findViewById(R.id.tv_avg_cost);
            tvDistance = view.findViewById(R.id.tv_distance);
            tvAddress = view.findViewById(R.id.tv_address);
        }
    }
}
