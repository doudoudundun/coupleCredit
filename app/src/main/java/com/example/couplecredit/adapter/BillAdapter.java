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

import com.example.couplecredit.BillBean;
import com.example.couplecredit.R;
import com.example.couplecredit.database.CoupleRelationshipHelper;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import com.example.couplecredit.function.UserInfoManager;

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
                
                // 如果有情侣关系，获取用户角色
                if (relationshipId != null) {
                    CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
                    coupleHelper.getUserRole(userId, new CoupleRelationshipHelper.UserRoleCallback() {
                        @Override
                        public void onRoleFound(int ownerId) {
                            currentUserRole = ownerId;
                            // 预加载昵称缓存
                            preloadNicknames();
                        }
                        
                        @Override
                        public void onNoRelationshipFound() {
                            currentUserRole = 1; // 默认为1
                            preloadNicknames();
                        }
                        
                        @Override
                        public void onError(String error) {
                            currentUserRole = 1; // 默认为1
                            preloadNicknames();
                        }
                    });
                } else {
                    currentUserRole = 1; // 无情侣关系时默认为1
                    preloadNicknames();
                }
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
        if (currentRelationshipId == null) {
            // 无情侣关系，显示"自己"
            String text = "自己";
            if (isHelp) text += "（帮）";
            tvOwner.setText(text);
            return;
        }
        
        switch (ownerValue) {
            case 1: // 邀请者
                if (currentUserRole == 1) {
                    // 当前用户是邀请者，显示自己的昵称
                    String text = (nicknamesCached && currentUserNickname != null) ? currentUserNickname : "自己";
                    if (isHelp) text += "（帮）";
                    tvOwner.setText(text);
                } else {
                    // 当前用户是被邀请者，显示对方(邀请者)的昵称
                    String text = (nicknamesCached && partnerNickname != null) ? partnerNickname : "对方";
                    if (isHelp) text += "（帮）";
                    tvOwner.setText(text);
                }
                break;
            case 2: // 被邀请者
                if (currentUserRole == 2) {
                    // 当前用户是被邀请者，显示自己的昵称
                    String text = (nicknamesCached && currentUserNickname != null) ? currentUserNickname : "自己";
                    if (isHelp) text += "（帮）";
                    tvOwner.setText(text);
                } else {
                    // 当前用户是邀请者，显示对方(被邀请者)的昵称
                    String text = (nicknamesCached && partnerNickname != null) ? partnerNickname : "对方";
                    if (isHelp) text += "（帮）";
                    tvOwner.setText(text);
                }
                break;
            case 3: // 共同开支
                String commonText = "共同";
                if (isHelp) commonText += "（帮）";
                tvOwner.setText(commonText);
                break;
            default:
                String unknownText = "未知";
                if (isHelp) unknownText += "（帮）";
                tvOwner.setText(unknownText);
                break;
        }
    }
    
    // 预加载昵称缓存，避免每次显示账单时都查询数据库
    private void preloadNicknames() {
        if (currentUserId == -1) {
            nicknamesCached = true;
            notifyDataSetChanged();
            return;
        }
        
        // 获取当前用户昵称
        MySQLDatabaseHelper dbHelper = new MySQLDatabaseHelper();
        dbHelper.getUserNicknameById(currentUserId, new MySQLDatabaseHelper.UserNicknameCallback() {
            @Override
            public void onSuccess(String nickname) {
                currentUserNickname = (nickname != null && !nickname.trim().isEmpty()) ? nickname : "自己";
                
                // 如果有情侣关系，继续获取对方昵称
                if (currentRelationshipId != null) {
                    loadPartnerNickname();
                } else {
                    nicknamesCached = true;
                    notifyDataSetChanged();
                }
            }
            
            @Override
            public void onError(String error) {
                currentUserNickname = "自己";
                
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
        CoupleRelationshipHelper coupleHelper = new CoupleRelationshipHelper();
        coupleHelper.getCoupleInfo(currentUserId, new CoupleRelationshipHelper.CoupleInfoCallback() {
            @Override
            public void onCoupleFound(int coupleId, String coupleName, String coupleNickname) {
                partnerNickname = (coupleNickname != null && !coupleNickname.trim().isEmpty()) ? coupleNickname : coupleName;
                if (partnerNickname == null) partnerNickname = "对方";
                
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
        Object item = items.get(position);
        if (item instanceof Map) {
            return TYPE_DATE_HEADER;
        } else if (item instanceof BillBean) {
            return TYPE_BILL_ITEM;
        }
        return TYPE_BILL_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_DATE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_date_header, parent, false);
            return new DateHeaderViewHolder(view);
        }
        return null;
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
            
            // 设置日期
            dateHolder.tvDate.setText(date);
            
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