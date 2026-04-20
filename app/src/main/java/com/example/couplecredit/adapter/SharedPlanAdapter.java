package com.example.couplecredit.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;

import java.util.List;
import java.util.Locale;

public class SharedPlanAdapter extends RecyclerView.Adapter<SharedPlanAdapter.ViewHolder> {

    public interface SharedPlanActionListener {
        void onAddMoney(AuthApiModels.SharedPlanData item);
        void onDelete(AuthApiModels.SharedPlanData item);
    }

    private List<AuthApiModels.SharedPlanData> items;
    private final SharedPlanActionListener listener;

    public SharedPlanAdapter(List<AuthApiModels.SharedPlanData> items, SharedPlanActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.SharedPlanData> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shared_plan, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.SharedPlanData item = items.get(position);
        holder.tvName.setText(item.name);
        holder.tvVisibility.setText("self".equals(item.visibility) ? "仅自己" : "双方可见");
        holder.tvBalance.setText(formatMoney(item.currentBalance));
        holder.tvInitial.setText("初始值 " + formatMoney(item.initialAmount));
        holder.btnAddMoney.setOnClickListener(v -> {
            if (listener != null) listener.onAddMoney(item);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item);
        });
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    private String formatMoney(double amount) {
        return "￥" + String.format(Locale.getDefault(), "%.2f", amount);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvVisibility;
        TextView tvBalance;
        TextView tvInitial;
        TextView btnAddMoney;
        TextView btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_plan_name);
            tvVisibility = itemView.findViewById(R.id.tv_visibility);
            tvBalance = itemView.findViewById(R.id.tv_balance);
            tvInitial = itemView.findViewById(R.id.tv_initial);
            btnAddMoney = itemView.findViewById(R.id.btn_add_money);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
