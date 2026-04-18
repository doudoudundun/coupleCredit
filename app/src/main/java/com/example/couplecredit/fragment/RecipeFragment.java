package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.RecipeAdapter;
import com.example.couplecredit.adapter.RecipeCategoryAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.UserInfoManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class RecipeFragment extends Fragment implements RecipeAdapter.RecipeActionListener {

    private RecyclerView rvRecipeList;
    private RecyclerView rvCategoryList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private LinearLayout layoutContent;
    private TextView btnLoginPrompt;
    private TextView tvRecipeCount;
    private View fabAddRecipe;
    private TextView btnAddCategory;

    private RecipeAdapter recipeAdapter;
    private RecipeCategoryAdapter categoryAdapter;
    private final List<AuthApiModels.RecipeItemData> recipeList = new ArrayList<>();
    private final List<AuthApiModels.RecipeItemData> allRecipes = new ArrayList<>();
    private final List<AuthApiModels.RecipeCategoryData> categoryList = new ArrayList<>();
    private boolean isLoggedIn;

    private ImageView pendingImageView;
    private String pendingImageUrl;
    private boolean imageChanged = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recipe, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvRecipeList = view.findViewById(R.id.rv_recipe_list);
        rvCategoryList = view.findViewById(R.id.rv_category_list);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        layoutLoginPrompt = view.findViewById(R.id.layout_login_prompt);
        layoutContent = view.findViewById(R.id.layout_content);
        btnLoginPrompt = view.findViewById(R.id.btn_login_prompt);
        tvRecipeCount = view.findViewById(R.id.tv_recipe_count);
        fabAddRecipe = view.findViewById(R.id.fab_add_recipe);
        btnAddCategory = view.findViewById(R.id.btn_add_category);

        rvRecipeList.setLayoutManager(new LinearLayoutManager(getContext()));
        recipeAdapter = new RecipeAdapter(recipeList, this);
        rvRecipeList.setAdapter(recipeAdapter);

        rvCategoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        categoryAdapter = new RecipeCategoryAdapter(categoryId -> {
            filterRecipes();
        });
        rvCategoryList.setAdapter(categoryAdapter);

        btnAddCategory.setOnClickListener(v -> showAddCategoryDialog());

        fabAddRecipe.setOnClickListener(v -> {
            if (!isLoggedIn) { openLoginPage(); return; }
            showRecipeDialog(null);
        });

        btnLoginPrompt.setOnClickListener(v -> openLoginPage());

        refreshData();
    }

    public void refreshData() {
        if (!isAdded()) return;
        isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        updateLoginUI();
        if (!isLoggedIn) {
            recipeList.clear();
            allRecipes.clear();
            recipeAdapter.updateData(recipeList);
            updateEmptyState();
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(requireContext());

        AuthApiClient.queryRecipeCategories(requireContext(), userId, new AuthApiClient.RecipeCategoryListCallback() {
            @Override
            public void onSuccess(AuthApiModels.RecipeCategoryListResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    categoryList.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        categoryList.addAll(response.data.items);
                    }
                    categoryAdapter.updateData(categoryList);
                });
            }
            @Override public void onError(String e) { /* silent */ }
        });

        AuthApiClient.queryRecipes(requireContext(), userId, new AuthApiClient.RecipeListCallback() {
            @Override
            public void onSuccess(AuthApiModels.RecipeListResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    allRecipes.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        allRecipes.addAll(response.data.items);
                    }
                    filterRecipes();
                });
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "加载菜谱失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void filterRecipes() {
        int selectedCategoryId = categoryAdapter.getSelectedCategoryId();
        recipeList.clear();
        for (AuthApiModels.RecipeItemData item : allRecipes) {
            if (selectedCategoryId == 0 || (item.categoryId != null && item.categoryId == selectedCategoryId)) {
                recipeList.add(item);
            }
        }
        recipeAdapter.updateData(recipeList);
        tvRecipeCount.setText(allRecipes.size() + " 道菜");
        updateEmptyState();
    }

    private void showAddCategoryDialog() {
        EditText input = new EditText(requireContext());
        input.setHint("种类名称");
        input.setPadding(48, 32, 48, 32);
        new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setTitle("添加种类")
                .setView(input)
                .setPositiveButton("添加", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) return;
                    int userId = UserInfoManager.getCurrentUserId(requireContext());
                    AuthApiClient.createRecipeCategory(requireContext(), userId, name, new AuthApiClient.RecipeMutationCallback() {
                        @Override public void onSuccess() {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> { refreshData(); });
                        }
                        @Override public void onError(String e) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateLoginUI() {
        layoutLoginPrompt.setVisibility(isLoggedIn ? View.GONE : View.VISIBLE);
        layoutContent.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        fabAddRecipe.setEnabled(isLoggedIn);
        fabAddRecipe.setAlpha(isLoggedIn ? 1f : 0.5f);
    }

    private void updateEmptyState() {
        llEmptyState.setVisibility(recipeList.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
        rvRecipeList.setVisibility(recipeList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openLoginPage() {
        startActivity(new Intent(requireContext(), LoginActivity.class));
    }

    @Override
    public void onEdit(AuthApiModels.RecipeItemData item) {
        showRecipeDialog(item);
    }

    @Override
    public void onDelete(AuthApiModels.RecipeItemData item) {
        new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setTitle("删除菜谱")
                .setMessage("确定删除\"" + item.title + "\"吗？")
                .setPositiveButton("删除", (d, w) -> {
                    int userId = UserInfoManager.getCurrentUserId(requireContext());
                    AuthApiClient.deleteRecipe(requireContext(), item.recipeId, userId, new AuthApiClient.RecipeMutationCallback() {
                        @Override public void onSuccess() {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                Toast.makeText(requireContext(), "删除成功", Toast.LENGTH_SHORT).show();
                                refreshData();
                            });
                        }
                        @Override public void onError(String e) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onCook(AuthApiModels.RecipeItemData item) {
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.cookRecipe(requireContext(), item.recipeId, userId, new AuthApiClient.CookCallback() {
            @Override
            public void onSuccess(AuthApiModels.CookResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    StringBuilder msg = new StringBuilder();
                    if (response.data != null && response.data.results != null) {
                        for (AuthApiModels.CookResultItem r : response.data.results) {
                            msg.append(r.name).append(" -").append((long) r.consumed).append(r.unit).append("\n");
                        }
                    }
                    if (response.data != null && response.data.warnings != null && !response.data.warnings.isEmpty()) {
                        msg.append("\n注意:\n");
                        for (String w : response.data.warnings) msg.append(w).append("\n");
                    }
                    new AlertDialog.Builder(requireContext())
                            .setTitle("烹饪完成")
                            .setMessage(msg.toString().trim())
                            .setPositiveButton("好的", null)
                            .show();
                });
            }
            @Override public void onError(String e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "烹饪失败: " + e, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showRecipeDialog(@Nullable AuthApiModels.RecipeItemData existing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_recipe, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        EditText etTitle = dialogView.findViewById(R.id.et_recipe_title);
        EditText etDesc = dialogView.findViewById(R.id.et_recipe_desc);
        EditText etSteps = dialogView.findViewById(R.id.et_recipe_steps);
        ImageView ivImage = dialogView.findViewById(R.id.iv_recipe_image);
        View flImage = dialogView.findViewById(R.id.fl_recipe_image);
        LinearLayout llIngredients = dialogView.findViewById(R.id.ll_ingredients);
        TextView btnAddIngredient = dialogView.findViewById(R.id.btn_add_ingredient);
        TextView btnSave = dialogView.findViewById(R.id.btn_save_recipe);

        pendingImageUrl = null;
        imageChanged = false;

        if (existing != null) {
            tvTitle.setText("编辑菜谱");
            btnSave.setText("保存修改");
            etTitle.setText(existing.title);
            etDesc.setText(existing.description != null ? existing.description : "");
            etSteps.setText(existing.steps != null ? existing.steps.replace("[\"", "").replace("\"]", "").replace("\",\"", "\n") : "");
            pendingImageUrl = existing.imageUrl;
            imageChanged = false;
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                String url = resolveImageUrl(existing.imageUrl);
                Glide.with(this).load(url).placeholder(R.drawable.ic_inventory_placeholder).into(ivImage);
            }
        } else {
            tvTitle.setText("添加菜谱");
            btnSave.setText("添加菜谱");
        }

        // Image picker
        flImage.setOnClickListener(v -> {
            pendingImageView = ivImage;
            // Reuse inventory's image picker via a simple gallery intent
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, 2001);
            }
        });

        // Ingredients list
        List<AuthApiModels.IngredientData> ingredients = new ArrayList<>();

        btnAddIngredient.setOnClickListener(v -> {
            int dp = (int) (requireContext().getResources().getDisplayMetrics().density);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            EditText etName = new EditText(requireContext());
            etName.setHint("食材名");
            etName.setTextSize(13);
            etName.setBackground(null);
            etName.setMinWidth(dp * 80);
            LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            etName.setLayoutParams(lp0);

            EditText etQty = new EditText(requireContext());
            etQty.setHint("数量");
            etQty.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etQty.setTextSize(13);
            etQty.setBackground(null);
            etQty.setMinWidth(dp * 40);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp1.setMargins(dp * 4, 0, dp * 4, 0);
            etQty.setLayoutParams(lp1);

            EditText etUnit = new EditText(requireContext());
            etUnit.setHint("单位");
            etUnit.setTextSize(13);
            etUnit.setBackground(null);
            etUnit.setMinWidth(dp * 30);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            etUnit.setLayoutParams(lp2);

            row.addView(etName);
            row.addView(etQty);
            row.addView(etUnit);
            llIngredients.addView(row);
        });

        btnSave.setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            if (TextUtils.isEmpty(title)) {
                Toast.makeText(getContext(), "请输入菜谱名称", Toast.LENGTH_SHORT).show();
                return;
            }

            String desc = etDesc.getText().toString().trim();
            String stepsRaw = etSteps.getText().toString().trim();
            String steps = null;
            if (!TextUtils.isEmpty(stepsRaw)) {
                StringBuilder sb = new StringBuilder("[");
                String[] lines = stepsRaw.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(lines[i].trim()).append("\"");
                }
                sb.append("]");
                steps = sb.toString();
            }

            // Collect ingredients
            List<AuthApiModels.IngredientData> finalIngredients = new ArrayList<>();
            for (int i = 0; i < llIngredients.getChildCount(); i++) {
                View child = llIngredients.getChildAt(i);
                if (child instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) child;
                    String iName = ((EditText) row.getChildAt(0)).getText().toString().trim();
                    String iQty = ((EditText) row.getChildAt(1)).getText().toString().trim();
                    String iUnit = ((EditText) row.getChildAt(2)).getText().toString().trim();
                    if (!TextUtils.isEmpty(iName)) {
                        AuthApiModels.IngredientData ing = new AuthApiModels.IngredientData();
                        ing.ingredientName = iName;
                        ing.quantity = TextUtils.isEmpty(iQty) ? 0 : Double.parseDouble(iQty);
                        ing.unit = TextUtils.isEmpty(iUnit) ? "个" : iUnit;
                        finalIngredients.add(ing);
                    }
                }
            }

            int userId = UserInfoManager.getCurrentUserId(requireContext());
            String imageUrl = imageChanged ? pendingImageUrl : (existing != null ? existing.imageUrl : null);

            if (existing == null) {
                AuthApiModels.CreateRecipeRequest req = new AuthApiModels.CreateRecipeRequest(
                        userId, title, desc, imageUrl, steps, null, finalIngredients);
                AuthApiClient.createRecipe(requireContext(), req, new AuthApiClient.RecipeMutationCallback() {
                    @Override public void onSuccess() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> { dialog.dismiss(); refreshData(); });
                    }
                    @Override public void onError(String e) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                AuthApiModels.UpdateRecipeRequest req = new AuthApiModels.UpdateRecipeRequest(
                        userId, title, desc, imageUrl, steps, null, finalIngredients);
                AuthApiClient.updateRecipe(requireContext(), existing.recipeId, req, new AuthApiClient.RecipeMutationCallback() {
                    @Override public void onSuccess() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> { dialog.dismiss(); refreshData(); });
                    }
                    @Override public void onError(String e) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                    }
                });
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://") || imageUrl.startsWith("content://") || imageUrl.startsWith("file://")) return imageUrl;
        return ApiConfigManager.getBaseUrl(requireContext()) + imageUrl;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @NonNull Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2001 && resultCode == android.app.Activity.RESULT_OK && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingImageView != null) {
                Glide.with(this).load(uri).into(pendingImageView);
            }
            pendingImageUrl = uri.toString();
            imageChanged = true;
        }
    }
}
