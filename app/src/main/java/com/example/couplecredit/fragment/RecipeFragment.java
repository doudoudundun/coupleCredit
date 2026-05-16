package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.activity.CartActivity;
import com.example.couplecredit.activity.LoginActivity;
import com.example.couplecredit.adapter.RecipeAdapter;
import com.example.couplecredit.adapter.RecipeCategoryAdapter;
import com.example.couplecredit.adapter.RecommendAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.DataLocalCache;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.ImageCompressor;
import com.example.couplecredit.utils.UserInfoManager;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RecipeFragment extends Fragment implements RecipeAdapter.RecipeActionListener {

    private final DataRefreshBus.Listener refreshListener = () -> refreshData();

    private RecyclerView rvRecipeList;
    private RecyclerView rvCategoryList;
    private LinearLayout llEmptyState;
    private LinearLayout layoutLoginPrompt;
    private LinearLayout layoutContent;
    private TextView btnLoginPrompt;
    private TextView tvRecipeCount;
    private View fabAddRecipe;
    private View fabRefreshRecipe;
    private TextView btnAddCategory;
    private TextView btnDoneEdit;
    private boolean isCategoryEditMode = false;

    private RecipeAdapter recipeAdapter;
    private RecipeCategoryAdapter categoryAdapter;
    private final List<AuthApiModels.RecipeItemData> allRecipes = new ArrayList<>();
    private final List<AuthApiModels.RecipeCategoryData> categoryList = new ArrayList<>();
    private boolean isLoggedIn;
    private TextView tvCartBadge;
    private View btnCart;
    private ItemTouchHelper categoryTouchHelper;

    private ImageView pendingImageView;
    private String pendingImageUrl;
    private boolean imageChanged = false;
    private boolean isClickScrolling = false;

    private final Gson gson = new Gson();

    // 推荐视图相关
    private View layoutMainTabs;
    private TextView tabMyRecipes;
    private TextView tabRecommend;
    private View layoutRecommendView;
    private RecyclerView rvRecommendList;
    private RecommendAdapter recommendAdapter;
    private LinearLayout llRecommendEmpty;
    private TextView tabRecommendAll;
    private TextView tabRecommendIngredient;
    private HorizontalScrollView scrollIngredientChips;
    private com.google.android.material.chip.ChipGroup chipGroupIngredients;
    private final List<String> availableIngredients = new ArrayList<>();
    private String selectedIngredient = null;
    private boolean isRecommendMode = false;

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
        fabRefreshRecipe = view.findViewById(R.id.fab_refresh_recipe);
        btnAddCategory = view.findViewById(R.id.btn_add_category);
        btnDoneEdit = view.findViewById(R.id.btn_done_edit);
        btnCart = view.findViewById(R.id.btn_cart);
        tvCartBadge = view.findViewById(R.id.tv_cart_badge);

        btnCart.setOnClickListener(v -> startActivity(new Intent(requireContext(), CartActivity.class)));

        View btnEatOut = view.findViewById(R.id.btn_eat_out);
        btnEatOut.setOnClickListener(v -> {
            if (getActivity() instanceof com.example.couplecredit.activity.MainActivity) {
                ((com.example.couplecredit.activity.MainActivity) getActivity()).showEatOutFragment();
            }
        });

        rvRecipeList.setLayoutManager(new LinearLayoutManager(getContext()));
        recipeAdapter = new RecipeAdapter(this);
        rvRecipeList.setAdapter(recipeAdapter);

        rvCategoryList.setLayoutManager(new LinearLayoutManager(getContext()));
        categoryAdapter = new RecipeCategoryAdapter(new RecipeCategoryAdapter.CategoryListener() {
            @Override
            public void onCategoryClick(int categoryId) {
                recipeAdapter.setData(categoryList, allRecipes);
                tvRecipeCount.setText(allRecipes.size() + " 道菜");
                updateEmptyState();
                int pos = recipeAdapter.getCategoryPosition(categoryId);
                isClickScrolling = true;
                RecyclerView.LayoutManager layoutManager = rvRecipeList.getLayoutManager();
                if (layoutManager instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(pos, 0);
                }
                rvRecipeList.postDelayed(() -> isClickScrolling = false, 300);
            }

            @Override
            public void onCategoryLongClick(int position, AuthApiModels.RecipeCategoryData item) {
                enterCategoryEditMode();
            }

            @Override
            public void onCategoryDeleteClick(int position, AuthApiModels.RecipeCategoryData item) {
                showDeleteCategoryConfirmDialog(item);
            }

            @Override
            public void onCategoryDragStart(RecyclerView.ViewHolder holder) {
                if (categoryTouchHelper != null) {
                    categoryTouchHelper.startDrag(holder);
                }
            }

            @Override
            public void onCategoryOrderChanged(List<AuthApiModels.RecipeCategoryData> orderedItems) {
                categoryList.clear();
                categoryList.addAll(orderedItems);
                recipeAdapter.setData(categoryList, allRecipes);
                if (!isAdded()) return;
                int userId = UserInfoManager.getCurrentUserId(requireContext());
                List<Integer> orderedIds = new ArrayList<>();
                for (AuthApiModels.RecipeCategoryData item : orderedItems) {
                    orderedIds.add(item.categoryId);
                }
                AuthApiClient.reorderRecipeCategories(requireContext(), userId, orderedIds, new AuthApiClient.RecipeMutationCallback() {
                    @Override
                    public void onSuccess() {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "分类顺序已更新", Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onError(String e) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
                            refreshData();
                        });
                    }
                });
            }
        });
        rvCategoryList.setAdapter(categoryAdapter);

        categoryTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            private boolean orderChanged = false;

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getAdapterPosition() <= 0 || !isCategoryEditMode) {
                    return makeMovementFlags(0, 0);
                }
                return super.getMovementFlags(recyclerView, viewHolder);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (target.getAdapterPosition() <= 0) {
                    return false;
                }
                boolean moved = categoryAdapter.moveCategory(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                if (moved) {
                    orderChanged = true;
                    categoryList.clear();
                    categoryList.addAll(categoryAdapter.getCategories());
                    recipeAdapter.setData(categoryList, allRecipes);
                }
                return moved;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                if (orderChanged) {
                    orderChanged = false;
                    categoryAdapter.notifyOrderPersisted();
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        categoryTouchHelper.attachToRecyclerView(rvCategoryList);

        rvRecipeList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (isClickScrolling) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int firstVisible = lm.findFirstVisibleItemPosition();
                if (firstVisible == RecyclerView.NO_POSITION) return;
                int catId = recipeAdapter.getCategoryIdAt(firstVisible);
                int targetPos = categoryAdapter.findPositionByCategoryId(catId);
                categoryAdapter.setSelectedPosition(targetPos, true);
            }
        });

        btnDoneEdit.setOnClickListener(v -> exitCategoryEditMode());
        btnAddCategory.setOnClickListener(v -> showAddCategoryDialog());
        fabAddRecipe.setOnClickListener(v -> {
            if (!isLoggedIn) { openLoginPage(); return; }
            showRecipeDialog(null);
        });
        fabRefreshRecipe.setOnClickListener(v -> refreshData());
        btnLoginPrompt.setOnClickListener(v -> openLoginPage());

        // 推荐视图初始化
        layoutMainTabs = view.findViewById(R.id.layout_main_tabs);
        tabMyRecipes = view.findViewById(R.id.tab_my_recipes);
        tabRecommend = view.findViewById(R.id.tab_recommend);
        layoutRecommendView = view.findViewById(R.id.layout_recommend_view);

        rvRecommendList = layoutRecommendView.findViewById(R.id.rv_recommend_list);
        llRecommendEmpty = layoutRecommendView.findViewById(R.id.ll_recommend_empty);
        tabRecommendAll = layoutRecommendView.findViewById(R.id.tab_recommend_all);
        tabRecommendIngredient = layoutRecommendView.findViewById(R.id.tab_recommend_ingredient);
        scrollIngredientChips = layoutRecommendView.findViewById(R.id.scroll_ingredient_chips);
        chipGroupIngredients = layoutRecommendView.findViewById(R.id.chip_group_ingredients);

        rvRecommendList.setLayoutManager(new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
        recommendAdapter = new RecommendAdapter(recipeId -> showRecipeDetail(recipeId));
        rvRecommendList.setAdapter(recommendAdapter);

        tabMyRecipes.setOnClickListener(v -> switchToMyRecipes());
        tabRecommend.setOnClickListener(v -> switchToRecommend());
        tabRecommendAll.setOnClickListener(v -> {
            tabRecommendAll.setBackgroundResource(R.drawable.bg_recommend_tab_active);
            tabRecommendAll.setTextColor(0xFFFFFFFF);
            tabRecommendIngredient.setBackgroundResource(R.drawable.bg_recommend_tab_inactive);
            tabRecommendIngredient.setTextColor(0xFF666666);
            scrollIngredientChips.setVisibility(View.GONE);
            selectedIngredient = null;
            loadRecommendations();
        });
        tabRecommendIngredient.setOnClickListener(v -> {
            tabRecommendIngredient.setBackgroundResource(R.drawable.bg_recommend_tab_active);
            tabRecommendIngredient.setTextColor(0xFFFFFFFF);
            tabRecommendAll.setBackgroundResource(R.drawable.bg_recommend_tab_inactive);
            tabRecommendAll.setTextColor(0xFF666666);
            scrollIngredientChips.setVisibility(View.VISIBLE);
        });

        refreshData();
        DataRefreshBus.subscribe(refreshListener);
    }

    @Override
    public void onDestroyView() {
        DataRefreshBus.unsubscribe(refreshListener);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateCartBadge();
    }

    private void updateCartBadge() {
        if (tvCartBadge == null) return;
        int size = CartActivity.getCartSize(requireContext());
        if (size > 0) {
            tvCartBadge.setVisibility(View.VISIBLE);
            tvCartBadge.setText(size > 9 ? "9+" : String.valueOf(size));
        } else {
            tvCartBadge.setVisibility(View.GONE);
        }
        updateCategoryCartBadges();
    }

    private void updateCategoryCartBadges() {
        List<AuthApiModels.RecipeItemData> cartRecipes = CartActivity.getCartItems(requireContext());
        categoryAdapter.updateCartCounts(cartRecipes);
    }

    public void refreshData() {
        if (!isAdded()) return;
        isLoggedIn = UserInfoManager.isUserLoggedIn(requireContext());
        updateLoginUI();
        if (!isLoggedIn) {
            allRecipes.clear();
            recipeAdapter.setData(categoryList, allRecipes);
            updateEmptyState();
            return;
        }

        int userId = UserInfoManager.getCurrentUserId(requireContext());

        if (categoryList.isEmpty()) {
            String cachedCats = DataLocalCache.get(requireContext(), "recipe_cats_" + userId);
            if (cachedCats != null) {
                try {
                    AuthApiModels.RecipeCategoryListResponse r = gson.fromJson(cachedCats, AuthApiModels.RecipeCategoryListResponse.class);
                    if (r != null && r.data != null && r.data.items != null) {
                        categoryList.clear();
                        categoryList.addAll(r.data.items);
                        categoryAdapter.updateData(categoryList);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (allRecipes.isEmpty()) {
            String cachedRecipes = DataLocalCache.get(requireContext(), "recipes_" + userId);
            if (cachedRecipes != null) {
                try {
                    AuthApiModels.RecipeListResponse r = gson.fromJson(cachedRecipes, AuthApiModels.RecipeListResponse.class);
                    if (r != null && r.data != null && r.data.items != null) {
                        allRecipes.clear();
                        allRecipes.addAll(r.data.items);
                        recipeAdapter.setData(categoryList, allRecipes);
                        updateCategoryCartBadges();
                        tvRecipeCount.setText(allRecipes.size() + " 道菜");
                        updateEmptyState();
                    }
                } catch (Exception ignored) {}
            }
        }

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
                    updateCategoryCartBadges();
                    recipeAdapter.setData(categoryList, allRecipes);
                    DataLocalCache.put(requireContext(), "recipe_cats_" + userId, gson.toJson(response));
                });
            }
            @Override public void onError(String e) { }
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
                    recipeAdapter.setData(categoryList, allRecipes);
                    updateCategoryCartBadges();
                    tvRecipeCount.setText(allRecipes.size() + " 道菜");
                    updateEmptyState();
                    DataLocalCache.put(requireContext(), "recipes_" + userId, gson.toJson(response));
                });
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "加载菜谱失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });

        if (isRecommendMode) {
            loadRecommendations();
        }
    }

    private void switchToMyRecipes() {
        isRecommendMode = false;
        tabMyRecipes.setTextColor(0xFF07C160);
        tabMyRecipes.setTypeface(null, android.graphics.Typeface.BOLD);
        tabRecommend.setTextColor(0xFF999999);
        tabRecommend.setTypeface(null, android.graphics.Typeface.NORMAL);
        layoutContent.setVisibility(View.VISIBLE);
        layoutRecommendView.setVisibility(View.GONE);
    }

    private void switchToRecommend() {
        isRecommendMode = true;
        tabRecommend.setTextColor(0xFF07C160);
        tabRecommend.setTypeface(null, android.graphics.Typeface.BOLD);
        tabMyRecipes.setTextColor(0xFF999999);
        tabMyRecipes.setTypeface(null, android.graphics.Typeface.NORMAL);
        layoutContent.setVisibility(View.GONE);
        layoutRecommendView.setVisibility(View.VISIBLE);
        loadRecommendations();
    }

    private void loadRecommendations() {
        if (!isLoggedIn) return;
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        String mode = selectedIngredient != null ? "ingredient" : "recommend";
        AuthApiClient.getRecipeRecommendations(requireContext(), userId, mode, selectedIngredient, new AuthApiClient.RecipeRecommendCallback() {
            @Override
            public void onSuccess(AuthApiModels.RecipeRecommendResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    recommendAdapter.setData(response.data.recipes);
                    llRecommendEmpty.setVisibility(response.data.recipes.isEmpty() ? View.VISIBLE : View.GONE);
                    rvRecommendList.setVisibility(response.data.recipes.isEmpty() ? View.GONE : View.VISIBLE);
                    if (response.data.availableIngredients != null) {
                        availableIngredients.clear();
                        availableIngredients.addAll(response.data.availableIngredients);
                        updateIngredientChips();
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "加载推荐失败: " + message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void updateIngredientChips() {
        if (chipGroupIngredients == null) return;
        chipGroupIngredients.removeAllViews();
        for (String ingredient : availableIngredients) {
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(requireContext());
            chip.setText(ingredient);
            chip.setClickable(true);
            chip.setCheckable(true);
            String ing = ingredient;
            chip.setOnClickListener(v -> {
                if (selectedIngredient != null && selectedIngredient.equals(ing)) {
                    selectedIngredient = null;
                } else {
                    selectedIngredient = ing;
                }
                loadRecommendations();
            });
            chipGroupIngredients.addView(chip);
        }
    }

    private void showRecipeDetail(int recipeId) {
        AuthApiModels.RecipeItemData item = null;
        for (AuthApiModels.RecipeItemData r : allRecipes) {
            if (r.recipeId == recipeId) { item = r; break; }
        }
        if (item != null) {
            showRecipeDetailDialog(item);
        } else {
            int userId = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.getRecipeDetail(requireContext(), recipeId, userId, new AuthApiClient.RecipeDetailCallback() {
                @Override
                public void onSuccess(AuthApiModels.RecipeDetailResponse response) {
                    if (!isAdded() || response == null || response.data == null) return;
                    requireActivity().runOnUiThread(() -> {
                        AuthApiModels.RecipeItemData proxy = new AuthApiModels.RecipeItemData();
                        proxy.recipeId = response.data.recipeId;
                        proxy.title = response.data.title;
                        proxy.description = response.data.description;
                        proxy.imageUrl = response.data.imageUrl;
                        proxy.steps = response.data.steps;
                        showRecipeDetailDialog(proxy);
                    });
                }
                @Override
                public void onError(String e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "加载菜谱失败", Toast.LENGTH_SHORT).show()
                    );
                }
            });
        }
    }

    private void showAddCategoryDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_recipe_category, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView)
                .create();

        EditText etName = dialogView.findViewById(R.id.et_category_name);
        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) return;
            dialog.dismiss();
            int userId = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.createRecipeCategory(requireContext(), userId, name, new AuthApiClient.RecipeMutationCallback() {
                @Override public void onSuccess() {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(this::refreshData);
                }
                private void refreshData() { RecipeFragment.this.refreshData(); }
                @Override public void onError(String e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                }
            });
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void enterCategoryEditMode() {
        isCategoryEditMode = true;
        categoryAdapter.setEditMode(true);
        btnDoneEdit.setVisibility(View.VISIBLE);
        btnAddCategory.setVisibility(View.GONE);
    }

    private void exitCategoryEditMode() {
        isCategoryEditMode = false;
        categoryAdapter.setEditMode(false);
        btnDoneEdit.setVisibility(View.GONE);
        btnAddCategory.setVisibility(View.VISIBLE);
        updateCategoryCartBadges();
    }

    private void showDeleteCategoryConfirmDialog(AuthApiModels.RecipeCategoryData item) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle).setView(dialogView).create();
        TextView message = dialogView.findViewById(R.id.tv_confirm_message);
        message.setText("确定要删除「" + item.name + "」吗？\n该种类下的菜谱将变为未分类。");
        dialogView.findViewById(R.id.btn_confirm_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm_delete).setOnClickListener(v -> {
            int userId = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.deleteRecipeCategory(requireContext(), item.categoryId, userId,
                    new AuthApiClient.RecipeMutationCallback() {
                        @Override public void onSuccess() {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() -> {
                                dialog.dismiss();
                                Toast.makeText(requireContext(), "删除成功", Toast.LENGTH_SHORT).show();
                                refreshData();
                            });
                        }
                        @Override public void onError(String e) {
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(requireContext(), "删除失败: " + e, Toast.LENGTH_SHORT).show());
                        }
                    });
        });
        DialogHelper.showWide(dialog, requireContext());
    }

    private void updateLoginUI() {
        layoutLoginPrompt.setVisibility(isLoggedIn ? View.GONE : View.VISIBLE);
        layoutContent.setVisibility(isLoggedIn && !isRecommendMode ? View.VISIBLE : View.GONE);
        layoutMainTabs.setVisibility(isLoggedIn ? View.VISIBLE : View.GONE);
        fabAddRecipe.setEnabled(isLoggedIn);
        fabAddRecipe.setAlpha(isLoggedIn ? 1f : 0.5f);
    }

    private void updateEmptyState() {
        llEmptyState.setVisibility(allRecipes.isEmpty() && isLoggedIn ? View.VISIBLE : View.GONE);
        rvRecipeList.setVisibility(allRecipes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openLoginPage() {
        startActivity(new Intent(requireContext(), LoginActivity.class));
    }

    @Override
    public void onEdit(AuthApiModels.RecipeItemData item) {
        showRecipeDialog(item);
    }

    private AlertDialog currentDetailDialog;

    private void showRecipeDetailDialog(AuthApiModels.RecipeItemData item) {
        if (currentDetailDialog != null && currentDetailDialog.isShowing()) {
            currentDetailDialog.dismiss();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_recipe_detail, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        currentDetailDialog = dialog;

        TextView tvName = dialogView.findViewById(R.id.tv_detail_name);
        TextView tvDesc = dialogView.findViewById(R.id.tv_detail_desc);
        ImageView ivImage = dialogView.findViewById(R.id.iv_detail_image);
        LinearLayout llIngredients = dialogView.findViewById(R.id.ll_detail_ingredients);
        TextView tvStepsLabel = dialogView.findViewById(R.id.tv_detail_steps_label);
        LinearLayout llSteps = dialogView.findViewById(R.id.ll_detail_steps);
        TextView btnClose = dialogView.findViewById(R.id.btn_detail_close);
        TextView btnEdit = dialogView.findViewById(R.id.btn_detail_edit);
        TextView btnDelete = dialogView.findViewById(R.id.btn_detail_delete);

        tvName.setText(item.title != null ? item.title : "");
        tvDesc.setText(item.description != null ? item.description : "");

        if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            String url = ApiConfigManager.resolveResourceUrl(requireContext(), item.imageUrl);
            Glide.with(this).load(url).placeholder(R.drawable.ic_inventory_placeholder).into(ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
        }

        int dp = (int) (requireContext().getResources().getDisplayMetrics().density);

        TextView tvLoading = new TextView(requireContext());
        tvLoading.setText("加载食材中...");
        tvLoading.setTextSize(13);
        tvLoading.setTextColor(0xFF999999);
        llIngredients.addView(tvLoading);

        renderSteps(llSteps, tvStepsLabel, item.steps, dp);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnEdit.setOnClickListener(v -> {
            dialog.dismiss();
            showRecipeDialog(item);
        });
        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            onDelete(item);
        });
        dialog.setOnDismissListener(d -> { if (currentDetailDialog == dialog) currentDetailDialog = null; });
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        showDialogWide(dialog);

        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.getRecipeDetail(requireContext(), item.recipeId, userId, new AuthApiClient.RecipeDetailCallback() {
            @Override
            public void onSuccess(AuthApiModels.RecipeDetailResponse response) {
                if (!isAdded() || !dialog.isShowing()) return;
                requireActivity().runOnUiThread(() -> {
                    if (response == null || response.data == null) return;
                    llIngredients.removeAllViews();
                    if (response.data.ingredients != null && !response.data.ingredients.isEmpty()) {
                        populateIngredients(llIngredients, response.data.ingredients, dp);
                    } else {
                        TextView tvNone = new TextView(requireContext());
                        tvNone.setText("暂无食材信息");
                        tvNone.setTextSize(13);
                        tvNone.setTextColor(0xFF999999);
                        llIngredients.addView(tvNone);
                    }
                    if (response.data.steps != null && !response.data.steps.equals(item.steps)) {
                        llSteps.removeAllViews();
                        renderSteps(llSteps, tvStepsLabel, response.data.steps, dp);
                    }
                });
            }
            @Override public void onError(String e) {
                if (!isAdded() || !dialog.isShowing()) return;
                requireActivity().runOnUiThread(() -> {
                    llIngredients.removeAllViews();
                    TextView tvErr = new TextView(requireContext());
                    tvErr.setText("食材加载失败");
                    tvErr.setTextSize(13);
                    tvErr.setTextColor(0xFF999999);
                    llIngredients.addView(tvErr);
                });
            }
        });
    }


    private void showDialogWide(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            int screenWidth = requireContext().getResources().getDisplayMetrics().widthPixels;
            int width = (int) (screenWidth * 0.85);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void populateIngredients(LinearLayout llIngredients, List<AuthApiModels.IngredientData> ingredients, int dp) {
        llIngredients.removeAllViews();
        for (AuthApiModels.IngredientData ing : ingredients) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp * 4, 0, dp * 4);

            TextView tvDot = new TextView(requireContext());
            tvDot.setText("• ");
            tvDot.setTextSize(14);
            tvDot.setTextColor(0xFF333333);
            row.addView(tvDot);

            TextView tvIngName = new TextView(requireContext());
            tvIngName.setText(ing.ingredientName);
            tvIngName.setTextSize(14);
            tvIngName.setTextColor(0xFF333333);
            LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tvIngName.setLayoutParams(lpName);
            row.addView(tvIngName);

            TextView tvQty = new TextView(requireContext());
            tvQty.setText(formatDecimal(ing.quantity) + " " + ing.unit);
            tvQty.setTextSize(14);
            tvQty.setTextColor(0xFF666666);
            row.addView(tvQty);

            llIngredients.addView(row);
        }
    }

    private String formatDecimal(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void renderSteps(LinearLayout llSteps, TextView tvStepsLabel, String steps, int dp) {
        if (steps == null || steps.trim().isEmpty()) return;
        String stepsStr = steps.replace("[\"", "").replace("\"]", "").replace("\",\"", "\n");
        String[] stepLines = stepsStr.split("\n");
        if (stepLines.length == 0 || (stepLines.length == 1 && stepLines[0].trim().isEmpty())) return;
        tvStepsLabel.setVisibility(View.VISIBLE);
        for (int i = 0; i < stepLines.length; i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp * 6, 0, dp * 6);
            row.setGravity(Gravity.TOP);

            TextView tvNum = new TextView(requireContext());
            tvNum.setText("  " + (i + 1) + ". ");
            tvNum.setTextSize(14);
            tvNum.setTextColor(0xFF07C160);
            tvNum.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(tvNum);

            TextView tvStep = new TextView(requireContext());
            tvStep.setText(stepLines[i].trim());
            tvStep.setTextSize(14);
            tvStep.setTextColor(0xFF333333);
            tvStep.setLineSpacing(dp * 4, 1f);
            LinearLayout.LayoutParams lpStep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tvStep.setLayoutParams(lpStep);
            row.addView(tvStep);

            llSteps.addView(row);

            if (i < stepLines.length - 1) {
                View divider = new View(requireContext());
                divider.setBackgroundColor(0xFFEEEEEE);
                llSteps.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp)));
            }
        }
    }

    @Override
    public void onDelete(AuthApiModels.RecipeItemData item) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_delete_recipe, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle)
                .setView(dialogView)
                .create();
        ((TextView) dialogView.findViewById(R.id.tv_dialog_message)).setText("确定删除\"" + item.title + "\"吗？");
        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            dialog.dismiss();
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
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }


    @Override
    public void onItemClick(AuthApiModels.RecipeItemData item) {
        showRecipeDetailDialog(item);
    }

    @Override
    public void onAddToCart(AuthApiModels.RecipeItemData item) {
        CartActivity.addToCart(requireContext(), item);
        updateCartBadge();
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
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinner_category);

        pendingImageUrl = null;
        imageChanged = false;

        List<String> categoryNames = new ArrayList<>();
        categoryNames.add("未分类");
        List<Integer> categoryIds = new ArrayList<>();
        categoryIds.add(0);
        for (AuthApiModels.RecipeCategoryData cat : categoryList) {
            categoryNames.add(cat.name);
            categoryIds.add(cat.categoryId);
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, categoryNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(spinnerAdapter);

        if (existing != null && existing.categoryId != null) {
            int idx = categoryIds.indexOf(existing.categoryId);
            if (idx >= 0) spinnerCategory.setSelection(idx);
        }

        if (existing != null) {
            tvTitle.setText("编辑菜谱");
            btnSave.setText("保存修改");
            etTitle.setText(existing.title);
            etDesc.setText(existing.description != null ? existing.description : "");
            etSteps.setText(existing.steps != null ? existing.steps.replace("[\"", "").replace("\"]", "").replace("\",\"", "\n") : "");
            pendingImageUrl = existing.imageUrl;
            imageChanged = false;
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                String url = ApiConfigManager.resolveResourceUrl(requireContext(), existing.imageUrl);
                Glide.with(this).load(url).placeholder(R.drawable.ic_inventory_placeholder).into(ivImage);
            }
            int userId = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.getRecipeDetail(requireContext(), existing.recipeId, userId, new AuthApiClient.RecipeDetailCallback() {
                @Override
                public void onSuccess(AuthApiModels.RecipeDetailResponse response) {
                    if (!isAdded() || response == null || response.data == null) return;
                    requireActivity().runOnUiThread(() -> {
                        llIngredients.removeAllViews();
                        if (response.data.ingredients != null) {
                            for (AuthApiModels.IngredientData ing : response.data.ingredients) {
                                addIngredientRow(llIngredients, ing);
                            }
                        }
                    });
                }
                @Override public void onError(String e) { }
            });
        } else {
            tvTitle.setText("添加菜谱");
            btnSave.setText("添加菜谱");
            int selectedCatId = categoryAdapter.getSelectedCategoryId();
            int idx = categoryIds.indexOf(selectedCatId);
            if (idx >= 0) spinnerCategory.setSelection(idx);
        }

        List<AuthApiModels.InventoryItemData> inventoryItems = new ArrayList<>();
        if (UserInfoManager.isUserLoggedIn(requireContext())) {
            int uid = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.queryInventory(requireContext(), uid, new AuthApiClient.InventoryListCallback() {
                @Override public void onSuccess(AuthApiModels.InventoryListResponse response) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        inventoryItems.clear();
                        if (response != null && response.data != null && response.data.items != null) {
                            inventoryItems.addAll(response.data.items);
                        }
                    });
                }
                @Override public void onError(String message) { }
            });
        }

        flImage.setOnClickListener(v -> {
            pendingImageView = ivImage;
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, 2001);
            }
        });

        btnAddIngredient.setOnClickListener(v -> addIngredientRow(llIngredients, null, inventoryItems));

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
            final String finalSteps = steps;

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
                        Object tag = row.getTag();
                        if (tag instanceof Integer) ing.inventoryId = (Integer) tag;
                        finalIngredients.add(ing);
                    }
                }
            }

            int userId = UserInfoManager.getCurrentUserId(requireContext());
            int catPos = spinnerCategory.getSelectedItemPosition();
            Integer selectedCatId = (catPos > 0 && catPos < categoryIds.size()) ? categoryIds.get(catPos) : null;

            if (imageChanged && pendingImageUrl != null) {
                try {
                    Uri imageUri = Uri.parse(pendingImageUrl);
                    byte[] compressedBytes = ImageCompressor.compress(requireContext(), imageUri, 1024, 80);
                    InputStream compressed = compressedBytes != null ? new ByteArrayInputStream(compressedBytes) : null;
                    if (compressed != null) {
                        String fileName = "recipe_" + System.currentTimeMillis() + ".jpg";
                        AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                            @Override public void onSuccess(String serverUrl) {
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> saveRecipe(dialog, userId, title, desc, finalSteps, serverUrl, selectedCatId, existing, finalIngredients));
                            }
                            @Override public void onError(String e) {
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "图片上传失败: " + e, Toast.LENGTH_SHORT).show());
                            }
                        });
                    } else {
                        saveRecipe(dialog, userId, title, desc, finalSteps, null, selectedCatId, existing, finalIngredients);
                    }
                } catch (Exception e) {
                    saveRecipe(dialog, userId, title, desc, finalSteps, null, selectedCatId, existing, finalIngredients);
                }
            } else {
                String imageUrl = existing != null ? existing.imageUrl : null;
                saveRecipe(dialog, userId, title, desc, finalSteps, imageUrl, selectedCatId, existing, finalIngredients);
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addIngredientRow(LinearLayout llIngredients, @Nullable AuthApiModels.IngredientData existing) {
        addIngredientRow(llIngredients, existing, new ArrayList<>());
    }

    private void addIngredientRow(LinearLayout llIngredients, @Nullable AuthApiModels.IngredientData existing, List<AuthApiModels.InventoryItemData> inventoryItems) {
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

        if (existing != null) {
            etName.setText(existing.ingredientName);
            etQty.setText(existing.quantity % 1 == 0 ? String.valueOf((long) existing.quantity) : String.valueOf(existing.quantity));
            etUnit.setText(existing.unit);
            if (existing.inventoryId != null) row.setTag(existing.inventoryId);
        }

        etName.setOnLongClickListener(nameView -> {
            if (inventoryItems.isEmpty()) {
                Toast.makeText(requireContext(), "暂无库存物资", Toast.LENGTH_SHORT).show();
                return true;
            }
            String[] names = new String[inventoryItems.size()];
            for (int i = 0; i < inventoryItems.size(); i++) names[i] = inventoryItems.get(i).name;
            new AlertDialog.Builder(requireContext())
                    .setTitle("选择库存物资")
                    .setItems(names, (d, which) -> {
                        AuthApiModels.InventoryItemData item = inventoryItems.get(which);
                        etName.setText(item.name);
                        etUnit.setText(item.unit);
                        row.setTag(item.inventoryId);
                    })
                    .setNegativeButton("手动输入", null)
                    .show();
            return true;
        });

        llIngredients.addView(row);
    }

    private void saveRecipe(AlertDialog dialog, int userId, String title, String desc, String steps, String imageUrl, Integer categoryId, AuthApiModels.RecipeItemData existing, List<AuthApiModels.IngredientData> finalIngredients) {
        if (existing == null) {
            AuthApiModels.CreateRecipeRequest req = new AuthApiModels.CreateRecipeRequest(userId, title, desc, imageUrl, steps, categoryId, finalIngredients);
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
            AuthApiModels.UpdateRecipeRequest req = new AuthApiModels.UpdateRecipeRequest(userId, title, desc, imageUrl, steps, categoryId, finalIngredients);
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
