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

import java.util.List;

public class AssetAdapter extends RecyclerView.Adapter<AssetAdapter.ViewHolder> {

    public interface OnAssetClickListener {
        void onAssetClick(AuthApiModels.AssetItemData asset);
    }

    private List<AuthApiModels.AssetItemData> assets;
    private OnAssetClickListener listener;

    public AssetAdapter(List<AuthApiModels.AssetItemData> assets, OnAssetClickListener listener) {
        this.assets = assets;
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.AssetItemData> newAssets) {
        this.assets = newAssets;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_asset_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.AssetItemData asset = assets.get(position);

        holder.tvAssetName.setText(asset.name);
        holder.tvAssetInfo.setText(asset.holdDays + "天 · ¥" + String.format("%.1f", asset.dailyCost) + "/天");
        holder.tvAssetPrice.setText("¥" + String.format("%.0f", asset.purchasePrice != null ? asset.purchasePrice : 0));

        // Set status
        if (asset.status != null) {
            switch (asset.status) {
                case "active":
                    holder.tvAssetStatus.setText("在用");
                    holder.tvAssetStatus.setTextColor(0xFF4CAF50);
                    holder.tvAssetStatus.setBackgroundResource(R.drawable.bg_status_active);
                    break;
                case "idle":
                    holder.tvAssetStatus.setText("闲置");
                    holder.tvAssetStatus.setTextColor(0xFFFF9800);
                    holder.tvAssetStatus.setBackgroundResource(R.drawable.bg_status_idle);
                    break;
                case "disposed":
                    holder.tvAssetStatus.setText("已处置");
                    holder.tvAssetStatus.setTextColor(0xFF757575);
                    holder.tvAssetStatus.setBackgroundResource(R.drawable.bg_status_disposed);
                    break;
            }
        }

        // Load image（列表用缩略图 ?w=400，体积减 90%+，加载快）
        if (asset.imageUrl != null && asset.imageUrl.isEmpty() == false) {
            String baseUrl = ApiConfigManager.getBaseUrl(holder.itemView.getContext());
            Glide.with(holder.itemView.getContext())
                    .load(baseUrl + asset.imageUrl + "?w=400")
                    .placeholder(R.drawable.ic_asset_placeholder)
                    .centerCrop()
                    .into(holder.ivAssetImage);
        } else {
            holder.ivAssetImage.setImageResource(R.drawable.ic_asset_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAssetClick(asset);
            }
        });
    }

    @Override
    public int getItemCount() {
        return assets.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivAssetImage;
        TextView tvAssetName, tvAssetInfo, tvAssetPrice, tvAssetStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAssetImage = itemView.findViewById(R.id.iv_asset_image);
            tvAssetName = itemView.findViewById(R.id.tv_asset_name);
            tvAssetInfo = itemView.findViewById(R.id.tv_asset_info);
            tvAssetPrice = itemView.findViewById(R.id.tv_asset_price);
            tvAssetStatus = itemView.findViewById(R.id.tv_asset_status);
        }
    }
}
