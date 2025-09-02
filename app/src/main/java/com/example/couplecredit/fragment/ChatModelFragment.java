package com.example.couplecredit.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.couplecredit.R;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.adapter.ChatMessageAdapter;
import com.example.couplecredit.function.UserInfoManager;
import com.example.couplecredit.manager.MessagePopupManager;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.repository.ChatRepository;
import com.example.couplecredit.utils.AvatarUpdateManager;
import com.example.couplecredit.utils.BackgroundUpdateManager;
import com.example.couplecredit.viewmodel.ChatViewModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天界面Fragment - 重构版本
 * 
 * 采用MVVM架构模式，职责清晰分离：
 * - Fragment：纯UI控制器，负责视图初始化、用户交互和UI状态更新
 * - ViewModel：业务逻辑层，处理数据操作和状态管理
 * - Repository：数据访问层，封装数据库操作
 * - Adapter：视图适配器，处理列表显示逻辑
 * - Manager：功能管理器，处理特定功能（如弹出菜单）
 * 
 * 主要功能：
 * - 聊天消息显示和发送
 * - 消息搜索功能
 * - 消息点赞功能
 * - 消息长按菜单（复制、删除、记账）
 * - 聊天背景设置
 * - 返回键处理
 */
public class ChatModelFragment extends Fragment {
    
    // ======================== UI组件 ========================
    private RecyclerView rvChatMessages;     // 聊天消息列表
    private EditText etMessageInput;         // 消息输入框
    private EditText etSearch;               // 搜索输入框
    private Button btnSend;                  // 发送按钮
    private Button btnSearch;                // 搜索按钮
    
    // ======================== MVVM组件 ========================
    private ChatViewModel viewModel;         // ViewModel实例
    private ChatMessageAdapter messageAdapter; // 消息列表适配器
    private MessagePopupManager popupManager;  // 弹出菜单管理器
    
    // ======================== 状态管理 ========================
    private boolean isSearchMode = false;    // 是否处于搜索模式
    private OnBackPressedCallback backPressedCallback; // 返回键回调
    
    // ======================== 新增变量 ========================
    private View rootView;                   // 根视图引用
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener; // 键盘监听器
    private Handler searchHandler = new Handler(Looper.getMainLooper()); // 搜索延迟处理器
    private Runnable searchRunnable; // 搜索任务
    private View inputSection;               // 输入区域引用
    private int bottomNavHeight = 0;         // 底部导航栏高度
    
    // ======================== 背景更新广播接收器 ========================
    private BroadcastReceiver backgroundUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d("ChatModelFragment", "收到背景更新广播");
            if (BackgroundUpdateManager.ACTION_BACKGROUND_UPDATED.equals(intent.getAction())) {
                android.util.Log.d("ChatModelFragment", "广播动作匹配，开始重新加载背景");
                // 重新加载聊天背景
                if (rootView != null) {
                    android.util.Log.d("ChatModelFragment", "rootView不为空，调用loadChatBackground");
                    loadChatBackground(rootView);
                } else {
                    android.util.Log.e("ChatModelFragment", "rootView为空，无法加载背景");
                }
            } else {
                android.util.Log.w("ChatModelFragment", "广播动作不匹配: " + intent.getAction());
            }
        }
    };
    
    // ======================== 头像更新广播接收器 ========================
    private BroadcastReceiver avatarUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AvatarUpdateManager.ACTION_AVATAR_UPDATED.equals(intent.getAction())) {
                int userId = intent.getIntExtra(AvatarUpdateManager.EXTRA_USER_ID, -1);
                String avatarUri = intent.getStringExtra(AvatarUpdateManager.EXTRA_AVATAR_URI);
                
                if (userId != -1 && avatarUri != null && messageAdapter != null) {
                    messageAdapter.updateUserAvatar(userId, avatarUri);
                }
            }
        }
    };
    
    // ======================== 生命周期方法 ========================
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化ViewModel
        ChatRepository repository = new ChatRepository(requireContext());
        ChatViewModel.Factory factory = new ChatViewModel.Factory(requireActivity().getApplication(), repository);
        viewModel = new ViewModelProvider(this, factory).get(ChatViewModel.class);
        
        // 获取用户信息并设置到ViewModel
        UserInfoManager.getCurrentUserInfo(requireContext(), new UserInfoManager.UserInfoCallback() {
            @Override
            public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                // 设置用户信息到ViewModel
                if (relationshipId != null) {
                    viewModel.setUserInfo(userId, relationshipId);
                } else {
                    android.util.Log.w("ChatModelFragment", "relationshipId为null，使用默认值");
                    viewModel.setUserInfo(userId, -1);
                }
            }
            
            @Override
            public void onError(String error) {
                android.util.Log.w("ChatModelFragment", "获取用户信息失败: " + error);
                // 使用默认值或提示用户登录
            }
        });
        
        // 初始化弹出菜单管理器
        popupManager = new MessagePopupManager(requireContext());
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_model, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        rootView = view;
        
        // 初始化UI组件
        initViews(view);
        
        // 设置RecyclerView
        setupRecyclerView();
        
        // 设置事件监听器
        setupListeners();
        
        // 设置ViewModel观察者
        setupViewModelObservers();
        
        // 设置返回键处理
        setupBackPressedHandler();
        
        // 设置键盘监听
        setupKeyboardListener();
        
        // 加载聊天背景
        loadChatBackground(view);
        
        // 初始化数据
        viewModel.initializeData();
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // 清理搜索Handler
        if (searchHandler != null && searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        
        // 清理键盘监听器
        if (rootView != null && keyboardLayoutListener != null) {
            rootView.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        }
        
        // 清理返回键监听器
        if (backPressedCallback != null) {
            backPressedCallback.remove();
        }
    }
    
    // ======================== 初始化方法 ========================
    
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
        inputSection = view.findViewById(R.id.input_section);
        
        // 获取底部导航栏高度
        if (getActivity() != null) {
            View bottomNav = getActivity().findViewById(R.id.bottom_nav);
            if (bottomNav != null) {
                bottomNav.post(() -> {
                    bottomNavHeight = bottomNav.getHeight();
                });
            }
        }
    }
    
    /**
     * 设置RecyclerView
     */
    private void setupRecyclerView() {
        // 初始化适配器
        messageAdapter = new ChatMessageAdapter(requireContext(), new ArrayList<>());
        
        // 设置适配器交互监听器
        messageAdapter.setOnMessageInteractionListener(new ChatMessageAdapter.OnMessageInteractionListener() {
            @Override
            public void onLikeStatusChanged(ChatMessage message, int position, boolean isLiked) {
                // 委托给ViewModel处理点赞逻辑
                viewModel.toggleMessageLike(message);
            }
            
            @Override
            public void onMessageLongClick(View anchorView, ChatMessage message, int position) {
                // 显示弹出菜单
                popupManager.showMessagePopupMenu(anchorView, message, position);
            }
            
            @Override
            public void onAvatarClick(ChatMessage message, int position) {
                // 头像点击事件 - 跳转到个人设置页面
                Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                intent.putExtra("username", message.getUsername());
                intent.putExtra("id", message.getUserId());
                startActivity(intent);
            }
        });
        
        // 设置RecyclerView
        rvChatMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        rvChatMessages.setAdapter(messageAdapter);
    }
    
    /**
     * 设置事件监听器
     */
    private void setupListeners() {
        // 发送按钮点击事件
        btnSend.setOnClickListener(v -> sendMessage());
        
        // 搜索按钮点击事件
        btnSearch.setOnClickListener(v -> performSearch());
        
        // 搜索输入框文本变化监听 - 实现实时搜索
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // 文本变化前的处理（通常不需要实现）
            }
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 取消之前的搜索任务
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                
                // 创建新的搜索任务
                searchRunnable = () -> {
                    String searchText = s.toString().trim();
                    if (!TextUtils.isEmpty(searchText)) {
                        // 执行搜索
                        viewModel.searchMessages(searchText);
                    } else {
                        // 如果搜索框为空，退出搜索模式
                        viewModel.exitSearchMode();
                    }
                };
                
                // 延迟300毫秒执行搜索，避免频繁搜索
                searchHandler.postDelayed(searchRunnable, 300);
            }
            
            @Override
            public void afterTextChanged(Editable s) {
                // 文本变化后的处理（通常不需要实现）
            }
        });
        
        // 输入框焦点监听
        etMessageInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // 获得焦点时滚动到最新消息
                rvChatMessages.post(() -> {
                    if (messageAdapter != null && messageAdapter.getItemCount() > 0) {
                        rvChatMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
                    }
                });
            }
        });
        
        // 添加根视图触摸事件监听器 - 点击空白区域隐藏键盘
        if (rootView != null) {
            rootView.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    // 获取触摸点坐标
                    float x = event.getX();
                    float y = event.getY();
                    
                    // 检查是否点击在输入框或按钮上
                    if (!isTouchInsideView(etMessageInput, x, y) && 
                        !isTouchInsideView(etSearch, x, y) &&
                        !isTouchInsideView(btnSend, x, y) &&
                        !isTouchInsideView(btnSearch, x, y)) {
                        
                        // 清除输入框焦点
                        etMessageInput.clearFocus();
                        etSearch.clearFocus();
                        
                        // 隐藏键盘
                        hideKeyboard();
                        
                        return true; // 消费触摸事件
                    }
                }
                return false; // 不消费触摸事件，让其他组件正常处理
            });
        }
        
        // 设置弹出菜单操作监听器
        popupManager.setOnMenuActionListener(new MessagePopupManager.OnMenuActionListener() {
            @Override
            public void onCopyMessage(ChatMessage message) {
                // 直接使用MessagePopupManager的复制功能
                if (message != null && message.getContent() != null) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
                        getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("聊天消息", message.getContent());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(), "消息已复制到剪贴板", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onDeleteMessage(ChatMessage message, int position) {
                viewModel.deleteMessage(message);
            }
            
            @Override
            public void onBillingAction(ChatMessage message) {
                Toast.makeText(getContext(), "记账功能开发中...", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 检查触摸点是否在指定视图内
     * @param view 要检查的视图
     * @param x 触摸点X坐标
     * @param y 触摸点Y坐标
     * @return 是否在视图内
     */
    private boolean isTouchInsideView(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        
        // 获取根视图在屏幕上的位置
        int[] rootLocation = new int[2];
        rootView.getLocationOnScreen(rootLocation);
        
        // 计算相对于根视图的坐标
        float relativeX = x + rootLocation[0];
        float relativeY = y + rootLocation[1];
        
        return relativeX >= location[0] && 
               relativeX <= location[0] + view.getWidth() &&
               relativeY >= location[1] && 
               relativeY <= location[1] + view.getHeight();
    }
    
    /**
     * 设置ViewModel观察者
     * 监听数据变化并更新UI
     */
    private void setupViewModelObservers() {
        // 观察消息列表变化
        viewModel.getAllMessages().observe(getViewLifecycleOwner(), messages -> {
            if (messages != null && !isSearchMode) {
                messageAdapter.updateMessages(messages);
                // 为当前用户的消息设置头像URI
                loadCurrentUserAvatarForMessages();
                // 滚动到最新消息
                if (!messages.isEmpty()) {
                    rvChatMessages.scrollToPosition(messages.size() - 1);
                }
            }
        });
        
        // 添加搜索结果观察者
        viewModel.getSearchResults().observe(getViewLifecycleOwner(), searchResults -> {
            if (searchResults != null && isSearchMode) {
                messageAdapter.updateMessages(searchResults);
                // 搜索结果不需要滚动到底部，保持当前位置
            }
        });

        // 观察搜索模式状态
        viewModel.getIsSearchMode().observe(getViewLifecycleOwner(), isSearchMode -> {
            this.isSearchMode = isSearchMode;
            backPressedCallback.setEnabled(isSearchMode);
            
            // 根据搜索模式切换显示的数据源
            if (!isSearchMode) {
                // 退出搜索模式时，重新显示所有消息
                List<ChatMessage> allMessages = viewModel.getAllMessages().getValue();
                if (allMessages != null) {
                    messageAdapter.updateMessages(allMessages);
                    if (!allMessages.isEmpty()) {
                        rvChatMessages.scrollToPosition(allMessages.size() - 1);
                    }
                }
            }
        });

        // 观察错误消息
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), errorMsg -> {
            if (errorMsg != null && !errorMsg.isEmpty()) {
                Toast.makeText(getContext(), errorMsg, Toast.LENGTH_SHORT).show();
            }
        });
        
        // 观察成功消息
        viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), successMsg -> {
            if (successMsg != null && !successMsg.isEmpty()) {
                Toast.makeText(getContext(), successMsg, Toast.LENGTH_SHORT).show();
            }
        });
        
        // 观察加载状态
        viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            // 可以在这里显示/隐藏加载指示器
            // 例如：progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });
        
        // ==================== 新增：云端同步功能监听 ====================
        
        // 观察云端同步开关状态
        viewModel.getIsCloudSyncEnabled().observe(getViewLifecycleOwner(), isEnabled -> {
            // 可以在这里更新UI显示同步状态
            // 例如：显示同步开关状态、更新菜单项等
        });
        
        // 观察网络状态
        viewModel.getIsNetworkAvailable().observe(getViewLifecycleOwner(), isAvailable -> {
            if (!isAvailable) {
                Toast.makeText(getContext(), "网络连接不可用，已切换到离线模式", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 观察云端同步状态
        viewModel.getSyncStatusMessage().observe(getViewLifecycleOwner(), syncStatus -> {
            if (syncStatus != null) {
                switch (syncStatus) {
                    case "SYNCING":
                        // 显示同步中状态
                        break;
                    case "SYNCED":
                        // 显示同步完成状态
                        break;
                    case "SYNC_FAILED":
                        // 显示同步失败状态
                        break;
                    case "OFFLINE":
                        // 显示离线状态
                        break;
                }
            }
        });
        
        // 观察同步进度
        viewModel.getSyncProgress().observe(getViewLifecycleOwner(), progress -> {
            // 可以在这里更新进度条显示
            // 例如：progressBar.setProgress(progress);
            if (progress != null && progress > 0 && progress < 100) {
                // 显示同步进度
                // Toast.makeText(getContext(), "同步进度: " + progress + "%", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 观察同步错误
        viewModel.getSyncError().observe(getViewLifecycleOwner(), errorMsg -> {
            if (errorMsg != null && !errorMsg.isEmpty()) {
                Toast.makeText(getContext(), "同步错误: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    // ======================== 用户交互方法 ========================
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String messageText = etMessageInput.getText().toString().trim();
        
        // 输入验证
        if (TextUtils.isEmpty(messageText)) {
            Toast.makeText(getContext(), "请输入消息内容", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 委托给ViewModel处理发送逻辑，只传递消息内容字符串
        viewModel.sendMessage(messageText); // 修改：直接传递字符串而不是ChatMessage对象
        
        // 清空输入框
        etMessageInput.setText("");
    }
    
    /**
     * 执行搜索
     */
    private void performSearch() {
        String searchText = etSearch.getText().toString().trim();
        
        if (TextUtils.isEmpty(searchText)) {
            Toast.makeText(getContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 隐藏软键盘
        hideKeyboard();
        
        // 委托给ViewModel处理搜索逻辑
        viewModel.searchMessages(searchText);
    }
    
    /**
     * 隐藏软键盘
     */
    private void hideKeyboard() {
        if (getActivity() != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getView() != null) {
                imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
            }
        }
    }
    
    // ======================== 返回键处理 ========================
    
    /**
     * 设置返回键处理
     */
    private void setupBackPressedHandler() {
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isSearchMode) {
                    // 如果处于搜索模式，退出搜索模式
                    viewModel.exitSearchMode();
                    etSearch.setText(""); // 清空搜索框
                } else {
                    // 否则执行默认返回操作
                    setEnabled(false);
                    requireActivity().onBackPressed();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);
    }
    
    // ======================== 背景设置 ========================
    
    /**
     * 加载聊天背景设置
     * 从SharedPreferences读取用户设置的背景并应用到聊天界面
     * @param rootView 根视图
     */
    private void loadChatBackground(View rootView) {
        android.util.Log.d("ChatModelFragment", "开始加载聊天背景");
        SharedPreferences prefs = requireActivity().getSharedPreferences("chat_settings", Context.MODE_PRIVATE);
        
        // 检查是否有自定义背景URI
        String backgroundUri = prefs.getString("chat_background_uri", null);
        android.util.Log.d("ChatModelFragment", "自定义背景URI: " + backgroundUri);
        if (backgroundUri != null) {
            try {
                // 应用自定义背景图片
                Uri uri = Uri.parse(backgroundUri);
                Drawable drawable = Drawable.createFromStream(
                    requireActivity().getContentResolver().openInputStream(uri), null);
                if (drawable != null) {
                    android.util.Log.d("ChatModelFragment", "成功加载自定义背景");
                    rootView.setBackground(drawable);
                    return;
                }
            } catch (Exception e) {
                android.util.Log.e("ChatModelFragment", "加载自定义背景失败", e);
                // 如果加载自定义背景失败，继续尝试预设背景
            }
        }
        
        // 检查是否有预设背景
        int backgroundResId = prefs.getInt("chat_background", -1);
        android.util.Log.d("ChatModelFragment", "预设背景资源ID: " + backgroundResId);
        if (backgroundResId != -1) {
            try {
                android.util.Log.d("ChatModelFragment", "开始设置预设背景: " + backgroundResId);
                rootView.setBackgroundResource(backgroundResId);
                android.util.Log.d("ChatModelFragment", "预设背景设置成功");
                return;
            } catch (Exception e) {
                android.util.Log.e("ChatModelFragment", "设置预设背景失败", e);
                // 如果加载预设背景失败，使用默认背景
            }
        }
        
        // 检查是否有纯色背景
        int backgroundColor = prefs.getInt("chat_background_color", -1);
        android.util.Log.d("ChatModelFragment", "纯色背景: " + backgroundColor);
        if (backgroundColor != -1) {
            rootView.setBackgroundColor(backgroundColor);
            android.util.Log.d("ChatModelFragment", "纯色背景设置成功");
        } else {
            android.util.Log.d("ChatModelFragment", "没有任何背景设置，保持默认背景");
        }
    }
    
    /**
     * 设置键盘监听器
     * 实现类似微信的键盘交互效果
     */
    private void setupKeyboardListener() {
        if (rootView != null) {
            keyboardLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                private int previousHeight = 0;
                
                @Override
                public void onGlobalLayout() {
                    // 检查Fragment是否仍然附加到上下文
                    if (!isAdded() || getContext() == null || rootView == null) {
                        return;
                    }
                    
                    Rect rect = new Rect();
                    rootView.getWindowVisibleDisplayFrame(rect);
                    
                    int screenHeight = rootView.getRootView().getHeight();
                    int keypadHeight = screenHeight - rect.bottom;
                    
                    // 键盘高度阈值（通常大于150dp认为键盘弹出）
                    int threshold = (int) (150 * getResources().getDisplayMetrics().density);
                    
                    if (keypadHeight > threshold) {
                    // 键盘弹出
                    // 手动调整输入框位置：上移键盘高度减去底部导航栏高度
                    int adjustHeight = keypadHeight - bottomNavHeight;
                    
                    // 调整输入区域位置
                    if (inputSection != null) {
                        inputSection.setTranslationY(-adjustHeight);
                    }
                    
                    // 调整RecyclerView底部边距，为上移的输入框让出空间
                    if (rvChatMessages != null) {
                        rvChatMessages.setPadding(
                            rvChatMessages.getPaddingLeft(),
                            rvChatMessages.getPaddingTop(),
                            rvChatMessages.getPaddingRight(),
                            adjustHeight + 8 // 原始padding + 调整高度
                        );
                    }
                    
                    onKeyboardShown(keypadHeight);
                } else {
                    // 键盘隐藏
                    // 恢复输入区域位置
                    if (inputSection != null) {
                        inputSection.setTranslationY(0);
                    }
                    
                    // 恢复RecyclerView底部边距
                    if (rvChatMessages != null) {
                        rvChatMessages.setPadding(
                            rvChatMessages.getPaddingLeft(),
                            rvChatMessages.getPaddingTop(),
                            rvChatMessages.getPaddingRight(),
                            8 // 恢复原始padding
                        );
                    }
                    
                    onKeyboardHidden();
                }
                    
                    previousHeight = keypadHeight;
                }
            };
            
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
        }
    }
    
    /**
     * 键盘弹出时的处理
     */
    private void onKeyboardShown(int keyboardHeight) {
        if (!isAdded() || getContext() == null || rvChatMessages == null) {
            return;
        }
        
        // 延迟滚动到最新消息，确保布局调整完成
        rvChatMessages.postDelayed(() -> {
            if (isAdded() && messageAdapter != null && messageAdapter.getItemCount() > 0) {
                rvChatMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            }
        }, 100);
    }
    
    /**
     * 键盘隐藏时的处理
     */
    private void onKeyboardHidden() {
        // 键盘隐藏时的处理逻辑（如果需要）
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        android.util.Log.d("ChatModelFragment", "注册头像更新广播接收器");
        // 注册头像更新广播接收器
        IntentFilter avatarFilter = new IntentFilter(AvatarUpdateManager.ACTION_AVATAR_UPDATED);
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(avatarUpdateReceiver, avatarFilter);
            
        android.util.Log.d("ChatModelFragment", "注册背景更新广播接收器");
        // 注册背景更新广播接收器
        IntentFilter backgroundFilter = new IntentFilter(BackgroundUpdateManager.ACTION_BACKGROUND_UPDATED);
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(backgroundUpdateReceiver, backgroundFilter);
        
        // ... existing code ...
    }
    
    /**
     * 为当前用户的消息加载头像URI
     */
    private void loadCurrentUserAvatarForMessages() {
        if (getContext() == null) return;
        
        try {
            // 获取当前用户信息
            int currentUserId = UserInfoManager.getCurrentUserId(getContext());
            String currentUsername = UserInfoManager.getCurrentUsername(getContext());
            
            if (currentUserId > 0) {
                // 从SharedPreferences加载保存的头像URI
                SharedPreferences prefs = getContext().getSharedPreferences("user_avatars", Context.MODE_PRIVATE);
                String savedUriString = prefs.getString("avatar_uri_" + currentUserId, null);
                
                if (savedUriString != null && messageAdapter != null) {
                    // 更新当前用户的消息头像
                    messageAdapter.updateUserAvatar(currentUserId, savedUriString);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        android.util.Log.d("ChatModelFragment", "注销头像更新广播接收器");
        // 注销头像更新广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(avatarUpdateReceiver);
            
        android.util.Log.d("ChatModelFragment", "注销背景更新广播接收器");
        // 注销背景更新广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(backgroundUpdateReceiver);
        
        // ... existing code ...
    }
    
}

