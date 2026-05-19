package com.example.couplecredit.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.adapter.RestaurantAdapter;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.config.ApiConfigManager;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.ImageCompressor;
import com.example.couplecredit.utils.RestaurantFormatUtils;
import com.example.couplecredit.utils.UserInfoManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class EatOutFragment extends Fragment implements RestaurantAdapter.RestaurantActionListener {

    private static final String[] PRESET_CATEGORIES = {"中餐", "西餐", "日料", "韩餐", "烧烤", "火锅", "快餐", "甜品饮品", "小吃", "其他"};
    private static final String[] DISTANCE_OPTIONS = {"全部", "500m内", "1km内", "3km内"};
    private static final double[] DISTANCE_VALUES = {0, 500, 1000, 3000};
    private RecyclerView rvList;
    private LinearLayout llEmptyState;
    private TextView tvCount;
    private LinearLayout llDistanceChips;
    private LinearLayout llCategoryChips;

    private RestaurantAdapter adapter;
    private final List<AuthApiModels.RestaurantItemData> allRestaurants = new ArrayList<>();

    private int selectedDistanceIndex = 0;
    private String selectedCategory = null;

    private ImageView pendingImageView;
    private String pendingImageUrl;
    private boolean imageChanged = false;

    private ImageView pendingRouteImageView;
    private String pendingRouteImageUrl;
    private boolean routeImageChanged = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_eat_out, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvList = view.findViewById(R.id.rv_restaurant_list);
        llEmptyState = view.findViewById(R.id.ll_empty_state);
        tvCount = view.findViewById(R.id.tv_restaurant_count);
        llDistanceChips = view.findViewById(R.id.ll_distance_chips);
        llCategoryChips = view.findViewById(R.id.ll_category_chips);

        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (getActivity() instanceof com.example.couplecredit.activity.MainActivity) {
                ((com.example.couplecredit.activity.MainActivity) getActivity()).navigateToLastTab();
            }
        });

        rvList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new RestaurantAdapter(this);
        rvList.setAdapter(adapter);

        setupDistanceChips();
        setupCategoryChips();

        view.findViewById(R.id.fab_add_restaurant).setOnClickListener(v -> showRestaurantDialog(null));
        view.findViewById(R.id.fab_random_pick).setOnClickListener(v -> showRandomPick());

        refreshData();
    }

    private void setupDistanceChips() {
        llDistanceChips.removeAllViews();
        int dp8 = (int) (getResources().getDisplayMetrics().density * 8);
        for (int i = 0; i < DISTANCE_OPTIONS.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(DISTANCE_OPTIONS[i]);
            chip.setTextSize(13);
            chip.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);
            chip.setGravity(android.view.Gravity.CENTER);
            final int index = i;
            chip.setOnClickListener(v -> {
                selectedDistanceIndex = index;
                updateDistanceChipStyles();
                applyFilters();
            });
            llDistanceChips.addView(chip);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            lp.setMargins(0, 0, dp8, 0);
        }
        updateDistanceChipStyles();
    }

    private void updateDistanceChipStyles() {
        for (int i = 0; i < llDistanceChips.getChildCount(); i++) {
            TextView chip = (TextView) llDistanceChips.getChildAt(i);
            if (i == selectedDistanceIndex) {
                chip.setTextColor(0xFFFFFFFF);
                chip.setBackgroundResource(R.drawable.button_background);
            } else {
                chip.setTextColor(0xFF666666);
                chip.setBackgroundResource(R.drawable.button_outline_background);
            }
        }
    }

    private void setupCategoryChips() {
        llCategoryChips.removeAllViews();
        int dp8 = (int) (getResources().getDisplayMetrics().density * 8);

        // "全部" chip
        TextView allChip = new TextView(requireContext());
        allChip.setText("全部");
        allChip.setTextSize(13);
        allChip.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);
        allChip.setGravity(android.view.Gravity.CENTER);
        allChip.setOnClickListener(v -> {
            selectedCategory = null;
            updateCategoryChipStyles();
            applyFilters();
        });
        llCategoryChips.addView(allChip);
        LinearLayout.LayoutParams lp0 = (LinearLayout.LayoutParams) allChip.getLayoutParams();
        lp0.setMargins(0, 0, dp8, 0);

        for (String cat : PRESET_CATEGORIES) {
            TextView chip = new TextView(requireContext());
            chip.setText(cat);
            chip.setTextSize(13);
            chip.setPadding(dp8 * 2, dp8, dp8 * 2, dp8);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                updateCategoryChipStyles();
                applyFilters();
            });
            llCategoryChips.addView(chip);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            lp.setMargins(0, 0, dp8, 0);
        }
        updateCategoryChipStyles();
    }

    private void updateCategoryChipStyles() {
        for (int i = 0; i < llCategoryChips.getChildCount(); i++) {
            TextView chip = (TextView) llCategoryChips.getChildAt(i);
            String text = chip.getText().toString();
            boolean isSelected = (selectedCategory == null && text.equals("全部")) || (selectedCategory != null && selectedCategory.equals(text));
            if (isSelected) {
                chip.setTextColor(0xFFFFFFFF);
                chip.setBackgroundResource(R.drawable.button_background);
            } else {
                chip.setTextColor(0xFF666666);
                chip.setBackgroundResource(R.drawable.button_outline_background);
            }
        }
    }

    public void refreshData() {
        if (!isAdded()) return;
        if (!UserInfoManager.isUserLoggedIn(requireContext())) {
            allRestaurants.clear();
            adapter.setData(allRestaurants);
            updateEmptyState();
            return;
        }
        int userId = UserInfoManager.getCurrentUserId(requireContext());
        AuthApiClient.queryRestaurants(requireContext(), userId, new AuthApiClient.RestaurantListCallback() {
            @Override
            public void onSuccess(AuthApiModels.RestaurantListResponse response) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    allRestaurants.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        allRestaurants.addAll(response.data.items);
                    }
                    applyFilters();
                });
            }
            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "加载商家失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void applyFilters() {
        List<AuthApiModels.RestaurantItemData> filtered = getFilteredRestaurants();
        adapter.setData(filtered);
        tvCount.setText(filtered.size() + " 家");
        updateEmptyState();
    }

    private List<AuthApiModels.RestaurantItemData> getFilteredRestaurants() {
        List<AuthApiModels.RestaurantItemData> filtered = new ArrayList<>();
        double maxDist = DISTANCE_VALUES[selectedDistanceIndex];
        for (AuthApiModels.RestaurantItemData item : allRestaurants) {
            if (maxDist > 0 && (item.distance == null || item.distance > maxDist)) continue;
            if (selectedCategory != null && (item.category == null || !selectedCategory.equals(item.category))) continue;
            filtered.add(item);
        }
        return filtered;
    }

    private void updateEmptyState() {
        llEmptyState.setVisibility(allRestaurants.isEmpty() ? View.VISIBLE : View.GONE);
        rvList.setVisibility(allRestaurants.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(AuthApiModels.RestaurantItemData item) {
        showDetailDialog(item);
    }

    @Override
    public void onEdit(AuthApiModels.RestaurantItemData item) {
        showRestaurantDialog(item);
    }

    @Override
    public void onDelete(AuthApiModels.RestaurantItemData item) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_delete_recipe, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle).setView(dialogView).create();
        ((TextView) dialogView.findViewById(R.id.tv_dialog_message)).setText("确定删除\"" + item.name + "\"吗？");
        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            dialog.dismiss();
            int userId = UserInfoManager.getCurrentUserId(requireContext());
            AuthApiClient.deleteRestaurant(requireContext(), item.restaurantId, userId, new AuthApiClient.RestaurantMutationCallback() {
                @Override public void onSuccess() {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> { Toast.makeText(requireContext(), "删除成功", Toast.LENGTH_SHORT).show(); refreshData(); });
                }
                @Override public void onError(String e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show());
                }
            });
        });
        DialogHelper.showWide(dialog, requireContext());
    }

    private void showRestaurantDialog(@Nullable AuthApiModels.RestaurantItemData existing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_restaurant_editor, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        EditText etName = dialogView.findViewById(R.id.et_name);
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinner_category);
        EditText etAvgCost = dialogView.findViewById(R.id.et_avg_cost);
        EditText etDefaultCalories = dialogView.findViewById(R.id.et_default_calories);
        EditText etDistance = dialogView.findViewById(R.id.et_distance);
        EditText etAddress = dialogView.findViewById(R.id.et_address);
        ImageView ivImage = dialogView.findViewById(R.id.iv_image);
        View flImage = dialogView.findViewById(R.id.fl_image);
        TextView tvImageHint = dialogView.findViewById(R.id.tv_image_hint);
        EditText etNote = dialogView.findViewById(R.id.et_note);
        TextView btnSave = dialogView.findViewById(R.id.btn_save);
        ImageView ivRouteImage = dialogView.findViewById(R.id.iv_route_image);
        View flRouteImage = dialogView.findViewById(R.id.fl_route_image);
        TextView tvRouteImageHint = dialogView.findViewById(R.id.tv_route_image_hint);

        pendingImageUrl = null;
        imageChanged = false;
        pendingRouteImageUrl = null;
        routeImageChanged = false;

        // Build category list: preset + custom
        List<String> categoryNames = new ArrayList<>(Arrays.asList(PRESET_CATEGORIES));
        categoryNames.add("自定义...");
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, categoryNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(spinnerAdapter);

        if (existing != null) {
            tvTitle.setText("编辑商家");
            btnSave.setText("保存修改");
            etName.setText(existing.name);
            if (existing.avgCost != null) etAvgCost.setText(String.valueOf(existing.avgCost));
            if (existing.defaultCalories != null) etDefaultCalories.setText(String.valueOf(existing.defaultCalories));
            if (existing.distance != null) etDistance.setText(String.valueOf(existing.distance));
            etAddress.setText(existing.address != null ? existing.address : "");
            etNote.setText(existing.note != null ? existing.note : "");
            pendingImageUrl = existing.imageUrl;
            imageChanged = false;
            if (existing.imageUrl != null && !existing.imageUrl.isEmpty()) {
                String url = ApiConfigManager.resolveResourceUrl(requireContext(), existing.imageUrl);
                Glide.with(this).load(url).placeholder(R.drawable.ic_inventory_placeholder).into(ivImage);
                tvImageHint.setVisibility(View.GONE);
            }
            pendingRouteImageUrl = existing.routeImageUrl;
            routeImageChanged = false;
            if (existing.routeImageUrl != null && !existing.routeImageUrl.isEmpty()) {
                String routeUrl = ApiConfigManager.resolveResourceUrl(requireContext(), existing.routeImageUrl);
                Glide.with(this).load(routeUrl).into(ivRouteImage);
                tvRouteImageHint.setVisibility(View.GONE);
            }
            if (existing.category != null) {
                int idx = categoryNames.indexOf(existing.category);
                if (idx >= 0) spinnerCategory.setSelection(idx);
                else {
                    // Custom category not in preset list, add it before "自定义..."
                    categoryNames.add(categoryNames.size() - 1, existing.category);
                    spinnerAdapter.notifyDataSetChanged();
                    spinnerCategory.setSelection(categoryNames.indexOf(existing.category));
                }
            }
        } else {
            tvTitle.setText("添加商家");
            btnSave.setText("添加");
        }

        flImage.setOnClickListener(v -> {
            pendingImageView = ivImage;
            tvImageHint.setVisibility(View.GONE);
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, 3001);
            }
        });

        flRouteImage.setOnClickListener(v -> {
            pendingRouteImageView = ivRouteImage;
            tvRouteImageHint.setVisibility(View.GONE);
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivityForResult(intent, 3002);
            }
        });

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(getContext(), "请输入商家名称", Toast.LENGTH_SHORT).show();
                return;
            }

            String categoryTemp = null;
            int catPos = spinnerCategory.getSelectedItemPosition();
            if (catPos >= 0 && catPos < categoryNames.size()) {
                String selected = categoryNames.get(catPos);
                if (!selected.equals("自定义...")) {
                    categoryTemp = selected;
                }
            }
            final String category = categoryTemp;

            Double avgCostTemp = null;
            String avgCostStr = etAvgCost.getText().toString().trim();
            if (!TextUtils.isEmpty(avgCostStr)) {
                try { avgCostTemp = Double.parseDouble(avgCostStr); } catch (NumberFormatException ignored) {}
            }
            final Double avgCost = avgCostTemp;

            Double distanceTemp = null;
            String distStr = etDistance.getText().toString().trim();
            if (!TextUtils.isEmpty(distStr)) {
                try { distanceTemp = Double.parseDouble(distStr); } catch (NumberFormatException ignored) {}
            }
            final Double distance = distanceTemp;

            Double defaultCaloriesTemp = null;
            String defaultCaloriesStr = etDefaultCalories.getText().toString().trim();
            if (!TextUtils.isEmpty(defaultCaloriesStr)) {
                try { defaultCaloriesTemp = Double.parseDouble(defaultCaloriesStr); } catch (NumberFormatException ignored) {}
            }
            final Double defaultCalories = defaultCaloriesTemp;

            final String address = etAddress.getText().toString().trim();
            final String note = etNote.getText().toString().trim();
            final int userId = UserInfoManager.getCurrentUserId(requireContext());

            // Determine final image URLs
            final String menuImageUrl;
            if (imageChanged && pendingImageUrl != null) {
                menuImageUrl = null; // will upload
            } else {
                menuImageUrl = existing != null ? existing.imageUrl : null;
            }
            final String routeImageUrlFinal;
            if (routeImageChanged && pendingRouteImageUrl != null) {
                routeImageUrlFinal = null; // will upload
            } else {
                routeImageUrlFinal = existing != null ? existing.routeImageUrl : null;
            }

            if (imageChanged && pendingImageUrl != null) {
                uploadAndContinue(dialog, pendingImageUrl, menuUploadUrl -> {
                    if (routeImageChanged && pendingRouteImageUrl != null) {
                        uploadAndContinue(dialog, pendingRouteImageUrl, routeUploadUrl -> {
                            doSaveRestaurant(dialog, makeReq(userId, name, category, menuUploadUrl, routeUploadUrl, avgCost, defaultCalories, distance, address, note), existing);
                        });
                    } else {
                        doSaveRestaurant(dialog, makeReq(userId, name, category, menuUploadUrl, routeImageUrlFinal, avgCost, defaultCalories, distance, address, note), existing);
                    }
                });
            } else if (routeImageChanged && pendingRouteImageUrl != null) {
                uploadAndContinue(dialog, pendingRouteImageUrl, routeUploadUrl -> {
                    doSaveRestaurant(dialog, makeReq(userId, name, category, menuImageUrl, routeUploadUrl, avgCost, defaultCalories, distance, address, note), existing);
                });
            } else {
                doSaveRestaurant(dialog, makeReq(userId, name, category, menuImageUrl, routeImageUrlFinal, avgCost, defaultCalories, distance, address, note), existing);
            }
        });

        DialogHelper.showWide(dialog, requireContext());
    }

    private interface UploadCallback {
        void onReady(String serverUrl);
    }

    private void uploadAndContinue(AlertDialog dialog, String localUri, UploadCallback callback) {
        try {
            Uri imageUri = Uri.parse(localUri);
            byte[] compressedBytes = ImageCompressor.compress(requireContext(), imageUri, 1024, 80);
            InputStream compressed = compressedBytes != null ? new ByteArrayInputStream(compressedBytes) : null;
            if (compressed != null) {
                String fileName = "restaurant_" + System.currentTimeMillis() + ".jpg";
                AuthApiClient.uploadImage(requireContext(), compressed, fileName, new AuthApiClient.ImageUploadCallback() {
                    @Override public void onSuccess(String serverUrl) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> callback.onReady(serverUrl));
                    }
                    @Override public void onError(String e) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "图片上传失败: " + e, Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                callback.onReady(null);
            }
        } catch (Exception e) {
            callback.onReady(null);
        }
    }

    private AuthApiModels.RestaurantRequest makeReq(int userId, String name, String category, String imageUrl, String routeImageUrl, Double avgCost, Double defaultCalories, Double distance, String address, String note) {
        return new AuthApiModels.RestaurantRequest(userId, name, category, imageUrl, routeImageUrl, avgCost, defaultCalories, distance, address, note);
    }

    private void doSaveRestaurant(AlertDialog dialog, AuthApiModels.RestaurantRequest req, AuthApiModels.RestaurantItemData existing) {
        if (existing == null) {
            AuthApiClient.createRestaurant(requireContext(), req, new AuthApiClient.RestaurantMutationCallback() {
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
            AuthApiClient.updateRestaurant(requireContext(), existing.restaurantId, req, new AuthApiClient.RestaurantMutationCallback() {
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

    private void showRandomPick() {
        List<AuthApiModels.RestaurantItemData> filtered = getFilteredRestaurants();
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "没有符合条件的商家", Toast.LENGTH_SHORT).show();
            return;
        }

        Random random = new Random();
        AuthApiModels.RestaurantItemData picked = filtered.get(random.nextInt(filtered.size()));
        showRandomPickDialog(picked, filtered);
    }

    private void showRandomPickDialog(AuthApiModels.RestaurantItemData picked, List<AuthApiModels.RestaurantItemData> pool) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_random_pick, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvName = dialogView.findViewById(R.id.tv_pick_name);
        TextView tvInfo = dialogView.findViewById(R.id.tv_pick_info);

        updatePickDisplay(tvName, tvInfo, picked);

        dialogView.findViewById(R.id.btn_try_another).setOnClickListener(v -> {
            AuthApiModels.RestaurantItemData newPick = pool.get(new Random().nextInt(pool.size()));
            updatePickDisplay(tvName, tvInfo, newPick);
        });

        dialogView.findViewById(R.id.btn_go_this).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if (dialog.getWindow() != null) {
            int w = (int) (requireContext().getResources().getDisplayMetrics().widthPixels * 0.8f);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void updatePickDisplay(TextView tvName, TextView tvInfo, AuthApiModels.RestaurantItemData item) {
        tvName.setText(item.name);
        StringBuilder info = new StringBuilder();
        if (item.category != null) info.append(item.category);
        if (item.avgCost != null && item.avgCost > 0) {
            if (info.length() > 0) info.append(" · ");
            info.append(RestaurantFormatUtils.formatAvgCost(item.avgCost));
        }
        if (item.distance != null && item.distance > 0) {
            if (info.length() > 0) info.append(" · ");
            info.append(RestaurantFormatUtils.formatDistance(item.distance));
        }
        tvInfo.setText(info.toString());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @NonNull Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 3001 && resultCode == android.app.Activity.RESULT_OK && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingImageView != null) {
                Glide.with(this).load(uri).into(pendingImageView);
            }
            pendingImageUrl = uri.toString();
            imageChanged = true;
        } else if (requestCode == 3002 && resultCode == android.app.Activity.RESULT_OK && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingRouteImageView != null) {
                Glide.with(this).load(uri).into(pendingRouteImageView);
            }
            pendingRouteImageUrl = uri.toString();
            routeImageChanged = true;
        }
    }

    private void showDetailDialog(AuthApiModels.RestaurantItemData item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_restaurant_detail, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvName = dialogView.findViewById(R.id.tv_detail_name);
        TextView tvCategory = dialogView.findViewById(R.id.tv_detail_category);
        TextView tvCost = dialogView.findViewById(R.id.tv_detail_cost);
        TextView tvDistance = dialogView.findViewById(R.id.tv_detail_distance);
        TextView tvAddress = dialogView.findViewById(R.id.tv_detail_address);
        TextView tvNoteLabel = dialogView.findViewById(R.id.tv_detail_note_label);
        TextView tvNote = dialogView.findViewById(R.id.tv_detail_note);
        TextView tvRouteLabel = dialogView.findViewById(R.id.tv_detail_route_label);
        ImageView ivRouteImage = dialogView.findViewById(R.id.iv_detail_route_image);

        tvName.setText(item.name);

        if (item.category != null && !item.category.isEmpty()) {
            tvCategory.setText(item.category);
            tvCategory.setVisibility(View.VISIBLE);
        }
        tvCost.setText(RestaurantFormatUtils.formatAvgCost(item.avgCost));
        tvDistance.setText(RestaurantFormatUtils.formatDistance(item.distance));
        if (item.address != null && !item.address.isEmpty()) {
            tvAddress.setText(item.address);
            tvAddress.setVisibility(View.VISIBLE);
        }
        if (item.note != null && !item.note.isEmpty()) {
            tvNoteLabel.setVisibility(View.VISIBLE);
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        if (item.routeImageUrl != null && !item.routeImageUrl.isEmpty()) {
            tvRouteLabel.setVisibility(View.VISIBLE);
            String url = ApiConfigManager.resolveResourceUrl(requireContext(), item.routeImageUrl);
            Glide.with(this).load(url).into(ivRouteImage);
            ivRouteImage.setVisibility(View.VISIBLE);
        }

        dialogView.findViewById(R.id.btn_detail_edit).setOnClickListener(v -> {
            dialog.dismiss();
            showRestaurantDialog(item);
        });
        dialogView.findViewById(R.id.btn_detail_delete).setOnClickListener(v -> {
            dialog.dismiss();
            onDelete(item);
        });
        dialogView.findViewById(R.id.btn_detail_close).setOnClickListener(v -> dialog.dismiss());

        DialogHelper.showWide(dialog, requireContext());
    }
}
