package com.example.couplecredit.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.model.BillBean;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.NicknameCache;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.List;
import java.util.Map;

public class BillAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int TYPE_DATE_HEADER = 0;
    private static final int TYPE_BILL_ITEM = 1;
    
    private List<Object> items; // 混合数据：Map<String, List<BillBean>>(日期组) 
    private Context context;
    private OnItemClickListener mListener;
    private int currentUserId = -1;
    private Integer currentRelationshipId = null;
    private int currentUserRole = -1; // 1=邀请者, 2=被邀请者
    
    // 昵称缓存
    private String currentUserNickname = null;
    private String partnerNickname = null;
    private boolean nicknamesCached = false;
    
    // 定义RecyclerView专用的点击监听器接口
    public interface OnItemClickListener {
        void onItemClick(View view, int position, BillBean bill);
    }
    
    public BillAdapter(Context context, List<Object> items, OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.mListener = listener;
        
        // 获取当前用户信息
        loadCurrentUserInfo();
    }
    
    private void loadCurrentUserInfo() {
        UserInfoManager.getCurrentUserInfo(context, new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                currentUserId = userId;
                currentRelationshipId = relationshipId;

                // 获取用户角色
                AuthApiClient.getCoupleRole(context, userId, new AuthApiClient.CoupleRoleCallback() {
                    @Override
                    public void onResult(boolean hasRelationship, int role, int relId) {
                        if (hasRelationship) {
                            currentUserRole = role;
                        } else {
                            currentUserRole = 1;
                        }
                        preloadNicknames();
                    }

                    @Override
                    public void onError(String error) {
                        currentUserRole = 1;
                        preloadNicknames();
                    }
                });
            }

            @Override
            public void onError(String error) {
                // 获取失败时使用默认值
                currentUserId = UserInfoManager.getCurrentUserId(context);
                currentUserRole = 1;
                preloadNicknames();
            }
        });
    }
    
    private void setOwnerText(TextView tvOwner, BillBean bill) {
        int ownerValue = bill.getOwner();
        boolean isHelp = bill.getIsHelp() == 1;
        String ownerText;

        if (ownerValue == 3) {
            ownerText = "共同";
        } else if (currentRelationshipId == null) {
            ownerText = "自己";
        } else if (currentUserRole != 1 && currentUserRole != 2) {
            ownerText = "自己";
        } else {
            switch (ownerValue) {
                case 1:
                    ownerText = (currentUserRole == 1)
                            ? ((nicknamesCached && currentUserNickname != null) ? currentUserNickname : "自己")
                            : ((nicknamesCached && partnerNickname != null) ? partnerNickname : "对方");
                    break;
                case 2:
                    ownerText = (currentUserRole == 2)
                            ? ((nicknamesCached && currentUserNickname != null) ? currentUserNickname : "自己")
                            : ((nicknamesCached && partnerNickname != null) ? partnerNickname : "对方");
                    break;
                default:
                    ownerText = "未知";
                    break;
            }
        }

        if (isHelp) ownerText += "（帮）";

        String planName = bill.getSharedPlanName();
        if (planName != null && !planName.trim().isEmpty()) {
            ownerText += " · " + planName;
        }

        tvOwner.setText(ownerText);
    }

    // 预加载昵称缓存，避免每次显示账单时都查询数据库
    private void preloadNicknames() {
        if (currentUserId == -1) {
            nicknamesCached = true;
            notifyDataSetChanged();
            return;
        }
        
        // 先尝试从缓存获取当前用户昵称
        String cachedNickname = NicknameCache.getCachedNickname(context, String.valueOf(currentUserId));
        if (cachedNickname != null) {
            currentUserNickname = cachedNickname;
            
            // 如果有情侣关系，继续获取对方昵称
            if (currentRelationshipId != null) {
                loadPartnerNickname();
            } else {
                nicknamesCached = true;
                notifyDataSetChanged();
            }
            return;
        }
        
        // 缓存不存在或过期，查询API
        AuthApiClient.getUserProfile(context, currentUserId, new AuthApiClient.ProfileCallback() {
            @Override
            public void onSuccess(AuthApiModels.UserProfileData profile) {
                String nickname = profile.nickname;
                String displayName = (nickname != null && !nickname.trim().isEmpty()) ? nickname : "自己";
                currentUserNickname = displayName;

                // 缓存昵称
                NicknameCache.cacheNickname(context, String.valueOf(currentUserId), displayName);

                // 如果有情侣关系，继续获取对方昵称
                if (currentRelationshipId != null) {
                    loadPartnerNickname();
                } else {
                    nicknamesCached = true;
                    notifyDataSetChanged();
                }
            }

            @Override
            public void onError(String e) {
                currentUserNickname = "自己";

                // 即使查询失败也缓存默认值
                NicknameCache.cacheNickname(context, String.valueOf(currentUserId), "自己");

                if (currentRelationshipId != null) {
                    loadPartnerNickname();
                } else {
                    nicknamesCached = true;
                    notifyDataSetChanged();
                }
            }
        });
    }
    
    private void loadPartnerNickname() {
        AuthApiClient.queryCoupleInfo(context, currentUserId, new AuthApiClient.CoupleInfoCallback() {
            @Override
            public void onCoupleFound(int partnerId, String partnerName, String partnerNickname, String partnerAvatarUrl, int relationshipId) {
                BillAdapter.this.partnerNickname = (partnerNickname != null && !partnerNickname.trim().isEmpty()) ? partnerNickname : partnerName;
                if (BillAdapter.this.partnerNickname == null) BillAdapter.this.partnerNickname = "对方";

                nicknamesCached = true;
                notifyDataSetChanged();
            }

            @Override
            public void onNoCoupleFound() {
                partnerNickname = "对方";
                nicknamesCached = true;
                notifyDataSetChanged();
            }

            @Override
            public void onError(String error) {
                partnerNickname = "对方";
                nicknamesCached = true;
                notifyDataSetChanged();
            }
        });
    }
    
    @Override
    public int getItemViewType(int position) {
        // 根据ClassicModelFragment的处理方式，所有项都是日期分组
        return TYPE_DATE_HEADER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_date_header, parent, false);
        return new DateHeaderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DateHeaderViewHolder) {
            DateHeaderViewHolder dateHolder = (DateHeaderViewHolder) holder;
            Map<String, List<BillBean>> dateGroup = (Map<String, List<BillBean>>) items.get(position);
            
            // 获取日期和对应的账单列表
            Map.Entry<String, List<BillBean>> entry = dateGroup.entrySet().iterator().next();
            String date = entry.getKey();
            List<BillBean> bills = entry.getValue();
            
            // 设置日期（去掉年份）
            String displayDate = date;
            if (date.length() >= 10 && date.contains("-")) {
                // 假设日期格式为 YYYY-MM-DD，提取 MM-DD 部分
                displayDate = date.substring(5); // 从第5个字符开始，跳过 "YYYY-"
            }
            dateHolder.tvDate.setText(displayDate);
            
            // 清空之前的账单项
            dateHolder.llBillsContainer.removeAllViews();
            
            // 动态添加账单项
            for (BillBean bill : bills) {
                View billView = LayoutInflater.from(context).inflate(R.layout.item_bill, dateHolder.llBillsContainer, false);
                
                TextView tvKind = billView.findViewById(R.id.tv_category_name);
                TextView tvMoney = billView.findViewById(R.id.tv_amount);
                TextView tvOwner = billView.findViewById(R.id.tv_owner);
                ImageView ivIcon = billView.findViewById(R.id.iv_category_icon);
                
                tvKind.setText(bill.getTitle());
                tvMoney.setText(String.format("%.2f", bill.getFare()));
                setOwnerText(tvOwner, bill);
                
                // 设置分类图标
                ivIcon.setImageResource(bill.getIconResId());
                ivIcon.setBackground(null); // 移除默认背景
                
                // 设置颜色（根据收入支出类型判断）
                if (bill.getIncomeType() == 0) { // 支出
                    tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                    tvMoney.setText("-" + String.format("%.2f", bill.getFare()));
                } else { // 收入
                    tvMoney.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                    tvMoney.setText("+" + String.format("%.2f", bill.getFare()));
                }
                
                // 为动态添加的账单项设置点击监听器
                final BillBean finalBill = bill;
                billView.setOnClickListener(v -> {
                    if (mListener != null) {
                        mListener.onItemClick(v, position, finalBill);
                    }
                });
                
                dateHolder.llBillsContainer.addView(billView);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
    
    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvDate;
        LinearLayout llBillsContainer;
        
        public DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_date_header);
            llBillsContainer = itemView.findViewById(R.id.ll_bills_container);
        }
    }
    
    // 账单项ViewHolder（用于聊天模式）

}