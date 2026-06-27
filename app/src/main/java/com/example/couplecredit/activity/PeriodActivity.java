package com.example.couplecredit.activity;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PeriodActivity extends AppCompatActivity {

    private int currentUserId;

    private GridView gvCalendar;
    private TextView tvMonthLabel;
    private LinearLayout llPredictionCard;
    private TextView tvPredictionInfo, tvPartnerPrediction;
    private TextView tvEmptyRecords;
    private RecyclerView rvRecords;
    private Button btnStartPeriod, btnEndPeriod;

    private YearMonth displayMonth;
    private CalendarGridAdapter calendarAdapter;
    private RecordAdapter recordAdapter;
    private List<AuthApiModels.PeriodRecordData> records = new ArrayList<>();
    private Integer averageCycleDays;
    private String predictedNextStart;
    private Integer partnerAverageCycleDays;
    private String partnerPredictedNextStart;

    private LocalDate selectedDate = null;
    private final DateTimeFormatter isoFmt = DateTimeFormatter.ISO_LOCAL_DATE;
    private final DateTimeFormatter shortFmt = DateTimeFormatter.ofPattern("M/d");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_period);

        currentUserId = UserInfoManager.getCurrentUserId(this);
        if (currentUserId == -1) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        displayMonth = YearMonth.now();

        initViews();
        setupListeners();
        loadRecords();
    }

    private void initViews() {
        ImageButton btnBack = findViewById(R.id.iv_back);
        btnBack.setOnClickListener(v -> finish());

        gvCalendar = findViewById(R.id.gv_calendar);
        tvMonthLabel = findViewById(R.id.tv_month_label);
        llPredictionCard = findViewById(R.id.ll_prediction_card);
        tvPredictionInfo = findViewById(R.id.tv_prediction_info);
        tvPartnerPrediction = findViewById(R.id.tv_partner_prediction);
        tvEmptyRecords = findViewById(R.id.tv_empty_records);
        btnStartPeriod = findViewById(R.id.btn_start_period);
        btnEndPeriod = findViewById(R.id.btn_end_period);

        rvRecords = findViewById(R.id.rv_records);
        recordAdapter = new RecordAdapter(records, currentUserId, record -> {
            new AlertDialog.Builder(this, R.style.CustomDialogStyle)
                    .setTitle("删除记录")
                    .setMessage("确定要删除这条经期记录吗？")
                    .setPositiveButton("删除", (d, w) -> deleteRecord(record.id))
                    .setNegativeButton("取消", null)
                    .show();
        });
        rvRecords.setLayoutManager(new LinearLayoutManager(this));
        rvRecords.setAdapter(recordAdapter);

        calendarAdapter = new CalendarGridAdapter(this);
        gvCalendar.setAdapter(calendarAdapter);

        updateMonthLabel();
    }

    private void setupListeners() {
        ImageButton btnPrev = findViewById(R.id.btn_prev_month);
        ImageButton btnNext = findViewById(R.id.btn_next_month);

        btnPrev.setOnClickListener(v -> {
            displayMonth = displayMonth.minusMonths(1);
            selectedDate = null;
            updateMonthLabel();
            calendarAdapter.notifyDataSetChanged();
            updateButtons();
        });

        btnNext.setOnClickListener(v -> {
            displayMonth = displayMonth.plusMonths(1);
            selectedDate = null;
            updateMonthLabel();
            calendarAdapter.notifyDataSetChanged();
            updateButtons();
        });

        gvCalendar.setOnItemClickListener((parent, view, position, id) -> {
            LocalDate date = (LocalDate) parent.getItemAtPosition(position);
            if (date == null) return;
            if (date.equals(selectedDate)) {
                selectedDate = null;
            } else {
                selectedDate = date;
            }
            calendarAdapter.notifyDataSetChanged();
            updateButtons();
        });

        btnStartPeriod.setOnClickListener(v -> onStartButtonClicked());
        btnEndPeriod.setOnClickListener(v -> onEndButtonClicked());
    }

    private void updateMonthLabel() {
        tvMonthLabel.setText(displayMonth.getYear() + "年" + displayMonth.getMonthValue() + "月");
    }

    private void loadRecords() {
        AuthApiClient.getPeriodRecords(this, currentUserId, new AuthApiClient.PeriodListCallback() {
            @Override
            public void onSuccess(AuthApiModels.PeriodListResponse response) {
                runOnUiThread(() -> {
                    if (response.data != null) {
                        records.clear();
                        records.addAll(response.data.records);
                        averageCycleDays = response.data.averageCycleDays;
                        predictedNextStart = response.data.predictedNextStart;
                        partnerAverageCycleDays = response.data.partnerAverageCycleDays;
                        partnerPredictedNextStart = response.data.partnerPredictedNextStart;

                        updatePredictionCard();
                        updateRecordList();
                        updateButtons();
                        calendarAdapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(PeriodActivity.this, "加载失败: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updatePredictionCard() {
        TextView tvEmoji = findViewById(R.id.tv_prediction_emoji);
        if (averageCycleDays != null && predictedNextStart != null) {
            llPredictionCard.setVisibility(View.VISIBLE);
            String info = "平均周期: " + averageCycleDays + " 天\n预计下次经期: " + predictedNextStart;

            LocalDate predicted = LocalDate.parse(predictedNextStart, isoFmt);
            long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), predicted);
            if (daysUntil > 0) {
                info += "（还有 " + daysUntil + " 天）";
            } else if (daysUntil == 0) {
                info += "（就是今天呀）";
            }

            tvPredictionInfo.setText(info);

            String emoji;
            if (daysUntil <= 0) emoji = "✨";
            else if (daysUntil <= 3) emoji = "🌸";
            else if (daysUntil <= 7) emoji = "🌷";
            else emoji = "🌙";
            tvEmoji.setText(emoji);

            if (partnerAverageCycleDays != null && partnerPredictedNextStart != null) {
                tvPartnerPrediction.setVisibility(View.VISIBLE);
                tvPartnerPrediction.setText("💗 伴侣预测: 周期 " + partnerAverageCycleDays + " 天, 预计 " + partnerPredictedNextStart);
            } else {
                tvPartnerPrediction.setVisibility(View.GONE);
            }
        } else {
            llPredictionCard.setVisibility(View.VISIBLE);
            tvPredictionInfo.setText("记录至少两次经期，就能预测下次日期啦 🌸");
            tvPartnerPrediction.setVisibility(View.GONE);
            tvEmoji.setText("🌿");
        }
    }

    private void updateRecordList() {
        recordAdapter.notifyDataSetChanged();
        tvEmptyRecords.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        rvRecords.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private AuthApiModels.PeriodRecordData findRecordAtDate(LocalDate date) {
        for (AuthApiModels.PeriodRecordData r : records) {
            try {
                LocalDate start = LocalDate.parse(r.startDate, isoFmt);
                LocalDate end = r.endDate != null ? LocalDate.parse(r.endDate, isoFmt) : LocalDate.now();
                if (!date.isBefore(start) && !date.isAfter(end)) return r;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private AuthApiModels.PeriodRecordData findOpenPeriod() {
        for (AuthApiModels.PeriodRecordData r : records) {
            if (r.userId == currentUserId && r.endDate == null) return r;
        }
        return null;
    }

    // ---- 按钮联动 ----

    private void updateButtons() {
        if (selectedDate == null) {
            // 默认：开始记录（今天）/ 结束本期（今天）
            boolean hasOpen = findOpenPeriod() != null;
            btnStartPeriod.setText("🌸 开始记录");
            btnStartPeriod.setEnabled(true);
            btnStartPeriod.setAlpha(1.0f);
            btnEndPeriod.setText("结束本期");
            btnEndPeriod.setEnabled(hasOpen);
            btnEndPeriod.setAlpha(hasOpen ? 1.0f : 0.5f);
        } else {
            AuthApiModels.PeriodRecordData r = findRecordAtDate(selectedDate);
            if (r == null) {
                // 空白日：从该日起 / 取消选中
                btnStartPeriod.setText("🌸 从 " + selectedDate.format(shortFmt) + " 起");
                btnStartPeriod.setEnabled(true);
                btnStartPeriod.setAlpha(1.0f);
                btnEndPeriod.setText("✕ 取消选中");
                btnEndPeriod.setEnabled(true);
                btnEndPeriod.setAlpha(1.0f);
            } else if (r.userId != currentUserId) {
                // 伴侣的记录：只读
                String name = r.userName != null ? r.userName : "伴侣";
                btnStartPeriod.setText("💞 " + name + " 的记录");
                btnStartPeriod.setEnabled(false);
                btnStartPeriod.setAlpha(0.5f);
                btnEndPeriod.setText("✕ 取消选中");
                btnEndPeriod.setEnabled(true);
                btnEndPeriod.setAlpha(1.0f);
            } else {
                // 自己的经期日：编辑 / 删除
                btnStartPeriod.setText("✏\uFE0F 编辑此记录");
                btnStartPeriod.setEnabled(true);
                btnStartPeriod.setAlpha(1.0f);
                btnEndPeriod.setText("\uD83D\uDDD1\uFE0F 删除");
                btnEndPeriod.setEnabled(true);
                btnEndPeriod.setAlpha(1.0f);
            }
        }
    }

    private void onStartButtonClicked() {
        if (selectedDate == null) {
            showCreateDialog(LocalDate.now());
        } else {
            AuthApiModels.PeriodRecordData r = findRecordAtDate(selectedDate);
            if (r == null) {
                showCreateDialog(selectedDate);
            } else {
                showEditDialog(r);
            }
        }
    }

    private void onEndButtonClicked() {
        if (selectedDate == null) {
            AuthApiModels.PeriodRecordData open = findOpenPeriod();
            if (open == null) {
                Toast.makeText(this, "没有进行中的经期", Toast.LENGTH_SHORT).show();
                return;
            }
            endPeriod(open.id, LocalDate.now());
        } else {
            AuthApiModels.PeriodRecordData r = findRecordAtDate(selectedDate);
            if (r == null) {
                // 空白日 → 取消选中
                selectedDate = null;
                calendarAdapter.notifyDataSetChanged();
                updateButtons();
            } else {
                // 经期日 → 确认删除
                new AlertDialog.Builder(this, R.style.CustomDialogStyle)
                        .setTitle("删除记录")
                        .setMessage("确定要删除 " + r.startDate + " 这条经期记录吗？")
                        .setPositiveButton("删除", (d, w) -> deleteRecord(r.id))
                        .setNegativeButton("取消", null)
                        .show();
            }
        }
    }

    // ---- 弹框 ----

    private void showCreateDialog(LocalDate initialDate) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llEndRow = dialogView.findViewById(R.id.ll_end_date_row);
        TextView tvStart = dialogView.findViewById(R.id.tv_start_date_value);
        EditText etNote = dialogView.findViewById(R.id.et_input);

        tvTitle.setVisibility(View.VISIBLE);
        tvTitle.setText(initialDate.equals(LocalDate.now()) ? "🌸 开始记录" : "🌸 补录经期");
        llEndRow.setVisibility(View.GONE);

        final String[] startDate = {initialDate.format(isoFmt)};
        tvStart.setText(startDate[0]);
        tvStart.setOnClickListener(v -> pickDate(startDate[0], picked -> {
            startDate[0] = picked;
            tvStart.setText(picked);
        }));

        new AlertDialog.Builder(this, R.style.CustomDialogStyle)
                .setView(dialogView)
                .setPositiveButton("保存", (d, w) -> {
                    String note = etNote.getText().toString().trim();
                    AuthApiModels.CreatePeriodRequest req = new AuthApiModels.CreatePeriodRequest(
                            currentUserId, startDate[0], note);
                    AuthApiClient.createPeriodRecord(this, req, new AuthApiClient.MutationCallback() {
                        @Override public void onSuccess() {
                            runOnUiThread(() -> {
                                Toast.makeText(PeriodActivity.this, "已记录", Toast.LENGTH_SHORT).show();
                                selectedDate = null;
                                loadRecords();
                            });
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> Toast.makeText(PeriodActivity.this, "记录失败: " + message, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEditDialog(AuthApiModels.PeriodRecordData record) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llEndRow = dialogView.findViewById(R.id.ll_end_date_row);
        TextView tvStart = dialogView.findViewById(R.id.tv_start_date_value);
        TextView tvEnd = dialogView.findViewById(R.id.tv_end_date_value);
        ImageButton btnClearEnd = dialogView.findViewById(R.id.btn_clear_end_date);
        EditText etNote = dialogView.findViewById(R.id.et_input);

        tvTitle.setVisibility(View.VISIBLE);
        tvTitle.setText("✏\uFE0F 编辑记录");
        llEndRow.setVisibility(View.VISIBLE);
        btnClearEnd.setVisibility(View.GONE);

        final String[] startDate = {record.startDate};
        final String[] endDate = {record.endDate};
        tvStart.setText(startDate[0]);
        tvEnd.setText(endDate[0] == null ? "进行中（点击设置）" : endDate[0]);
        if (record.note != null) etNote.setText(record.note);

        tvStart.setOnClickListener(v -> pickDate(startDate[0], picked -> {
            startDate[0] = picked;
            tvStart.setText(picked);
        }));
        tvEnd.setOnClickListener(v -> pickDate(endDate[0] == null ? LocalDate.now().format(isoFmt) : endDate[0], picked -> {
            endDate[0] = picked;
            tvEnd.setText(picked);
        }));

        new AlertDialog.Builder(this, R.style.CustomDialogStyle)
                .setView(dialogView)
                .setPositiveButton("保存", (d, w) -> {
                    String note = etNote.getText().toString().trim();
                    AuthApiModels.UpdatePeriodRequest req = new AuthApiModels.UpdatePeriodRequest(
                            currentUserId, startDate[0], endDate[0], note);
                    AuthApiClient.updatePeriodRecord(this, record.id, req, new AuthApiClient.MutationCallback() {
                        @Override public void onSuccess() {
                            runOnUiThread(() -> {
                                Toast.makeText(PeriodActivity.this, "已更新", Toast.LENGTH_SHORT).show();
                                selectedDate = null;
                                loadRecords();
                            });
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> Toast.makeText(PeriodActivity.this, "更新失败: " + message, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickDate(String currentIso, OnDatePicked callback) {
        LocalDate cur;
        try { cur = LocalDate.parse(currentIso, isoFmt); }
        catch (Exception e) { cur = LocalDate.now(); }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, cur.getYear());
        c.set(Calendar.MONTH, cur.getMonthValue() - 1);
        c.set(Calendar.DAY_OF_MONTH, cur.getDayOfMonth());
        new DatePickerDialog(this, (view, y, m, day) -> {
            String iso = String.format("%04d-%02d-%02d", y, m + 1, day);
            callback.onPicked(iso);
        }, cur.getYear(), cur.getMonthValue() - 1, cur.getDayOfMonth()).show();
    }

    interface OnDatePicked { void onPicked(String isoDate); }

    private void endPeriod(int recordId, LocalDate endDate) {
        AuthApiModels.UpdatePeriodRequest req = new AuthApiModels.UpdatePeriodRequest(
                currentUserId, null, endDate.format(isoFmt), null);
        AuthApiClient.updatePeriodRecord(this, recordId, req, new AuthApiClient.MutationCallback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(PeriodActivity.this, "已结束经期", Toast.LENGTH_SHORT).show();
                    loadRecords();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(PeriodActivity.this, "操作失败: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteRecord(int recordId) {
        AuthApiClient.deletePeriodRecord(this, recordId, currentUserId, new AuthApiClient.MutationCallback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(PeriodActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    selectedDate = null;
                    loadRecords();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(PeriodActivity.this, "删除失败: " + message, Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---- Calendar Grid Adapter ----

    class CalendarGridAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final List<LocalDate> dayCells = new ArrayList<>();
        private final Set<LocalDate> periodDays = new HashSet<>();
        private final Set<LocalDate> partnerDays = new HashSet<>();
        private final Set<LocalDate> predictedDays = new HashSet<>();

        CalendarGridAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        void updateData() {
            dayCells.clear();
            periodDays.clear();
            partnerDays.clear();
            predictedDays.clear();

            LocalDate firstOfMonth = displayMonth.atDay(1);
            int daysInMonth = displayMonth.lengthOfMonth();
            int startDayOfWeek = firstOfMonth.getDayOfWeek().getValue() % 7;

            for (int i = 0; i < startDayOfWeek; i++) {
                dayCells.add(null);
            }
            for (int day = 1; day <= daysInMonth; day++) {
                dayCells.add(firstOfMonth.withDayOfMonth(day));
            }

            for (AuthApiModels.PeriodRecordData r : records) {
                try {
                    LocalDate start = LocalDate.parse(r.startDate, isoFmt);
                    LocalDate end = r.endDate != null ? LocalDate.parse(r.endDate, isoFmt) : LocalDate.now();
                    Set<LocalDate> target = r.userId == currentUserId ? periodDays : partnerDays;
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        target.add(d);
                    }
                } catch (Exception ignored) {}
            }

            if (predictedNextStart != null && averageCycleDays != null) {
                try {
                    LocalDate predicted = LocalDate.parse(predictedNextStart, isoFmt);
                    int periodLength = 5;
                    for (int i = 0; i < periodLength; i++) {
                        predictedDays.add(predicted.plusDays(i));
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override public int getCount() { updateData(); return dayCells.size(); }
        @Override public Object getItem(int position) { return dayCells.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_calendar_day, parent, false);
            }

            TextView tvDay = convertView.findViewById(R.id.tv_day);
            View vIndicator = convertView.findViewById(R.id.v_indicator);

            LocalDate date = dayCells.get(position);
            if (date == null) {
                tvDay.setText("");
                tvDay.setBackground(null);
                tvDay.setTextColor(Color.TRANSPARENT);
                vIndicator.setVisibility(View.GONE);
                return convertView;
            }

            tvDay.setText(String.valueOf(date.getDayOfMonth()));
            tvDay.setTextColor(Color.parseColor("#6B5566"));
            tvDay.setBackground(null);

            LocalDate today = LocalDate.now();
            boolean isToday = date.equals(today);
            boolean isPeriod = periodDays.contains(date);
            boolean isPartner = partnerDays.contains(date);
            boolean isPredicted = predictedDays.contains(date);
            boolean isSelected = date.equals(selectedDate);

            if (isPeriod && (isToday || isSelected)) {
                tvDay.setTextColor(Color.WHITE);
                tvDay.setBackgroundResource(R.drawable.bg_period_today_chip);
            } else if (isPeriod) {
                tvDay.setTextColor(Color.parseColor("#D8456D"));
                tvDay.setBackgroundResource(R.drawable.bg_period_day_chip);
            } else if (isPartner) {
                tvDay.setTextColor(Color.parseColor("#8A6FA0"));
                tvDay.setBackgroundResource(R.drawable.bg_period_partner_chip);
            } else if (isPredicted && (isToday || isSelected)) {
                tvDay.setTextColor(Color.parseColor("#D8456D"));
                tvDay.setBackgroundResource(R.drawable.bg_period_today_outline);
            } else if (isPredicted) {
                tvDay.setTextColor(Color.parseColor("#E37D9A"));
                tvDay.setBackgroundResource(R.drawable.bg_period_predicted_chip);
            } else if (isToday || isSelected) {
                tvDay.setTextColor(Color.parseColor("#FF6B8A"));
                tvDay.setBackgroundResource(R.drawable.bg_period_selected_chip);
            } else {
                tvDay.setBackground(null);
            }

            if (isPeriod) {
                vIndicator.setVisibility(View.VISIBLE);
                vIndicator.setBackgroundResource(R.drawable.bg_period_day);
            } else if (isPartner) {
                vIndicator.setVisibility(View.VISIBLE);
                vIndicator.setBackgroundResource(R.drawable.bg_period_predicted);
            } else if (isPredicted) {
                vIndicator.setVisibility(View.VISIBLE);
                vIndicator.setBackgroundResource(R.drawable.bg_period_predicted);
            } else {
                vIndicator.setVisibility(View.GONE);
            }

            return convertView;
        }
    }

    // ---- Record List Adapter ----

    static class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {
        private final List<AuthApiModels.PeriodRecordData> records;
        private final int currentUserId;
        private final OnDeleteListener deleteListener;
        private final DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

        interface OnDeleteListener { void onDelete(AuthApiModels.PeriodRecordData record); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDateRange, tvDuration, tvNote;
            ImageButton btnDelete;

            ViewHolder(View v) {
                super(v);
                tvDateRange = v.findViewById(R.id.tv_date_range);
                tvDuration = v.findViewById(R.id.tv_duration);
                tvNote = v.findViewById(R.id.tv_record_note);
                btnDelete = v.findViewById(R.id.btn_delete_record);
            }
        }

        RecordAdapter(List<AuthApiModels.PeriodRecordData> records, int currentUserId, OnDeleteListener deleteListener) {
            this.records = records;
            this.currentUserId = currentUserId;
            this.deleteListener = deleteListener;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_period_record, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder h, int pos) {
            AuthApiModels.PeriodRecordData r = records.get(pos);

            String display = r.startDate;
            if (r.endDate != null) {
                display += " ~ " + r.endDate;
                try {
                    LocalDate start = LocalDate.parse(r.startDate, fmt);
                    LocalDate end = LocalDate.parse(r.endDate, fmt);
                    long days = ChronoUnit.DAYS.between(start, end) + 1;
                    h.tvDuration.setText(days + " 天");
                } catch (Exception e) {
                    h.tvDuration.setText("");
                }
            } else {
                display += " ~ 进行中";
                try {
                    LocalDate start = LocalDate.parse(r.startDate, fmt);
                    long days = ChronoUnit.DAYS.between(start, LocalDate.now()) + 1;
                    h.tvDuration.setText("第 " + days + " 天");
                } catch (Exception e) {
                    h.tvDuration.setText("进行中");
                }
            }
            h.tvDateRange.setText(display);

            if (r.note != null && !r.note.isEmpty()) {
                h.tvNote.setVisibility(View.VISIBLE);
                h.tvNote.setText(r.note);
            } else {
                h.tvNote.setVisibility(View.GONE);
            }

            if (r.userId == currentUserId) {
                h.btnDelete.setVisibility(View.VISIBLE);
                h.btnDelete.setOnClickListener(v -> deleteListener.onDelete(r));
            } else {
                h.btnDelete.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return records.size(); }
    }
}
