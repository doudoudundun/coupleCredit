package com.example.couplecredit.adapter;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.viewmodel.BeadInventoryViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BeadBlueprintAdapter extends RecyclerView.Adapter<BeadBlueprintAdapter.ViewHolder> {

    public interface OnBlueprintClickListener {
        void onBlueprintClick(BeadInventoryViewModel.BeadBlueprintItem item);
    }

    private final List<BeadInventoryViewModel.BeadBlueprintItem> items = new ArrayList<>();
    private OnBlueprintClickListener listener;

    public void setOnBlueprintClickListener(OnBlueprintClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<BeadInventoryViewModel.BeadBlueprintItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bead_blueprint, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeadInventoryViewModel.BeadBlueprintItem item = items.get(position);
        String name = item.name == null || item.name.trim().isEmpty() ? "未命名图纸" : item.name;
        if (item.isPartner) {
            SpannableStringBuilder label = new SpannableStringBuilder(name).append("  来自TA");
            int start = label.length() - 4;
            int end = label.length();
            label.setSpan(new ForegroundColorSpan(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new RelativeSizeSpan(0.78f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.tvName.setText(label);
        } else {
            holder.tvName.setText(name);
        }
        holder.tvMeta.setText(String.format(Locale.getDefault(), "%d 色 · 每次 %d 颗", value(item.colorCount), value(item.totalBeadsPerBuild)));
        holder.tvBuildCount.setText(String.format(Locale.getDefault(), "已制作 %d 次", item.buildCount));
        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            holder.ivThumb.setVisibility(View.VISIBLE);
            Glide.with(holder.itemView.getContext())
                    .load(ApiConfigManager.resolveResourceUrl(holder.itemView.getContext(), item.imageUrl))
                    .placeholder(R.drawable.ic_default_avatar)
                    .centerCrop()
                    .into(holder.ivThumb);
        } else {
            holder.ivThumb.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onBlueprintClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvMeta;
        TextView tvBuildCount;
        ImageView ivThumb;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_bead_blueprint_name);
            tvMeta = itemView.findViewById(R.id.tv_bead_blueprint_meta);
            tvBuildCount = itemView.findViewById(R.id.tv_bead_blueprint_build_count);
            ivThumb = itemView.findViewById(R.id.iv_bead_blueprint_thumb);
        }
    }
}
