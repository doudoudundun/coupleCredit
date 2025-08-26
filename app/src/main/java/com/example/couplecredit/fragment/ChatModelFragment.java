package com.example.couplecredit.fragment;



import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// 添加Room数据库相关导入
import com.example.couplecredit.R;
import com.example.couplecredit.database.ChatDatabase;
import com.example.couplecredit.database.ChatMessageDao;
import com.example.couplecredit.database.ChatMessageEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.OnBackPressedCallback;

/**
 * 聊天模式Fragment
 * 实现情侣记账APP中的群聊功能，支持消息收发、互动和记账相关功能
 * 集成Room数据库实现消息持久化存储
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

    // 数据库相关
    private ChatDatabase chatDatabase;       // Room数据库实例
    private ChatMessageDao chatMessageDao;   // 数据访问对象
    private ExecutorService databaseExecutor; // 数据库操作线程池

    // 添加搜索状态跟踪
    private boolean isSearchMode = false;    // 是否处于搜索模式
    private OnBackPressedCallback backPressedCallback;  // 返回键回调

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化数据库
        initDatabase();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_model, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化各个组件
        initViews(view);
        initData();
        setupRecyclerView();
        setupListeners();
        setupBackPressedHandler();

        // 加载聊天背景设置
        loadChatBackground(view);

        // 从数据库加载历史消息
        loadMessagesFromDatabase();
    }

    /**
     * 初始化Room数据库
     */
    private void initDatabase() {
        try {
            // 获取数据库实例
            chatDatabase = ChatDatabase.getInstance(requireContext());
            chatMessageDao = chatDatabase.chatMessageDao();

            // 创建数据库操作线程池
            databaseExecutor = Executors.newFixedThreadPool(2);

        } catch (Exception e) {
            // 数据库初始化失败处理
            if (getContext() != null) {
                Toast.makeText(getContext(), "数据库初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 从数据库加载历史聊天消息
     */
    private void loadMessagesFromDatabase() {
        if (chatMessageDao == null) {
            // 如果数据库未初始化，加载默认消息
            loadDefaultMessages();
            return;
        }

        // 在后台线程执行数据库查询
        databaseExecutor.execute(() -> {
            try {
                // 从数据库获取所有消息
                List<ChatMessageEntity> entities = chatMessageDao.getAllMessages();

                // 在主线程更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        messageList.clear();
                        originalMessageList.clear();

                        if (entities.isEmpty()) {
                            // 如果数据库为空，加载默认消息并保存到数据库
                            loadDefaultMessages();
                            saveDefaultMessagesToDatabase();
                        } else {
                            // 将数据库实体转换为ChatMessage对象
                            for (ChatMessageEntity entity : entities) {
                                ChatMessage message = new ChatMessage(
                                        entity.getUsername(),
                                        entity.getContent(),
                                        entity.getTimestamp(),
                                        entity.getAvatarResId(),
                                        entity.isSentByMe()
                                );
                                message.setLiked(entity.isLiked());
                                messageList.add(message);
                                originalMessageList.add(message);
                            }

                            // 通知适配器数据已更新
                            messageAdapter.notifyDataSetChanged();

                            // 滚动到最新消息
                            if (!messageList.isEmpty()) {
                                rvChatMessages.scrollToPosition(messageList.size() - 1);
                            }

                            Toast.makeText(getContext(), "已加载 " + entities.size() + " 条历史消息", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

            } catch (Exception e) {
                // 数据库查询失败处理
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "加载历史消息失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        loadDefaultMessages(); // 加载默认消息作为备选
                    });
                }
            }
        });
    }

    /**
     * 加载默认示例消息
     */
    private void loadDefaultMessages() {
        messageList.clear();
        originalMessageList.clear();

        // 添加示例聊天消息
        messageList.add(new ChatMessage("男友", "小迷糊，要记得记账呀", "09:30", R.drawable.ic_profile, false));
        messageList.add(new ChatMessage("我", "好的，我刚记了今天的早餐费用", "09:32", R.drawable.ic_profile, true));
        messageList.add(new ChatMessage("男友", "很棒！坚持记账能帮我们更好地管理财务", "09:35", R.drawable.ic_profile, false));
        messageList.add(new ChatMessage("我", "今天花了50元买午餐，有点贵", "12:30", R.drawable.ic_profile, true));
        messageList.add(new ChatMessage("男友", "偶尔吃好一点没关系，重要的是要有记录", "12:35", R.drawable.ic_profile, false));

        // 备份原始消息列表（用于搜索功能）
        originalMessageList.addAll(messageList);

        // 通知适配器数据已更新
        if (messageAdapter != null) {
            messageAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 将默认消息保存到数据库
     */
    private void saveDefaultMessagesToDatabase() {
        if (chatMessageDao == null) return;

        databaseExecutor.execute(() -> {
            try {
                // 将默认消息保存到数据库
                for (ChatMessage message : messageList) {
                    ChatMessageEntity entity = new ChatMessageEntity(
                            message.getUsername(),
                            message.getContent(),
                            message.getTimestamp(),
                            message.getAvatarResId(),
                            message.isSentByMe()
                    );
                    entity.setLiked(message.isLiked());
                    chatMessageDao.insertMessage(entity);
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "保存默认消息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }

    /**
     * 初始化UI组件
     *
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
    }

    /**
     * 设置RecyclerView
     */
    private void setupRecyclerView() {
        messageAdapter = new ChatMessageAdapter(messageList);
        rvChatMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        rvChatMessages.setAdapter(messageAdapter);
    }

    /**
     * 设置各种事件监听器
     */
    private void setupListeners() {
        // 发送消息按钮点击事件
        btnSend.setOnClickListener(v -> sendMessage());

        // 搜索按钮点击事件
        btnSearch.setOnClickListener(v -> {
            String searchText = etSearch.getText().toString().trim();
            if (TextUtils.isEmpty(searchText)) {
                // 如果搜索框为空，恢复显示所有消息
                restoreAllMessages();
            } else {
                // 执行数据库搜索
                searchMessagesInDatabase(searchText);
            }
        });

        // 搜索功能 - 搜索聊天记录中的关键词
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String searchText = etSearch.getText().toString().trim();
            if (TextUtils.isEmpty(searchText)) {
                restoreAllMessages();
            } else {
                searchMessagesInDatabase(searchText);
            }
            return true;
        });
    }

    /**
     * 发送消息功能（集成数据库保存）
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

            // 保存到数据库
            saveMessageToDatabase(newMessage);

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
     * 将消息保存到数据库
     *
     * @param message 要保存的消息
     */
    private void saveMessageToDatabase(ChatMessage message) {
        if (chatMessageDao == null) {
            Toast.makeText(getContext(), "数据库未初始化，消息未保存", Toast.LENGTH_SHORT).show();
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                // 创建数据库实体
                ChatMessageEntity entity = new ChatMessageEntity(
                        message.getUsername(),
                        message.getContent(),
                        message.getTimestamp(),
                        message.getAvatarResId(),
                        message.isSentByMe()
                );
                entity.setLiked(message.isLiked());

                // 插入到数据库
                long messageId = chatMessageDao.insertMessage(entity);

                // 在主线程显示保存结果
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (messageId > 0) {
                            // 保存成功，可以在这里添加额外的成功处理逻辑
                        } else {
                            Toast.makeText(getContext(), "消息保存失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

            } catch (Exception e) {
                // 保存失败处理
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "保存消息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }

    /**
     * 在数据库中搜索聊天消息
     *
     * @param searchText 搜索关键词
     */
    private void searchMessagesInDatabase(String searchText) {
        if (chatMessageDao == null) {
            // 如果数据库未初始化，使用内存搜索作为备选
            searchMessages(searchText);
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                // 在数据库中搜索
                List<ChatMessageEntity> entities = chatMessageDao.searchMessages(searchText);

                // 在主线程更新UI
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        List<ChatMessage> filteredList = new ArrayList<>();

                        // 将数据库实体转换为ChatMessage对象
                        for (ChatMessageEntity entity : entities) {
                            ChatMessage message = new ChatMessage(
                                    entity.getUsername(),
                                    entity.getContent(),
                                    entity.getTimestamp(),
                                    entity.getAvatarResId(),
                                    entity.isSentByMe()
                            );
                            message.setLiked(entity.isLiked());
                            filteredList.add(message);
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
                    });
                }

            } catch (Exception e) {
                // 数据库搜索失败，使用内存搜索作为备选
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "数据库搜索失败，使用内存搜索: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        searchMessages(searchText);
                    });
                }
            }
        });
    }

    /**
     * 内存中搜索聊天消息（备用方法）
     *
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
    private void restoreAllMessages() {
        // 重新从数据库加载所有消息
        loadMessagesFromDatabase();

        etSearch.setText("");  // 清空搜索框

        // 退出搜索模式
        isSearchMode = false;
        backPressedCallback.setEnabled(false);  // 禁用返回键拦截

        Toast.makeText(getContext(), "显示所有消息", Toast.LENGTH_SHORT).show();
    }

    /**
     * 更新消息点赞状态到数据库
     *
     * @param message  要更新的消息
     * @param position 消息在列表中的位置
     */
    private void updateMessageLikeStatus(ChatMessage message, int position) {
        if (chatMessageDao == null) {
            Toast.makeText(getContext(), "数据库未初始化，点赞状态未保存", Toast.LENGTH_SHORT).show();
            return;
        }

        databaseExecutor.execute(() -> {
            try {
                // 根据消息内容和时间戳查找数据库中的对应记录
                List<ChatMessageEntity> allEntities = chatMessageDao.getAllMessages();

                for (ChatMessageEntity entity : allEntities) {
                    if (entity.getContent().equals(message.getContent()) &&
                            entity.getTimestamp().equals(message.getTimestamp()) &&
                            entity.getUsername().equals(message.getUsername())) {

                        // 更新点赞状态
                        entity.setLiked(message.isLiked());
                        chatMessageDao.updateMessage(entity);
                        break;
                    }
                }

            } catch (Exception e) {
                // 更新失败处理
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "更新点赞状态失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
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
         *
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
         */
        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            ChatMessage message = messages.get(position);
            holder.bind(message, position);
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
            private ImageView ivAvatar, ivLike;

            /**
             * 构造函数，初始化UI组件
             */
            public MessageViewHolder(@NonNull View itemView) {
                super(itemView);
                tvUsername = itemView.findViewById(R.id.tv_username);
                tvMessageContent = itemView.findViewById(R.id.tv_message_content);
                tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
                ivAvatar = itemView.findViewById(R.id.iv_avatar);
                ivLike = itemView.findViewById(R.id.iv_like);
            }

            /**
             * 绑定消息数据到视图
             *
             * @param message  消息对象
             * @param position 消息位置
             */
            public void bind(ChatMessage message, int position) {
                // 设置基本信息
                tvUsername.setText(message.getUsername());
                tvMessageContent.setText(message.getContent());
                tvTimestamp.setText(message.getTimestamp());
                ivAvatar.setImageResource(message.getAvatarResId());

                // 设置点赞状态
                updateLikeButton(message.isLiked());

                // 设置点赞按钮点击事件（集成数据库更新）
                ivLike.setOnClickListener(v -> {
                    // 切换点赞状态
                    boolean newLikeStatus = !message.isLiked();
                    message.setLiked(newLikeStatus);

                    // 更新UI
                    updateLikeButton(newLikeStatus);

                    // 更新数据库中的点赞状态
                    updateMessageLikeStatus(message, position);

                    // 显示点赞状态提示
                    String statusText = newLikeStatus ? "已点赞" : "取消点赞";
                    Toast.makeText(getContext(), statusText, Toast.LENGTH_SHORT).show();
                });

                // 在bind方法的最后添加
                // 添加长按监听器，显示弹出菜单
                itemView.setOnLongClickListener(v -> {
                    showMessagePopupMenu(v, message, position);
                    return true; // 返回true表示消费了长按事件
                });
            }

            /**
             * 更新点赞按钮的显示状态
             *
             * @param isLiked 是否已点赞
             */
            private void updateLikeButton(boolean isLiked) {
                if (isLiked) {
                    // 使用现有的ic_favorite图标，通过颜色区分状态
                    ivLike.setImageResource(R.drawable.ic_favorite);
                    ivLike.setColorFilter(getResources().getColor(R.color.like_color));
                } else {
                    // 同样使用ic_favorite图标，但使用不同颜色
                    ivLike.setImageResource(R.drawable.ic_favorite);
                    ivLike.setColorFilter(getResources().getColor(R.color.unlike_color));
                }
            }
        }
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
         *
         * @param username    用户名
         * @param content     消息内容
         * @param timestamp   时间戳
         * @param avatarResId 头像资源ID
         * @param isSentByMe  是否是本人发送
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
        public String getUsername() {
            return username;
        }

        public String getContent() {
            return content;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public int getAvatarResId() {
            return avatarResId;
        }

        public boolean isLiked() {
            return isLiked;
        }

        public boolean isSentByMe() {
            return isSentByMe;
        }

        public void setLiked(boolean liked) {
            isLiked = liked;
        }

        public void setSentByMe(boolean sentByMe) {
            isSentByMe = sentByMe;
        }
    }

    /**
     * 设置返回键处理
     */
    private void setupBackPressedHandler() {
        // 创建返回键处理回调
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSearchMode) {
                    // 如果处于搜索模式，退出搜索模式
                    restoreAllMessages();
                } else {
                    // 否则执行默认返回操作
                    setEnabled(false);
                    requireActivity().onBackPressed();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理资源
        if (backPressedCallback != null) {
            backPressedCallback.remove();
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }

    /**
     * 加载聊天背景设置
     * 从SharedPreferences读取用户设置的背景并应用到聊天界面
     */
    private void loadChatBackground(View rootView) {
        SharedPreferences prefs = getActivity().getSharedPreferences("chat_settings", Context.MODE_PRIVATE);

        // 检查是否有自定义背景URI
        String backgroundUri = prefs.getString("chat_background_uri", null);
        if (backgroundUri != null) {
            try {
                // 应用自定义背景图片
                Uri uri = Uri.parse(backgroundUri);
                Drawable drawable = Drawable.createFromStream(
                        getActivity().getContentResolver().openInputStream(uri), null);
                if (drawable != null) {
                    rootView.setBackground(drawable);
                    return;
                }
            } catch (Exception e) {
                // 如果加载自定义背景失败，继续尝试预设背景
                e.printStackTrace();
            }
        }

        // 检查是否有预设背景资源ID
        int backgroundResId = prefs.getInt("chat_background", -1);
        if (backgroundResId != -1) {
            try {
                // 应用预设背景图片
                Drawable drawable = getResources().getDrawable(backgroundResId, null);
                rootView.setBackground(drawable);
            } catch (Exception e) {
                // 如果加载预设背景失败，使用默认背景
                e.printStackTrace();
                rootView.setBackgroundColor(0xFFF0F0F0); // 默认浅灰色背景
            }
        } else {
            // 没有设置背景时使用默认背景
            rootView.setBackgroundColor(0xFFF0F0F0); // 默认浅灰色背景
        }
    }

    /**
     * 显示消息操作弹出菜单
     */
    private void showMessagePopupMenu(View anchorView, ChatMessage message, int position) {
        // 创建PopupWindow
        View popupView = LayoutInflater.from(getContext()).inflate(R.layout.popup_message_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        // 设置背景和动画
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setElevation(8);

        // 获取菜单项
        LinearLayout tvCopy = popupView.findViewById(R.id.tv_copy);
        LinearLayout tvDelete = popupView.findViewById(R.id.tv_delete);
        LinearLayout tvBilling = popupView.findViewById(R.id.tv_billing);

        // 设置点击事件
        tvCopy.setOnClickListener(v -> {
            copyMessageToClipboard(message);
            popupWindow.dismiss();
        });

        tvDelete.setOnClickListener(v -> {
            deleteMessage(message, position);
            popupWindow.dismiss();
        });

        tvBilling.setOnClickListener(v -> {
            // 记账功能暂不实现
            Toast.makeText(getContext(), "记账功能开发中...", Toast.LENGTH_SHORT).show();
            popupWindow.dismiss();
        });

        // 测量弹出窗口的尺寸
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = popupView.getMeasuredWidth();
        int popupHeight = popupView.getMeasuredHeight();

        // 获取屏幕宽度和消息框在屏幕中的位置
        int[] anchorLocation = new int[2];
        anchorView.getLocationOnScreen(anchorLocation);
        int anchorScreenX = anchorLocation[0];
        int anchorWidth = anchorView.getWidth();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        // 计算智能的水平偏移量
        int xOffset;

        // 首先尝试居中显示
        int centerOffset = (anchorWidth - popupWidth) / 2;
        int popupLeftEdge = anchorScreenX + centerOffset;
        int popupRightEdge = popupLeftEdge + popupWidth;

        // 检查是否会溢出屏幕
        if (popupLeftEdge < 0) {
            // 左侧溢出，贴近左边界
            xOffset = -anchorScreenX;
        } else if (popupRightEdge > screenWidth) {
            // 右侧溢出，贴近右边界
            xOffset = screenWidth - anchorScreenX - popupWidth;
        } else {
            // 可以居中显示
            xOffset = centerOffset;
        }

        // 如果消息很短，根据发送者调整为靠内侧显示
        if (anchorWidth < popupWidth * 0.6) { // 消息宽度小于弹出菜单宽度的60%时
            if (message.isSentByMe()) {
                // 自己的消息，靠左内侧显示（向消息中心靠拢）
                int innerOffset = anchorWidth - popupWidth;
                // 确保不会溢出屏幕左侧
                if (anchorScreenX + innerOffset >= 0) {
                    xOffset = innerOffset;
                }
            } else {
                // 对方的消息，靠右内侧显示（向消息中心靠拢）
                int innerOffset = 0;
                // 确保不会溢出屏幕右侧
                if (anchorScreenX + popupWidth <= screenWidth) {
                    xOffset = innerOffset;
                }
            }
        }

        // 计算垂直偏移量，让弹出菜单显示在消息上方且不覆盖
        // showAsDropDown是相对于anchorView底部的，所以要显示在上方需要：
        // -(anchorView高度 + 弹出菜单高度)
        int yOffset = -(anchorView.getHeight() + popupHeight - 88);

        // 显示弹出窗口
        popupWindow.showAsDropDown(anchorView, xOffset, yOffset);
    }

    /**
     * 删除消息
     */
    private void deleteMessage(ChatMessage message, int position) {
        // 从UI列表中删除
        messageList.remove(position);
        messageAdapter.notifyItemRemoved(position);

        // 从数据库中删除
        deleteMessageFromDatabase(message);

        Toast.makeText(getContext(), "消息已删除", Toast.LENGTH_SHORT).show();
    }

    /**
     * 从数据库删除消息
     */
    private void deleteMessageFromDatabase(ChatMessage message) {
        if (databaseExecutor != null && chatMessageDao != null) {
            databaseExecutor.execute(() -> {
                try {
                    // 根据消息内容和时间戳删除
                    chatMessageDao.deleteByContentAndTimestamp(message.getContent(), message.getTimestamp());
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }
    }

    /**
     * 复制消息到剪贴板
     */
    private void copyMessageToClipboard(ChatMessage message) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", message.getContent());
        clipboard.setPrimaryClip(clip);

        Toast.makeText(getContext(), "消息已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

}

