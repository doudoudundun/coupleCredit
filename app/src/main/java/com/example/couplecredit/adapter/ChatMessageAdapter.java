package com.example.couplecredit.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.widget.LikeButton;

import java.util.List;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.utils.UserInfoManager;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.utils.NicknameCache;

/**
 * 聊天消息RecyclerView适配器
 * 负责将消息数据绑定到视图上，支持左右两种布局样式
 * 
 * 功能特性：
 * - 支持发送者和接收者两种不同的布局样式
 * - 集成点赞功能和状态管理
 * - 支持长按显示弹出菜单
 * - 提供回调接口与外部组件通信
 */
public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {
    
    private static final int VIEW_TYPE_LEFT = 0;
    private static final int VIEW_TYPE_RIGHT = 1;
    private static final int VIEW_TYPE_AI_EXTRACTION = 2;

    private static final String TYPE_BILL = "bill";
    private static final String TYPE_INVENTORY = "inventory";
    private static final String TYPE_TODO = "todo";
    
    // 数据和上下文
    private List<ChatMessage> messages;  // 消息列表
    private Context context;             // 上下文对象
    
    // 回调接口
    private OnMessageInteractionListener listener;
    private OnAiExtractionListener aiExtractionListener;
    
    /**
     * 消息交互监听器接口
     * 定义适配器与外部组件的通信方式
     */
    public interface OnMessageInteractionListener {
        // 点赞功能已移除
    // void onLikeStatusChanged(ChatMessage message, int position, boolean isLiked);
        
        /**
         * 长按消息回调
         * @param anchorView 锚点视图
         * @param message 消息对象
         * @param position 消息位置
         */
        void onMessageLongClick(View anchorView, ChatMessage message, int position);
        
        /**
         * 头像点击回调
         * @param message 消息对象
         * @param position 消息位置
         */
        void onAvatarClick(ChatMessage message, int position);
    }
    
    /**
     * 构造函数
     * @param context 上下文对象
     * @param messages 消息列表
     */
    public ChatMessageAdapter(Context context, List<ChatMessage> messages) {
        this.context = context;
        this.messages = messages;
    }
    
    /**
     * 设置消息交互监听器
     * @param listener 监听器实例
     */
    public void setOnMessageInteractionListener(OnMessageInteractionListener listener) {
        this.listener = listener;
    }

    public interface OnAiExtractionListener {
        void onConfirm(int extractionId);
        void onEdit(int extractionId, String type, java.util.Map<String, Object> data);
        void onDismiss(int extractionId);
    }

    public void setOnAiExtractionListener(OnAiExtractionListener listener) {
        this.aiExtractionListener = listener;
    }
    
    /**
     * 获取指定位置消息的视图类型
     * @param position 消息位置
     * @return 视图类型（左侧或右侧）
     */
    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        if (message.isAiExtraction()) return VIEW_TYPE_AI_EXTRACTION;
        return message.isSentByMe() ? VIEW_TYPE_RIGHT : VIEW_TYPE_LEFT;
    }
    
    /**
     * 创建ViewHolder
     * 根据视图类型选择对应的布局文件
     */
    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        if (viewType == VIEW_TYPE_AI_EXTRACTION) {
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_ai_extraction, parent, false);
        } else if (viewType == VIEW_TYPE_RIGHT) {
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message_right, parent, false);
        } else {
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message, parent, false);
        }
        return new MessageViewHolder(view);
    }
    
    /**
     * 绑定数据到ViewHolder
     * @param holder ViewHolder实例
     * @param position 消息位置
     */
    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        holder.bind(message, position);
    }
    
    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        }
    }
    
    /**
     * 获取消息总数
     * @return 消息数量
     */
    @Override
    public int getItemCount() {
        return messages != null ? messages.size() : 0;
    }
    
    /**
     * 更新消息列表
     * @param newMessages 新的消息列表
     */
    public void updateMessages(List<ChatMessage> newMessages) {
        if (messages == null || messages.isEmpty()) {
            this.messages = newMessages;
            notifyDataSetChanged();
            return;
        }
        List<ChatMessage> oldMessages = this.messages;
        this.messages = newMessages;
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldMessages.size(); }
            @Override public int getNewListSize() { return newMessages.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                ChatMessage o = oldMessages.get(oldPos), n = newMessages.get(newPos);
                if (o == null || n == null) return o == n;
                if (o.isAiExtraction() && n.isAiExtraction()) return o.getExtractionId() == n.getExtractionId();
                return o.getContent() != null && o.getContent().equals(n.getContent())
                    && o.getTimestamp() == n.getTimestamp()
                    && o.getUserId() == n.getUserId();
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                ChatMessage o = oldMessages.get(oldPos), n = newMessages.get(newPos);
                if (o == null || n == null) return o == n;
                if (o.isAiExtraction() || n.isAiExtraction()) return o.isExtractionConfirmed() == n.isExtractionConfirmed();
                return o.equals(n);
            }
        }).dispatchUpdatesTo(this);
    }

    public ChatMessage getMessageAt(int position) {
        if (messages != null && position >= 0 && position < messages.size()) {
            return messages.get(position);
        }
        return null;
    }
    
    /**
     * 添加单条消息
     * @param message 要添加的消息
     */
    public void addMessage(ChatMessage message) {
        if (messages != null) {
            messages.add(message);
            notifyItemInserted(messages.size() - 1);
        }
    }
    
    /**
     * 删除指定位置的消息
     * @param position 要删除的消息位置
     */
    public void removeMessage(int position) {
        if (messages != null && position >= 0 && position < messages.size()) {
            messages.remove(position);
            notifyItemRemoved(position);
        }
    }
    
    /**
     * 消息ViewHolder类
     * 负责单个消息项的视图管理和事件处理
     */
    public class MessageViewHolder extends RecyclerView.ViewHolder {

        private TextView tvUsername, tvMessageContent, tvTimestamp;
        private ImageView ivAvatar;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);

            tvUsername = itemView.findViewById(R.id.tv_username);
            tvMessageContent = itemView.findViewById(R.id.tv_message_content);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
        }
        
        public void bind(ChatMessage message, int position) {
            if (message.isAiExtraction()) {
                bindAiExtraction(message);
                return;
            }
            // 点赞功能已移除 - 不再初始化LikeButton
            // 首先重置LikeButton状态，防止ViewHolder复用时的状态混乱
            // if (likeButton != null) {
            //     likeButton.setOnLikeClickListener(null);
            //     // 强制重置状态和停止所有动画
            //     likeButton.forceReset();
            // }
            
            // 设置基本信息
            loadAndDisplayNickname(message);
            tvMessageContent.setText(message.getContent());
            tvTimestamp.setText(message.getTimestamp());
            
            // 使用AvatarCacheManager加载头像（支持服务器同步）
            if (message.getUserId() > 0) {
                // 使用AvatarCacheManager加载头像，优先从服务器获取最新头像
                AvatarCacheManager.getInstance(context).loadAvatar(
                    context, ivAvatar, message.getUserId(), message.getAvatarUri()
                );
            } else if (message.hasCustomAvatar()) {
                // 如果没有用户ID但有自定义头像URI，使用Glide加载
                Glide.with(context)
                    .load(message.getAvatarUri())
                    .transform(new CircleCrop())
                    .placeholder(message.getAvatarResId())
                    .error(message.getAvatarResId())
                    .into(ivAvatar);
            } else {
                // 使用默认头像
                ivAvatar.setImageResource(message.getAvatarResId());
            }
            
            // 设置头像点击事件
            ivAvatar.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAvatarClick(message, position);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onMessageLongClick(v, message, position);
                }
                return true;
            });
        }

        private void bindAiExtraction(ChatMessage message) {
            View typeIndicator = itemView.findViewById(R.id.view_type_indicator);
            TextView tvTypeLabel = itemView.findViewById(R.id.tv_type_label);
            TextView tvSummary = itemView.findViewById(R.id.tv_extraction_summary);
            View btnConfirm = itemView.findViewById(R.id.btn_confirm);
            View btnEdit = itemView.findViewById(R.id.btn_edit);
            View btnDismiss = itemView.findViewById(R.id.btn_dismiss);
            TextView tvConfirmed = itemView.findViewById(R.id.tv_confirmed_badge);

            String type = message.getExtractionType();
            int color;
            String label;
            if (TYPE_BILL.equals(type)) {
                color = 0xFF3B82F6;
                label = "AI 识别到一笔账单";
            } else if (TYPE_INVENTORY.equals(type)) {
                color = 0xFF10B981;
                label = "AI 识别到物资记录";
            } else {
                color = 0xFFF59E0B;
                label = "AI 识别到一条待办";
            }

            boolean confirmed = message.isExtractionConfirmed();
            if (confirmed) {
                label += " · 已记录";
                color = 0xFF9CA3AF;
            }

            if (typeIndicator != null) typeIndicator.setBackgroundColor(color);
            if (tvTypeLabel != null) tvTypeLabel.setText(label);
            if (tvSummary != null) tvSummary.setText(message.getExtractionSummary());

            int buttonVisibility = confirmed ? View.GONE : View.VISIBLE;
            if (btnConfirm != null) btnConfirm.setVisibility(buttonVisibility);
            if (btnEdit != null) btnEdit.setVisibility(buttonVisibility);
            if (btnDismiss != null) btnDismiss.setVisibility(buttonVisibility);
            if (tvConfirmed != null) tvConfirmed.setVisibility(confirmed ? View.VISIBLE : View.GONE);

            if (!confirmed) {
                int extractionId = message.getExtractionId();
                if (btnConfirm != null) btnConfirm.setOnClickListener(v -> {
                    if (aiExtractionListener != null) aiExtractionListener.onConfirm(extractionId);
                });
                if (btnDismiss != null) btnDismiss.setOnClickListener(v -> {
                    if (aiExtractionListener != null) aiExtractionListener.onDismiss(extractionId);
                });
                if (btnEdit != null) btnEdit.setOnClickListener(v -> {
                    if (aiExtractionListener != null) aiExtractionListener.onEdit(extractionId, message.getExtractionType(), message.getExtractionData());
                });
            }
        }

        /**
         * 加载并显示用户昵称
         * @param message 聊天消息对象
         */
        private void loadAndDisplayNickname(ChatMessage message) {
            String username = message.getUsername();
            
            // 如果有用户ID，优先使用用户ID查询昵称
            if (message.getUserId() > 0) {
                // 先尝试从缓存获取昵称
                String cachedNickname = NicknameCache.getCachedNickname(context, username);
                if (cachedNickname != null) {
                    tvUsername.setText(cachedNickname);
                    return;
                }
                
                // 缓存中没有，从API查询
                AuthApiClient.getUserProfile(context, message.getUserId(), new AuthApiClient.ProfileCallback() {
                    @Override
                    public void onSuccess(AuthApiModels.UserProfileData profile) {
                        if (context instanceof android.app.Activity) {
                            ((android.app.Activity) context).runOnUiThread(() -> {
                                String nickname = profile.nickname;
                                if (nickname != null && !nickname.trim().isEmpty()) {
                                    tvUsername.setText(nickname);
                                    // 缓存昵称
                                    NicknameCache.cacheNickname(context, username, nickname);
                                } else {
                                    tvUsername.setText(username);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String e) {
                        if (context instanceof android.app.Activity) {
                            ((android.app.Activity) context).runOnUiThread(() -> {
                                // 查询失败，显示用户名
                                tvUsername.setText(username);
                            });
                        }
                    }
                });
            } else {
                // 没有用户ID，直接显示用户名
                tvUsername.setText(username);
            }
        }
    }


    
    /**
     * 更新指定用户的头像
     * @param userId 用户ID
     * @param avatarUri 新头像URI
     */
    public void updateUserAvatar(int userId, String avatarUri) {
        if (messages == null) return;
        
        boolean hasUpdates = false;
        int currentUserId = UserInfoManager.getCurrentUserId(context);
        String currentUsername = UserInfoManager.getCurrentUsername(context);
        
        for (ChatMessage message : messages) {
            // 如果是当前用户的消息，更新头像
            if (userId == currentUserId && 
                (message.isSentByMe() || message.getUsername().equals(currentUsername))) {
                message.setAvatarUri(avatarUri);
                hasUpdates = true;
            }
        }
        
        if (hasUpdates) {
            notifyItemRangeChanged(0, messages.size());
        }
    }
}