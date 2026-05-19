package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.CalorieFormatUtils;
import com.example.couplecredit.utils.DataRefreshBus;
import com.example.couplecredit.utils.DialogHelper;
import com.example.couplecredit.utils.UserInfoManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CalorieActivity extends AppCompatActivity {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault());

    private TextView tvDate;
    private TextView tvTotal;
    private TextView tvGoalSummary;
    private TextView tvSources;
    private TextView tvEmpty;
    private ProgressBar progressBar;
    private RecyclerView rvRecords;
    private RecordAdapter adapter;
    private final List<AuthApiModels.MealRecord> records = new ArrayList<>();
    private final List<AuthApiModels.RestaurantItemData> restaurants = new ArrayList<>();
    private LocalDate selectedDate = LocalDate.now();
    private AuthApiModels.CalorieSummaryData currentSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calorie);

        ImageButton btnBack = findViewById(R.id.btn_calorie_back);
        TextView btnGoal = findViewById(R.id.btn_calorie_goal);
        TextView btnPrev = findViewById(R.id.btn_prev_date);
        TextView btnNext = findViewById(R.id.btn_next_date);
        TextView btnEatOut = findViewById(R.id.btn_record_eat_out);
        TextView btnManual = findViewById(R.id.btn_record_manual);
        tvDate = findViewById(R.id.tv_calorie_date);
        tvTotal = findViewById(R.id.tv_calorie_total);
        tvGoalSummary = findViewById(R.id.tv_calorie_goal_summary);
        tvSources = findViewById(R.id.tv_calorie_sources);
        tvEmpty = findViewById(R.id.tv_calorie_empty);
        progressBar = findViewById(R.id.progress_calorie_total);
        rvRecords = findViewById(R.id.rv_calorie_records);

        rvRecords.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordAdapter();
        rvRecords.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnGoal.setOnClickListener(v -> showGoalDialog());
        btnPrev.setOnClickListener(v -> {
            selectedDate = selectedDate.minusDays(1);
            loadSummary();
        });
        btnNext.setOnClickListener(v -> {
            selectedDate = selectedDate.plusDays(1);
            loadSummary();
        });
        btnEatOut.setOnClickListener(v -> showRecordDialog(true));
        btnManual.setOnClickListener(v -> showRecordDialog(false));

        loadRestaurants();
        loadSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSummary();
    }

    private void loadRestaurants() {
        int userId = UserInfoManager.getCurrentUserId(this);
        if (userId <= 0) return;
        AuthApiClient.queryRestaurants(this, userId, new AuthApiClient.RestaurantListCallback() {
            @Override public void onSuccess(AuthApiModels.RestaurantListResponse response) {
                runOnUiThread(() -> {
                    restaurants.clear();
                    if (response != null && response.data != null && response.data.items != null) {
                        restaurants.addAll(response.data.items);
                    }
                });
            }
            @Override public void onError(String message) { }
        });
    }

    private void loadSummary() {
        int userId = UserInfoManager.getCurrentUserId(this);
        if (userId <= 0) {
            finish();
            return;
        }
        String date = selectedDate.format(DATE_FORMATTER);
        AuthApiClient.getCalorieHistory(this, userId, date, new AuthApiClient.CalorieSummaryCallback() {
            @Override
            public void onSuccess(AuthApiModels.CalorieSummaryResponse response) {
                runOnUiThread(() -> renderSummary(response != null ? response.data : null));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(CalorieActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderSummary(AuthApiModels.CalorieSummaryData data) {
        currentSummary = data;
        tvDate.setText(selectedDate.equals(LocalDate.now()) ? "今天" : selectedDate.format(DATE_FORMATTER));
        if (data == null) {
            tvTotal.setText("0 kcal");
            tvGoalSummary.setText("目标 2000 kcal");
            tvSources.setText("烹饪 0  外食 0  手动 0");
            progressBar.setProgress(0);
            records.clear();
            adapter.notifyDataSetChanged();
            updateEmptyState();
            return;
        }

        tvTotal.setText(formatCalories(data.totalCalories));
        tvGoalSummary.setText("目标 " + formatCalories(data.dailyGoal));
        int progress = (int) Math.max(0, Math.min(100, Math.round(data.progress)));
        progressBar.setProgress(progress);
        double cook = data.sourceTotals != null ? data.sourceTotals.cook : 0;
        double eatOut = data.sourceTotals != null ? data.sourceTotals.eatOut : 0;
        double manual = data.sourceTotals != null ? data.sourceTotals.manual : 0;
        tvSources.setText("烹饪 " + formatPlain(cook) + "  外食 " + formatPlain(eatOut) + "  手动 " + formatPlain(manual));

        records.clear();
        if (data.records != null) {
            records.addAll(data.records);
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = records.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvRecords.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showGoalDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_calorie_goal, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CustomDialogStyle).setView(view).create();
        EditText etGoal = view.findViewById(R.id.et_calorie_goal);
        if (currentSummary != null) {
            etGoal.setText(formatPlain(currentSummary.dailyGoal));
        }
        view.findViewById(R.id.btn_save_calorie_goal).setOnClickListener(v -> {
            String input = etGoal.getText().toString().trim();
            if (TextUtils.isEmpty(input)) {
                Toast.makeText(this, "请输入目标", Toast.LENGTH_SHORT).show();
                return;
            }
            double value;
            try {
                value = Double.parseDouble(input);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "目标格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            AuthApiClient.updateCalorieGoal(this, UserInfoManager.getCurrentUserId(this), value, new AuthApiClient.CalorieMutationCallback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        DataRefreshBus.refreshAll();
                        loadSummary();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(CalorieActivity.this, message, Toast.LENGTH_SHORT).show());
                }
            });
        });
        DialogHelper.showWide(dialog, this);
    }

    private void showRecordDialog(boolean isEatOut) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_eat_out_calorie, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CustomDialogStyle).setView(view).create();
        TextView tvTitle = view.findViewById(R.id.tv_dialog_calorie_record_title);
        Spinner spinnerRestaurant = view.findViewById(R.id.spinner_calorie_restaurant);
        EditText etTitle = view.findViewById(R.id.et_calorie_title);
        EditText etValue = view.findViewById(R.id.et_calorie_value);
        EditText etNote = view.findViewById(R.id.et_calorie_note);
        tvTitle.setText(isEatOut ? "记录外食" : "手动记录");
        spinnerRestaurant.setVisibility(isEatOut ? View.VISIBLE : View.GONE);

        final List<String> names = new ArrayList<>();
        names.add("不关联商家");
        for (AuthApiModels.RestaurantItemData item : restaurants) {
            names.add(item.name);
        }
        spinnerRestaurant.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names));
        if (isEatOut) {
            spinnerRestaurant.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view1, int position, long id) {
                    if (position > 0 && position - 1 < restaurants.size()) {
                        AuthApiModels.RestaurantItemData item = restaurants.get(position - 1);
                        if (TextUtils.isEmpty(etTitle.getText())) etTitle.setText(item.name);
                        if (item.defaultCalories != null) etValue.setText(formatPlain(item.defaultCalories));
                    }
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }

        view.findViewById(R.id.btn_save_calorie_record).setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            String valueText = etValue.getText().toString().trim();
            if (TextUtils.isEmpty(valueText)) {
                Toast.makeText(this, "请输入热量", Toast.LENGTH_SHORT).show();
                return;
            }
            double calories;
            try {
                calories = Double.parseDouble(valueText);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "热量格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            Integer restaurantId = null;
            if (isEatOut && spinnerRestaurant.getSelectedItemPosition() > 0) {
                restaurantId = restaurants.get(spinnerRestaurant.getSelectedItemPosition() - 1).restaurantId;
            }
            AuthApiModels.MealRecordRequest request = new AuthApiModels.MealRecordRequest(
                    UserInfoManager.getCurrentUserId(this),
                    isEatOut ? "eat_out" : CalorieFormatUtils.SOURCE_MANUAL,
                    null,
                    restaurantId,
                    title,
                    calories,
                    CalorieFormatUtils.SOURCE_MANUAL,
                    etNote.getText().toString().trim(),
                    selectedDate.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault()))
            );
            AuthApiClient.recordMeal(this, request, new AuthApiClient.CalorieMutationCallback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        DataRefreshBus.refreshAll();
                        loadSummary();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(CalorieActivity.this, message, Toast.LENGTH_SHORT).show());
                }
            });
        });

        DialogHelper.showWide(dialog, this);
    }

    private String formatCalories(double value) {
        return formatPlain(value) + " kcal";
    }

    private String formatPlain(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.1f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.RecordHolder> {
        @NonNull
        @Override
        public RecordHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_meal_record, parent, false);
            return new RecordHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecordHolder holder, int position) {
            AuthApiModels.MealRecord item = records.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvCalories.setText(formatCalories(item.calories));
            String source = CalorieFormatUtils.SOURCE_MANUAL.equals(item.calorieSource) ? "手动" : "自动";
            holder.tvMeta.setText(item.userName + " · " + item.mealType + " · " + source);
            holder.tvNote.setVisibility(TextUtils.isEmpty(item.note) ? View.GONE : View.VISIBLE);
            holder.tvNote.setText(item.note);

            int color = 0xFF1976D2;
            if ("cook".equals(item.mealType)) color = 0xFF07C160;
            if ("eat_out".equals(item.mealType)) color = 0xFFFF8F00;
            holder.colorView.setBackgroundColor(color);

            int currentUserId = UserInfoManager.getCurrentUserId(CalorieActivity.this);
            holder.btnDelete.setVisibility(item.userId == currentUserId ? View.VISIBLE : View.GONE);
            holder.btnDelete.setOnClickListener(v -> AuthApiClient.deleteMealRecord(CalorieActivity.this, item.id, currentUserId, new AuthApiClient.CalorieMutationCallback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        DataRefreshBus.refreshAll();
                        loadSummary();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(CalorieActivity.this, message, Toast.LENGTH_SHORT).show());
                }
            }));
        }

        @Override
        public int getItemCount() {
            return records.size();
        }

        class RecordHolder extends RecyclerView.ViewHolder {
            View colorView;
            TextView tvTitle;
            TextView tvMeta;
            TextView tvNote;
            TextView tvCalories;
            TextView btnDelete;

            RecordHolder(View itemView) {
                super(itemView);
                colorView = itemView.findViewById(R.id.view_meal_color);
                tvTitle = itemView.findViewById(R.id.tv_meal_title);
                tvMeta = itemView.findViewById(R.id.tv_meal_meta);
                tvNote = itemView.findViewById(R.id.tv_meal_note);
                tvCalories = itemView.findViewById(R.id.tv_meal_calories);
                btnDelete = itemView.findViewById(R.id.btn_delete_meal);
            }
        }
    }
}
