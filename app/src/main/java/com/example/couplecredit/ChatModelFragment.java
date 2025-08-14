package com.example.couplecredit;

// 添加缺少的 import 语句
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.activity.OnBackPressedCallback;

/**
 * 聊天模式Fragment
 * 实现情侣记账APP中的群聊功能，支持消息收发、互动和记账相关功能
 */
public class ChatModelFragment extends Fragment {
    
    // UI组件声明
    private RecyclerView rvChatMessages;     // 聊天消息列表
    private EditText etMessageInput;         // 消息输入框
    private EditText etSearch;               // 搜索输入框
    private Button btnSend;                  // 发送按钮
    private Button btnSearch;                // 搜索按钮
    
    // 数据相关
    private ChatMessageAdapter messageAdapter;  // 消息列表适配器
    private List<ChatMessage> messageList;      // 消息数据列表
    private List<ChatMessage> originalMessageList;  // 原始消息列表（用于搜索恢复）
    
    // 添加搜索状态跟踪
    private boolean isSearchMode = false;    // 是否处于搜索模式
    private OnBackPressedCallback backPressedCallback;  // 返回键回调
    
    /**
     * 创建Fragment视图
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_model, container, false);
    }
    
    /**
     * 视图创建完成后的初始化工作
     */
    // 删除 @Override 注解，因为这是一个私有方法，不是重写父类方法
    private void applyChatBackground() {
        // 获取聊天设置的SharedPreferences
        SharedPreferences prefs = getActivity().getSharedPreferences("chat_settings", Context.MODE_PRIVATE);
        
        // 检查是否有自定义背景URI
        String backgroundUri = prefs.getString("chat_background_uri", null);
        if (backgroundUri != null) {
            try {
                // 尝试从URI创建Drawable
                Uri uri = Uri.parse(backgroundUri);
                Drawable drawable = Drawable.createFromStream(
                    getActivity().getContentResolver().openInputStream(uri), null);
                if (drawable != null) {
                    // 应用自定义背景
                    rvChatMessages.setBackground(drawable);
                    return;
                }
            } catch (Exception e) {
                // 如果自定义背景加载失败，记录错误并继续使用预设背景
                e.printStackTrace();
            }
        }
        
        // 使用预设背景
        int backgroundResId = prefs.getInt("chat_background", -1);
        if (backgroundResId != -1) {
            // 应用预设背景资源
            rvChatMessages.setBackgroundResource(backgroundResId);
        }
        // 如果没有设置任何背景，保持默认背景
    }
    
    // 在onViewCreated方法中调用applyChatBackground()方法
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        initData();
        setupRecyclerView();
        setupListeners();
        setupBackPressedHandler();
        
        // 应用用户设置的聊天背景
        applyChatBackground();
    }
    
    /**
     * 初始化UI组件
     * @param view 根视图
     */
    private void initViews(View view) {
        rvChatMessages = view.findViewById(R.id.rv_chat_messages);
        etMessageInput = view.findViewById(R.id.et_message_input);
        etSearch = view.findViewById(R.id.et_search);
        btnSend = view.findViewById(R.id.btn_send);
        btnSearch = view.findViewById(R.id.btn_search);
    }
    
    
    /**
     * 初始化数据
     */
    private void initData() {
        messageList = new ArrayList<>();
        originalMessageList = new ArrayList<>();
        
        // 添加示例聊天消息
        messageList.add(new ChatMessage("男友", "小迷糊，要记得记账呀", "09:30", R.drawable.ic_profile, false));
        messageList.add(new ChatMessage("我", "好的，我刚记了今天的早餐费用", "09:32", R.drawable.ic_profile, true));
        messageList.add(new ChatMessage("男友", "很棒！坚持记账能帮我们更好地管理财务", "09:35", R.drawable.ic_profile, false));
        messageList.add(new ChatMessage("我", "今天花了50元买午餐，有点贵", "12:30", R.drawable.ic_profile, true));
        messageList.add(new ChatMessage("男友", "偶尔吃好一点没关系，重要的是要有记录", "12:35", R.drawable.ic_profile, false));
        
        // 备份原始消息列表（用于搜索功能）
        originalMessageList.addAll(messageList);
    }
    
    /**
     * 设置消息列表RecyclerView
     */
    private void setupRecyclerView() {
        messageAdapter = new ChatMessageAdapter(messageList);
        rvChatMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        rvChatMessages.setAdapter(messageAdapter);
        
        // 自动滚动到最新消息
        if (!messageList.isEmpty()) {
            rvChatMessages.scrollToPosition(messageList.size() - 1);
        }
    }
    
    /**
     * 设置各种事件监听器
     */
    private void setupListeners() {
        // 发送消息按钮点击事件
        btnSend.setOnClickListener(v -> sendMessage());
        
        // 搜索按钮点击事件 - 添加这个监听器
        btnSearch.setOnClickListener(v -> {
            String searchText = etSearch.getText().toString().trim();
            if (TextUtils.isEmpty(searchText)) {
                // 如果搜索框为空，恢复显示所有消息
                restoreAllMessages();
            } else {
                // 执行搜索
                searchMessages(searchText);
            }
        });
        

        // 搜索功能 - 搜索聊天记录中的关键词
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String searchText = etSearch.getText().toString().trim();
            if (TextUtils.isEmpty(searchText)) {
                restoreAllMessages();
            } else {
                searchMessages(searchText);
            }
            return true;
        });
    }
    
    /**
     * 发送消息功能
     */
    private void sendMessage() {
        String messageText = etMessageInput.getText().toString().trim();
        if (!TextUtils.isEmpty(messageText)) {
            // 创建新消息，标记为本人发送
            String currentTime = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date());
            ChatMessage newMessage = new ChatMessage("我", messageText, currentTime, R.drawable.ic_profile, true);
            
            // 添加到消息列表
            messageList.add(newMessage);
            originalMessageList.add(newMessage);
            messageAdapter.notifyItemInserted(messageList.size() - 1);
            
            // 清空输入框
            etMessageInput.setText("");
            
            // 滚动到最新消息
            rvChatMessages.scrollToPosition(messageList.size() - 1);
            
            // 显示发送成功提示
            Toast.makeText(getContext(), "消息发送成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "请输入消息内容", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 搜索聊天消息
     * @param searchText 搜索关键词
     */
    private void searchMessages(String searchText) {
        List<ChatMessage> filteredList = new ArrayList<>();
        
        // 遍历原始消息列表，查找包含关键词的消息
        for (ChatMessage message : originalMessageList) {
            if (message.getContent().toLowerCase().contains(searchText.toLowerCase()) ||
                message.getUsername().toLowerCase().contains(searchText.toLowerCase())) {
                filteredList.add(message);
            }
        }
        
        // 更新显示的消息列表
        messageList.clear();
        messageList.addAll(filteredList);
        messageAdapter.notifyDataSetChanged();
        
        // 设置搜索模式状态
        isSearchMode = true;
        backPressedCallback.setEnabled(true);  // 启用返回键拦截
        
        // 显示搜索结果提示
        if (filteredList.isEmpty()) {
            Toast.makeText(getContext(), "未找到相关消息", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "找到 " + filteredList.size() + " 条相关消息，按返回键可回到聊天窗口", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 恢复显示所有消息
     */
    /**
     * 恢复显示所有消息
     */
    private void restoreAllMessages() {
        messageList.clear();
        messageList.addAll(originalMessageList);
        messageAdapter.notifyDataSetChanged();
        etSearch.setText("");  // 清空搜索框
        
        // 退出搜索模式
        isSearchMode = false;
        backPressedCallback.setEnabled(false);  // 禁用返回键拦截
        
        Toast.makeText(getContext(), "显示所有消息", Toast.LENGTH_SHORT).show();
    }
    

    /**
     * 聊天消息数据模型类
     * 封装单条聊天消息的所有信息
     */
    public static class ChatMessage {
        private String username;    // 用户名
        private String content;     // 消息内容
        private String timestamp;   // 时间戳
        private int avatarResId;    // 头像资源ID
        private boolean isLiked;    // 是否被点赞
        private boolean isSentByMe; // 是否是本人发送的消息
        
        /**
         * 构造函数
         * @param username 用户名
         * @param content 消息内容
         * @param timestamp 时间戳
         * @param avatarResId 头像资源ID
         * @param isSentByMe 是否是本人发送
         */
        public ChatMessage(String username, String content, String timestamp, int avatarResId, boolean isSentByMe) {
            this.username = username;
            this.content = content;
            this.timestamp = timestamp;
            this.avatarResId = avatarResId;
            this.isLiked = false;  // 默认未点赞
            this.isSentByMe = isSentByMe;
        }
        
        // 保持向后兼容的构造函数
        public ChatMessage(String username, String content, String timestamp, int avatarResId) {
            this(username, content, timestamp, avatarResId, false);
        }
        
        // Getter和Setter方法
        public String getUsername() { return username; }
        public String getContent() { return content; }
        public String getTimestamp() { return timestamp; }
        public int getAvatarResId() { return avatarResId; }
        public boolean isLiked() { return isLiked; }
        public boolean isSentByMe() { return isSentByMe; }
        public void setLiked(boolean liked) { isLiked = liked; }
        public void setSentByMe(boolean sentByMe) { isSentByMe = sentByMe; }
    }
    
    /**
     * 聊天消息RecyclerView适配器
     * 负责将消息数据绑定到视图上
     */
    private class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {
        private static final int VIEW_TYPE_LEFT = 0;  // 对方消息（左侧）
        private static final int VIEW_TYPE_RIGHT = 1; // 本人消息（右侧）
        
        private List<ChatMessage> messages;  // 消息列表
        
        /**
         * 构造函数
         * @param messages 消息列表
         */
        public ChatMessageAdapter(List<ChatMessage> messages) {
            this.messages = messages;
        }
        
        @Override
        public int getItemViewType(int position) {
            ChatMessage message = messages.get(position);
            return message.isSentByMe() ? VIEW_TYPE_RIGHT : VIEW_TYPE_LEFT;
        }
        
        /**
         * 创建ViewHolder
         */
        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_RIGHT) {
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
         */
        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            ChatMessage message = messages.get(position);
            holder.bind(message);
        }
        
        /**
         * 获取消息总数
         */
        @Override
        public int getItemCount() {
            return messages.size();
        }
        
        /**
         * 消息ViewHolder类
         * 负责单个消息项的视图管理和事件处理
         */
        class MessageViewHolder extends RecyclerView.ViewHolder {
            // UI组件声明
            private TextView tvUsername, tvMessageContent, tvTimestamp;
            private ImageView ivAvatar, ivLike; // 删除 ivReply
            
            /**
             * 构造函数，初始化UI组件
             */
            public MessageViewHolder(@NonNull View itemView) {
                super(itemView);
                tvUsername = itemView.findViewById(R.id.tv_username);
                tvMessageContent = itemView.findViewById(R.id.tv_message_content);
                tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
                ivAvatar = itemView.findViewById(R.id.iv_avatar);
                // 删除这行：ivReply = itemView.findViewById(R.id.iv_reply);
                ivLike = itemView.findViewById(R.id.iv_like);
            }
            
            /**
             * 绑定消息数据到视图
             * @param message 要显示的消息对象
             */
            public void bind(ChatMessage message) {
                // 设置基本信息
                tvUsername.setText(message.getUsername());
                tvMessageContent.setText(message.getContent());
                tvTimestamp.setText(message.getTimestamp());
                ivAvatar.setImageResource(message.getAvatarResId());
                
                // 根据点赞状态设置爱心图标样式
                if (message.isLiked()) {
                    ivLike.setBackgroundResource(R.drawable.circle_bg_red);  // 已点赞：红色
                } else {
                    ivLike.setBackgroundResource(R.drawable.circle_bg_pink); // 未点赞：粉色
                }
                
                // 点赞按钮点击事件 - 切换点赞状态
                ivLike.setOnClickListener(v -> {
                    message.setLiked(!message.isLiked()); // 切换点赞状态
                    notifyItemChanged(getAdapterPosition()); // 刷新当前项
                    
                    // 显示点赞状态提示
                    String likeText = message.isLiked() ? "已点赞" : "取消点赞";
                    Toast.makeText(getContext(), likeText, Toast.LENGTH_SHORT).show();
                });
            }
        }
    }
    
    /**
     * 设置返回键处理
     */
    private void setupBackPressedHandler() {
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isSearchMode) {
                    // 如果处于搜索模式，返回键恢复显示所有消息
                    restoreAllMessages();
                }
            }
        };
        
        // 注册返回键回调
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理返回键回调
        if (backPressedCallback != null) {
            backPressedCallback.remove();
        }
    }
}
