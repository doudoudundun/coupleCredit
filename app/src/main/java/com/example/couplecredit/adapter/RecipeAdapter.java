package com.example.couplecredit.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RecipeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_CATEGORY = 0;
    private static final int TYPE_RECIPE = 1;

    public interface RecipeActionListener {
        void onItemClick(AuthApiModels.RecipeItemData item);
        void onAddToCart(AuthApiModels.RecipeItemData item);
        void onEdit(AuthApiModels.RecipeItemData item);
        void onDelete(AuthApiModels.RecipeItemData item);
    }

    // Flat list: String = category header, RecipeItemData = recipe card
    private final List<Object> flatList = new ArrayList<>();
    private final RecipeActionListener listener;
    // Map: category position in flatList -> category name
    private final List<Integer> categoryPositions = new ArrayList<>();

    public RecipeAdapter(RecipeActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<AuthApiModels.RecipeCategoryData> categories, List<AuthApiModels.RecipeItemData> allRecipes) {
        flatList.clear();
        categoryPositions.clear();

        Map<Integer, List<AuthApiModels.RecipeItemData>> byCategory = new LinkedHashMap<>();
        List<AuthApiModels.RecipeItemData> uncategorized = new ArrayList<>();

        for (AuthApiModels.RecipeItemData r : allRecipes) {
            if (r.categoryId != null) {
                byCategory.computeIfAbsent(r.categoryId, k -> new ArrayList<>()).add(r);
            } else {
                uncategorized.add(r);
            }
        }

        for (AuthApiModels.RecipeCategoryData cat : categories) {
            List<AuthApiModels.RecipeItemData> catRecipes = byCategory.get(cat.categoryId);
            if (catRecipes != null && !catRecipes.isEmpty()) {
                categoryPositions.add(flatList.size());
                flatList.add(cat.name);
                flatList.addAll(catRecipes);
            }
        }

        if (!uncategorized.isEmpty()) {
            categoryPositions.add(flatList.size());
            flatList.add("未分类");
            flatList.addAll(uncategorized);
        }

        if (flatList.isEmpty() && !allRecipes.isEmpty()) {
            flatList.addAll(allRecipes);
        }

        notifyDataSetChanged();
    }

    public int getCategoryPosition(int categoryId) {
        // Find category position by matching recipes with that categoryId
        for (int i = 0; i < flatList.size(); i++) {
            Object item = flatList.get(i);
            if (item instanceof AuthApiModels.RecipeItemData) {
                AuthApiModels.RecipeItemData r = (AuthApiModels.RecipeItemData) item;
                if (r.categoryId != null && r.categoryId == categoryId) {
                    return i - 1; // scroll to the category header above this recipe
                }
            }
        }
        return 0;
    }

    public int getCategoryIdAt(int flatPosition) {
        if (flatPosition < 0 || flatPosition >= flatList.size()) return 0;
        Object item = flatList.get(flatPosition);
        if (item instanceof String) {
            // It's a category header — find the first recipe below it
            for (int i = flatPosition + 1; i < flatList.size(); i++) {
                Object next = flatList.get(i);
                if (next instanceof AuthApiModels.RecipeItemData) {
                    Integer cid = ((AuthApiModels.RecipeItemData) next).categoryId;
                    return cid != null ? cid : 0;
                }
            }
            return 0;
        }
        if (item instanceof AuthApiModels.RecipeItemData) {
            Integer cid = ((AuthApiModels.RecipeItemData) item).categoryId;
            return cid != null ? cid : 0;
        }
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        return flatList.get(position) instanceof String ? TYPE_CATEGORY : TYPE_RECIPE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_CATEGORY) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(24, 24, 16, 8);
            tv.setTextSize(16);
            tv.setTextColor(Color.parseColor("#333333"));
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            return new CategoryHolder(tv);
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recipe, parent, false);
        return new RecipeHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CategoryHolder) {
            ((CategoryHolder) holder).tv.setText((String) flatList.get(position));
        } else {
            AuthApiModels.RecipeItemData item = (AuthApiModels.RecipeItemData) flatList.get(position);
            RecipeHolder rh = (RecipeHolder) holder;
            rh.tvTitle.setText(item.title);
            rh.tvDesc.setText(item.description != null ? item.description : "");
            rh.tvIngredients.setText(item.ingredientCount + " 种食材");

            if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                Glide.with(rh.ivImage.getContext())
                        .load(item.imageUrl)
                        .placeholder(R.drawable.ic_inventory_placeholder)
                        .error(R.drawable.ic_inventory_placeholder)
                        .centerCrop()
                        .into(rh.ivImage);
            } else {
                rh.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
            }

            rh.btnAdd.setOnClickListener(v -> {
                if (listener != null) listener.onAddToCart(item);
            });
            rh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
        }
    }

    @Override
    public int getItemCount() { return flatList.size(); }

    static class CategoryHolder extends RecyclerView.ViewHolder {
        TextView tv;
        CategoryHolder(TextView tv) { super(tv); this.tv = tv; }
    }

    static class RecipeHolder extends RecyclerView.ViewHolder {
        ImageView ivImage;
        TextView tvTitle;
        TextView tvDesc;
        TextView tvIngredients;
        ImageButton btnAdd;

        RecipeHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_recipe_image);
            tvTitle = itemView.findViewById(R.id.tv_recipe_title);
            tvDesc = itemView.findViewById(R.id.tv_recipe_desc);
            tvIngredients = itemView.findViewById(R.id.tv_recipe_ingredients);
            btnAdd = itemView.findViewById(R.id.btn_recipe_add);
        }
    }
}
