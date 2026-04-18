package com.example.couplecredit.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;

import java.util.ArrayList;
import java.util.List;

public class RecipeCategoryAdapter extends RecyclerView.Adapter<RecipeCategoryAdapter.ViewHolder> {

    public interface CategoryClickListener {
        void onCategoryClick(int categoryId);
    }

    private List<AuthApiModels.RecipeCategoryData> items = new ArrayList<>();
    private final CategoryClickListener listener;
    private int selectedPosition = 0;

    public RecipeCategoryAdapter(CategoryClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.RecipeCategoryData> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        // Position 0 = "全部" (virtual), real items start at 1
        notifyDataSetChanged();
    }

    public int getSelectedCategoryId() {
        if (selectedPosition == 0) return 0;
        if (selectedPosition - 1 < items.size()) return items.get(selectedPosition - 1).categoryId;
        return 0;
    }

    public List<AuthApiModels.RecipeCategoryData> getCategories() {
        return items;
    }

    @Override
    public int getItemCount() {
        return items.size() + 1; // +1 for "全部"
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recipe_category, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Context ctx = holder.tvName.getContext();
        boolean isSelected = position == selectedPosition;

        if (position == 0) {
            holder.tvName.setText("全部");
        } else {
            AuthApiModels.RecipeCategoryData item = items.get(position - 1);
            holder.tvName.setText(item.name);
        }

        holder.tvName.setTextColor(isSelected ? Color.parseColor("#07C160") : Color.parseColor("#666666"));
        holder.tvName.setTextSize(isSelected ? 14 : 13);
        holder.vIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> {
            int prev = selectedPosition;
            selectedPosition = holder.getAdapterPosition();
            if (prev != selectedPosition) {
                notifyItemChanged(prev);
                notifyItemChanged(selectedPosition);
            }
            if (listener != null) listener.onCategoryClick(getSelectedCategoryId());
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        View vIndicator;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_category_name);
            vIndicator = itemView.findViewById(R.id.v_indicator);
        }
    }
}
