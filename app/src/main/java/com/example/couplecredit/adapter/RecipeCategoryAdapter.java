package com.example.couplecredit.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeCategoryAdapter extends RecyclerView.Adapter<RecipeCategoryAdapter.ViewHolder> {

    public interface CategoryListener {
        void onCategoryClick(int categoryId);
        void onCategoryDragStart(RecyclerView.ViewHolder holder);
        void onCategoryOrderChanged(List<AuthApiModels.RecipeCategoryData> orderedItems);
    }

    private List<AuthApiModels.RecipeCategoryData> items = new ArrayList<>();
    private final CategoryListener listener;
    private int selectedPosition = 0;
    private final Map<Integer, Integer> cartCountByCategory = new HashMap<>();

    public RecipeCategoryAdapter(CategoryListener listener) {
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.RecipeCategoryData> newItems) {
        this.items = newItems != null ? new ArrayList<>(newItems) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateCartCounts(List<AuthApiModels.RecipeItemData> cartRecipes) {
        cartCountByCategory.clear();
        if (cartRecipes != null) {
            for (AuthApiModels.RecipeItemData r : cartRecipes) {
                int key = r.categoryId != null ? r.categoryId : 0;
                cartCountByCategory.put(key, cartCountByCategory.getOrDefault(key, 0) + 1);
            }
        }
        notifyDataSetChanged();
    }

    public int getSelectedCategoryId() {
        if (selectedPosition == 0) return 0;
        if (selectedPosition - 1 < items.size()) return items.get(selectedPosition - 1).categoryId;
        return 0;
    }

    public List<AuthApiModels.RecipeCategoryData> getCategories() {
        return new ArrayList<>(items);
    }

    public void setSelectedPosition(int position, boolean notify) {
        if (position == selectedPosition) return;
        int prev = selectedPosition;
        selectedPosition = position;
        if (notify) {
            notifyItemChanged(prev);
            notifyItemChanged(selectedPosition);
        }
    }

    public int findPositionByCategoryId(int categoryId) {
        if (categoryId == 0) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).categoryId == categoryId) return i + 1;
        }
        return 0;
    }

    public boolean moveCategory(int fromPosition, int toPosition) {
        if (fromPosition <= 0 || toPosition <= 0) return false;
        int fromIndex = fromPosition - 1;
        int toIndex = toPosition - 1;
        if (fromIndex < 0 || fromIndex >= items.size() || toIndex < 0 || toIndex >= items.size()) return false;
        if (fromIndex == toIndex) return false;

        Collections.swap(items, fromIndex, toIndex);
        notifyItemMoved(fromPosition, toPosition);
        if (selectedPosition == fromPosition) {
            selectedPosition = toPosition;
        } else if (selectedPosition == toPosition) {
            selectedPosition = fromPosition;
        }
        return true;
    }

    public void notifyOrderPersisted() {
        if (listener != null) listener.onCategoryOrderChanged(new ArrayList<>(items));
    }

    @Override
    public int getItemCount() {
        return items.size() + 1;
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
        int categoryId;

        if (position == 0) {
            holder.tvName.setText("全部");
            categoryId = 0;
            holder.itemView.setOnLongClickListener(null);
            holder.itemView.setOnTouchListener(null);
        } else {
            AuthApiModels.RecipeCategoryData item = items.get(position - 1);
            holder.tvName.setText(item.name);
            categoryId = item.categoryId;
            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onCategoryDragStart(holder);
                return true;
            });
        }

        holder.tvName.setTextColor(isSelected ? Color.parseColor("#07C160") : Color.parseColor("#666666"));
        holder.tvName.setTextSize(isSelected ? 14 : 13);
        holder.vIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        int count;
        if (position == 0) {
            int total = 0;
            for (int c : cartCountByCategory.values()) total += c;
            count = total;
        } else {
            count = cartCountByCategory.getOrDefault(categoryId, 0);
        }

        if (count > 0) {
            holder.tvCartBadge.setVisibility(View.VISIBLE);
            holder.tvCartBadge.setText(count > 99 ? "99" : String.valueOf(count));
        } else {
            holder.tvCartBadge.setVisibility(View.GONE);
        }

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
        TextView tvCartBadge;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_category_name);
            vIndicator = itemView.findViewById(R.id.v_indicator);
            tvCartBadge = itemView.findViewById(R.id.tv_cart_badge);
        }
    }
}
