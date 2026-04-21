package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.UserInfoManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CartActivity extends AppCompatActivity {


    private static final String PREFS_NAME = "recipe_cart";
    private static final String KEY_CART = "cart_items";
    private static final Gson GSON = new Gson();

    private RecyclerView rvCartList;
    private LinearLayout llCartEmpty;
    private CartAdapter cartAdapter;
    private final List<AuthApiModels.RecipeItemData> cartItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cart);

        View btnBack = findViewById(R.id.btn_back);
        rvCartList = findViewById(R.id.rv_cart_list);
        llCartEmpty = findViewById(R.id.ll_cart_empty);
        TextView btnAddMore = findViewById(R.id.btn_add_more);
        TextView btnCookAll = findViewById(R.id.btn_cook_all);
        TextView tvDate = findViewById(R.id.tv_cart_date);

        SimpleDateFormat sdf = new SimpleDateFormat("M月d日 EEEE", Locale.CHINESE);
        tvDate.setText(sdf.format(new Date()));

        btnBack.setOnClickListener(v -> finish());

        rvCartList.setLayoutManager(new LinearLayoutManager(this));
        cartAdapter = new CartAdapter();
        rvCartList.setAdapter(cartAdapter);

        btnAddMore.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        btnCookAll.setOnClickListener(v -> cookAll());

        loadCart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCart();
    }

    private void loadCart() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_CART, "[]");
        Type type = new TypeToken<List<AuthApiModels.RecipeItemData>>(){}.getType();
        List<AuthApiModels.RecipeItemData> loaded = GSON.fromJson(json, type);
        cartItems.clear();
        if (loaded != null) cartItems.addAll(loaded);
        cartAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void saveCart() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_CART, GSON.toJson(cartItems)).apply();
        updateEmptyState();
    }

    private void updateEmptyState() {
        llCartEmpty.setVisibility(cartItems.isEmpty() ? View.VISIBLE : View.GONE);
        rvCartList.setVisibility(cartItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void removeFromCart(int position) {
        cartItems.remove(position);
        cartAdapter.notifyItemRemoved(position);
        cartAdapter.notifyItemRangeChanged(position, cartItems.size());
        saveCart();
    }

    private void cookAll() {
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "购物车是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        int userId = UserInfoManager.getCurrentUserId(this);
        if (userId <= 0) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        cookNext(0, userId);
    }

    private void cookNext(int index, int userId) {
        if (index >= cartItems.size()) {
            cartItems.clear();
            saveCart();
            com.example.couplecredit.utils.DataRefreshBus.refreshAll();
            new AlertDialog.Builder(this)
                    .setTitle("烹饪完成")
                    .setMessage("所有菜品已烹饪完成，食材已自动扣除")
                    .setPositiveButton("好的", (d, w) -> finish())
                    .show();
            return;
        }

        AuthApiModels.RecipeItemData item = cartItems.get(index);
        AuthApiClient.cookRecipe(this, item.recipeId, userId, new AuthApiClient.CookCallback() {
            @Override
            public void onSuccess(AuthApiModels.CookResponse response) {
                StringBuilder msg = new StringBuilder();
                msg.append(item.title).append(": ");
                if (response.data != null && response.data.results != null) {
                    for (AuthApiModels.CookResultItem r : response.data.results) {
                        msg.append(r.name).append(" -").append(formatDecimal(r.consumed)).append(r.unit).append(" ");
                    }
                }
                if (response.data != null && response.data.warnings != null && !response.data.warnings.isEmpty()) {
                    msg.append("\n注意: ");
                    for (String w : response.data.warnings) msg.append(w).append(" ");
                }
                runOnUiThread(() -> {
                    Toast.makeText(CartActivity.this, msg.toString().trim(), Toast.LENGTH_SHORT).show();
                    cookNext(index + 1, userId);
                });
            }

            @Override
            public void onError(String e) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(CartActivity.this)
                            .setTitle("烹饪失败")
                            .setMessage(item.title + " 烹饪失败: " + e + "\n是否继续烹饪其他菜品？")
                            .setPositiveButton("继续", (d, w) -> cookNext(index + 1, userId))
                            .setNegativeButton("停止", null)
                            .show();
                });
            }
        });
    }

    private String formatDecimal(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static void addToCart(android.content.Context context, AuthApiModels.RecipeItemData recipe) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CART, "[]");
        Type type = new TypeToken<List<AuthApiModels.RecipeItemData>>(){}.getType();
        List<AuthApiModels.RecipeItemData> cart = GSON.fromJson(json, type);
        if (cart == null) cart = new ArrayList<>();

        for (AuthApiModels.RecipeItemData existing : cart) {
            if (existing.recipeId == recipe.recipeId) {
                Toast.makeText(context, "\"" + recipe.title + "\" 已在购物车中", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        cart.add(recipe);
        prefs.edit().putString(KEY_CART, GSON.toJson(cart)).apply();
        Toast.makeText(context, "\"" + recipe.title + "\" 已加入购物车", Toast.LENGTH_SHORT).show();
    }

    public static int getCartSize(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CART, "[]");
        Type type = new TypeToken<List<AuthApiModels.RecipeItemData>>(){}.getType();
        List<AuthApiModels.RecipeItemData> cart = GSON.fromJson(json, type);
        return cart != null ? cart.size() : 0;
    }

    public static List<AuthApiModels.RecipeItemData> getCartItems(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CART, "[]");
        Type type = new TypeToken<List<AuthApiModels.RecipeItemData>>(){}.getType();
        List<AuthApiModels.RecipeItemData> cart = GSON.fromJson(json, type);
        return cart != null ? cart : new ArrayList<>();
    }

    private void showRecipeDetail(AuthApiModels.RecipeItemData item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_recipe_detail, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvName = dialogView.findViewById(R.id.tv_detail_name);
        TextView tvDesc = dialogView.findViewById(R.id.tv_detail_desc);
        ImageView ivImage = dialogView.findViewById(R.id.iv_detail_image);
        LinearLayout llIngredients = dialogView.findViewById(R.id.ll_detail_ingredients);
        TextView tvStepsLabel = dialogView.findViewById(R.id.tv_detail_steps_label);
        LinearLayout llSteps = dialogView.findViewById(R.id.ll_detail_steps);
        TextView btnClose = dialogView.findViewById(R.id.btn_detail_close);

        tvName.setText(item.title != null ? item.title : "");
        tvDesc.setText(item.description != null ? item.description : "");

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            Glide.with(this).load(ApiConfigManager.resolveResourceUrl(this, item.imageUrl))
                    .placeholder(R.drawable.ic_inventory_placeholder).into(ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
        }

        int dp = (int) (getResources().getDisplayMetrics().density);

        // Show loading hint for ingredients
        TextView tvLoading = new TextView(this);
        tvLoading.setText("加载食材中...");
        tvLoading.setTextSize(13);
        tvLoading.setTextColor(0xFF999999);
        llIngredients.addView(tvLoading);

        // Steps from list data
        if (item.steps != null && !item.steps.isEmpty()) {
            String stepsStr = item.steps.replace("[\"", "").replace("\"]", "").replace("\",\"", "\n");
            String[] stepLines = stepsStr.split("\n");
            if (stepLines.length > 0 && !(stepLines.length == 1 && stepLines[0].trim().isEmpty())) {
                tvStepsLabel.setVisibility(View.VISIBLE);
                for (int i = 0; i < stepLines.length; i++) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(0, dp * 6, 0, dp * 6);
                    row.setGravity(Gravity.TOP);

                    TextView tvNum = new TextView(this);
                    tvNum.setText("  " + (i + 1) + ". ");
                    tvNum.setTextSize(14);
                    tvNum.setTextColor(0xFF07C160);
                    tvNum.setTypeface(null, android.graphics.Typeface.BOLD);
                    row.addView(tvNum);

                    TextView tvStep = new TextView(this);
                    tvStep.setText(stepLines[i].trim());
                    tvStep.setTextSize(14);
                    tvStep.setTextColor(0xFF333333);
                    tvStep.setLineSpacing(dp * 4, 1f);
                    LinearLayout.LayoutParams lpStep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    tvStep.setLayoutParams(lpStep);
                    row.addView(tvStep);

                    llSteps.addView(row);

                    if (i < stepLines.length - 1) {
                        View divider = new View(this);
                        divider.setBackgroundColor(0xFFEEEEEE);
                        llSteps.addView(divider, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp)));
                    }
                }
            }
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
            dialog.getWindow().setLayout(w, h);
        }

        // Load ingredients async
        int userId = UserInfoManager.getCurrentUserId(this);
        AuthApiClient.getRecipeDetail(this, item.recipeId, userId, new AuthApiClient.RecipeDetailCallback() {
            @Override
            public void onSuccess(AuthApiModels.RecipeDetailResponse response) {
                if (response == null || response.data == null || response.data.ingredients == null) return;
                runOnUiThread(() -> {
                    llIngredients.removeAllViews();
                    if (response.data.ingredients.isEmpty()) {
                        TextView tvNone = new TextView(CartActivity.this);
                        tvNone.setText("暂无食材信息");
                        tvNone.setTextSize(13);
                        tvNone.setTextColor(0xFF999999);
                        llIngredients.addView(tvNone);
                    } else {
                        for (AuthApiModels.IngredientData ing : response.data.ingredients) {
                            LinearLayout row = new LinearLayout(CartActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setPadding(0, dp * 4, 0, dp * 4);

                            TextView tvDot = new TextView(CartActivity.this);
                            tvDot.setText("• ");
                            tvDot.setTextSize(14);
                            tvDot.setTextColor(0xFF333333);
                            row.addView(tvDot);

                            TextView tvIngName = new TextView(CartActivity.this);
                            tvIngName.setText(ing.ingredientName);
                            tvIngName.setTextSize(14);
                            tvIngName.setTextColor(0xFF333333);
                            LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                            tvIngName.setLayoutParams(lpName);
                            row.addView(tvIngName);

                            TextView tvQty = new TextView(CartActivity.this);
                            tvQty.setText(formatDecimal(ing.quantity) + " " + ing.unit);
                            tvQty.setTextSize(14);
                            tvQty.setTextColor(0xFF666666);
                            row.addView(tvQty);

                            llIngredients.addView(row);
                        }
                    }
                });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> {
                    llIngredients.removeAllViews();
                    TextView tvErr = new TextView(CartActivity.this);
                    tvErr.setText("食材加载失败");
                    tvErr.setTextSize(13);
                    tvErr.setTextColor(0xFF999999);
                    llIngredients.addView(tvErr);
                });
            }
        });
    }

    class CartAdapter extends RecyclerView.Adapter<CartAdapter.CartHolder> {

        @NonNull
        @Override
        public CartHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cart_recipe, parent, false);
            return new CartHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CartHolder holder, int position) {
            AuthApiModels.RecipeItemData item = cartItems.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvIngredients.setText(item.ingredientCount + " 种食材");

            if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                Glide.with(holder.ivImage.getContext())
                        .load(ApiConfigManager.resolveResourceUrl(CartActivity.this, item.imageUrl))
                        .placeholder(R.drawable.ic_inventory_placeholder)
                        .error(R.drawable.ic_inventory_placeholder)
                        .centerCrop()
                        .into(holder.ivImage);
            } else {
                holder.ivImage.setImageResource(R.drawable.ic_inventory_placeholder);
            }

            holder.btnRemove.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) removeFromCart(pos);
            });

            holder.itemView.setOnClickListener(v -> showRecipeDetail(item));
        }

        @Override
        public int getItemCount() { return cartItems.size(); }

        class CartHolder extends RecyclerView.ViewHolder {
            ImageView ivImage;
            TextView tvTitle;
            TextView tvIngredients;
            TextView btnRemove;

            CartHolder(View itemView) {
                super(itemView);
                ivImage = itemView.findViewById(R.id.iv_cart_image);
                tvTitle = itemView.findViewById(R.id.tv_cart_title);
                tvIngredients = itemView.findViewById(R.id.tv_cart_ingredients);
                btnRemove = itemView.findViewById(R.id.btn_cart_remove);
            }
        }
    }
}
