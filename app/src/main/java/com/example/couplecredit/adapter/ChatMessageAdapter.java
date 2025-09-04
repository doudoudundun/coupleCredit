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
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.function.LikeButton;

import java.util.List;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.utils.AvatarCacheManager;
import com.example.couplecredit.function.MySQLDatabaseHelper;
import com.example.couplecredit.function.NicknameCache;

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
    
    // 视图类型常量
    private static final int VIEW_TYPE_LEFT = 0;  // 对方消息（左侧）
    private static final int VIEW_TYPE_RIGHT = 1; // 本人消息（右侧）
    
    // 数据和上下文
    private List<ChatMessage> messages;  // 消息列表
    private Context context;             // 上下文对象
    
    // 回调接口
    private OnMessageInteractionListener listener;
    
    /**
     * 消息交互监听器接口
     * 定义适配器与外部组件的通信方式
     */
    public interface OnMessageInteractionListener {
        /**
         * 点赞状态改变回调
         * @param message 消息对象
         * @param position 消息位置
         * @param isLiked 新的点赞状态
         */
        void onLikeStatusChanged(ChatMessage message, int position, boolean isLiked);
        
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
    
    /**
     * 获取指定位置消息的视图类型
     * @param position 消息位置
     * @return 视图类型（左侧或右侧）
     */
    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
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
        if (viewType == VIEW_TYPE_RIGHT) {
            // 本人消息使用右侧布局
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message_right, parent, false);
        } else {
            // 对方消息使用左侧布局
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
            // 没有部分更新，执行完整绑定
            super.onBindViewHolder(holder, position, payloads);
        } else {
            // 处理部分更新
            ChatMessage message = messages.get(position);
            for (Object payload : payloads) {
                if ("like_status".equals(payload)) {
                    // 只更新点赞状态，不重新绑定整个ViewHolder
                    holder.updateLikeButton(message.isLiked());
                }
            }
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
        this.messages = newMessages;
        // 使用notifyDataSetChanged()会导致所有ViewHolder重新绑定
        // 这可能引起LikeButton状态混乱，但为了数据一致性暂时保留
        // 后续可以考虑使用DiffUtil来优化
        notifyDataSetChanged();
    }
    
    /**
     * 更新特定位置消息的点赞状态
     * @param position 消息位置
     * @param isLiked 新的点赞状态
     */
    public void updateLikeStatus(int position, boolean isLiked) {
        if (messages != null && position >= 0 && position < messages.size()) {
            messages.get(position).setLiked(isLiked);
            notifyItemChanged(position, "like_status");
        }
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
        private LikeButton likeButton;
        
        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            
            // 初始化UI组件
            tvUsername = itemView.findViewById(R.id.tv_username);
            tvMessageContent = itemView.findViewById(R.id.tv_message_content);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            ivAvatar = itemView.findViewById(R.id.iv_avatar);
            likeButton = itemView.findViewById(R.id.likeButton);
        }
        
        /**
         * 绑定消息数据到视图
         * @param message 消息对象
         * @param position 消息位置
         */
        public void bind(ChatMessage message, int position) {
            // 首先重置LikeButton状态，防止ViewHolder复用时的状态混乱
            if (likeButton != null) {
                likeButton.setOnLikeClickListener(null);
                // 强制重置状态和停止所有动画
                likeButton.forceReset();
            }
            
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
            
            // 设置点赞状态
            updateLikeButton(message.isLiked());
            
            // 清除之前的监听器并设置新的点赞按钮点击事件
            if (likeButton != null) {
                // 先清除之前的监听器
                likeButton.setOnLikeClickListener(null);
                // 设置新的监听器
                likeButton.setOnLikeClickListener(isLiked -> {
                    // 切换点赞状态
                    message.setLiked(isLiked);
                    
                    // 通知监听器
                    if (listener != null) {
                        listener.onLikeStatusChanged(message, position, isLiked);
                    }
                });
            }
            
            // 设置长按监听器，显示弹出菜单
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onMessageLongClick(v, message, position);
                }
                return true; // 返回true表示消费了长按事件
            });
        }
        
        /**
         * 更新点赞按钮的显示状态
         * @param isLiked 是否已点赞
         */
        public void updateLikeButton(boolean isLiked) {
            if (likeButton != null) {
                likeButton.setLiked(isLiked);
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
                
                // 缓存中没有，从数据库查询
                MySQLDatabaseHelper.getUserNicknameById(message.getUserId(), new MySQLDatabaseHelper.UserNicknameCallback() {
                    @Override
                    public void onSuccess(String nickname) {
                        if (context instanceof android.app.Activity) {
                            ((android.app.Activity) context).runOnUiThread(() -> {
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
                    public void onError(String error) {
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
            notifyDataSetChanged();
        }
    }
}