package com.example.couplecredit.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.PlatformIconHelper;

import java.util.List;

public class AccountAdapter extends RecyclerView.Adapter<AccountAdapter.ViewHolder> {

    public interface OnAccountClickListener {
        void onAccountClick(AuthApiModels.PasswordAccountItemData account);
    }

    private List<AuthApiModels.PasswordAccountItemData> accounts;
    private final OnAccountClickListener listener;

    public AccountAdapter(List<AuthApiModels.PasswordAccountItemData> accounts, OnAccountClickListener listener) {
        this.accounts = accounts;
        this.listener = listener;
    }

    public void updateData(List<AuthApiModels.PasswordAccountItemData> newAccounts) {
        this.accounts = newAccounts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_account_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthApiModels.PasswordAccountItemData account = accounts.get(position);
        Context ctx = holder.itemView.getContext();

        holder.tvPlatformName.setText(account.platformName);
        holder.tvAccount.setText(account.accountIdentifier);

        // 分类标签
        String category = account.category != null && !account.category.isEmpty() ? account.category : "其他";
        holder.tvCategory.setText(category);

        // 平台图标：优先内置匹配，否则首字母彩色头像
        int iconRes = PlatformIconHelper.getIconResForPlatform(ctx, account.platformName);
        if (iconRes != 0) {
            holder.ivPlatformIcon.setVisibility(View.VISIBLE);
            holder.tvPlatformInitial.setVisibility(View.GONE);
            holder.ivPlatformIcon.setImageResource(iconRes);
        } else {
            holder.ivPlatformIcon.setVisibility(View.GONE);
            holder.tvPlatformInitial.setVisibility(View.VISIBLE);
            holder.tvPlatformInitial.setText(PlatformIconHelper.getInitial(account.platformName));
            // 动态设置背景色
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(PlatformIconHelper.getColorForPlatform(account.platformName));
            holder.tvPlatformInitial.setBackground(bg);
        }

        // 复制账号
        holder.ivCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("account", account.accountIdentifier));
                Toast.makeText(ctx, "已复制账号", Toast.LENGTH_SHORT).show();
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAccountClick(account);
            }
        });
    }

    @Override
    public int getItemCount() {
        return accounts == null ? 0 : accounts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPlatformIcon;
        TextView tvPlatformInitial;
        TextView tvPlatformName, tvAccount, tvCategory;
        ImageView ivCopy;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPlatformIcon = itemView.findViewById(R.id.iv_platform_icon);
            tvPlatformInitial = itemView.findViewById(R.id.tv_platform_initial);
            tvPlatformName = itemView.findViewById(R.id.tv_platform_name);
            tvAccount = itemView.findViewById(R.id.tv_account);
            tvCategory = itemView.findViewById(R.id.tv_category);
            ivCopy = itemView.findViewById(R.id.iv_copy);
        }
    }
}
