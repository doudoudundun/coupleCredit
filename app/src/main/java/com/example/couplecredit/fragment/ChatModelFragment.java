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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.couplecredit.R;
import com.example.couplecredit.activity.UserSettingsActivity;
import com.example.couplecredit.adapter.ChatMessageAdapter;
import com.example.couplecredit.dialog.ChatBillingDialog;
import com.example.couplecredit.function.CustomToast;
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

    private static final String TAG = "ChatModelFragment";
    
    // ======================== UI组件 ========================
    private RecyclerView rvChatMessages;     // 聊天消息列表
    private SwipeRefreshLayout swipeRefreshLayout; // 下拉刷新布局
    private EditText etMessageInput;         // 消息输入框
    private EditText etSearch;               // 搜索输入框
    private Button btnSend;                  // 发送按钮
    private Button btnSearch;                // 搜索按钮
    private View loadingNewerIndicator;      // 加载新消息指示器
    private View loadingOlderIndicator;      // 加载历史消息指示器
    
    // ======================== MVVM组件 ========================
    private ChatViewModel viewModel;         // ViewModel实例
    private ChatMessageAdapter messageAdapter; // 消息列表适配器
    private MessagePopupManager popupManager;  // 弹出菜单管理器
    
    // ======================== 状态管理 ========================
// 状态管理
    private boolean isSearchMode = false;    // 是否处于搜索模式
    private OnBackPressedCallback backPressedCallback; // 返回键回调
    
    // 双向滑动加载状态
    private boolean isLoadingNewer = false;  // 是否正在加载新消息
    private boolean isLoadingOlder = false;  // 是否正在加载历史消息
    private boolean hasMoreNewer = true;     // 是否还有更新的消息
    private boolean hasMoreOlder = true;     // 是否还有更老的消息
    
    // ======================== 新增变量 ========================
    private View rootView;                   // 根视图引用
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener; // 键盘监听器
    private Handler searchHandler = new Handler(Looper.getMainLooper()); // 搜索延迟处理器
    private Runnable searchRunnable; // 搜索任务
    private View inputSection;               // 输入区域引用
    private int bottomNavHeight = 0;         // 底部导航栏高度
    
    // 点赞功能已移除
    // private ChatMessage lastLikedMessage = null;
    // private int lastLikedPosition = -1;
    // private boolean lastLikedStatus = false;
    
    // ======================== 背景更新广播接收器 ========================
    private BroadcastReceiver backgroundUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d(TAG, "收到背景更新广播");
            if (BackgroundUpdateManager.ACTION_BACKGROUND_UPDATED.equals(intent.getAction())) {
                android.util.Log.d(TAG, "广播动作匹配，开始重新加载背景");
                // 重新加载聊天背景
                if (rootView != null) {
                    android.util.Log.d(TAG, "rootView不为空，调用loadChatBackground");
                    loadChatBackground(rootView);
                } else {
                    android.util.Log.e(TAG, "rootView为空，无法加载背景");
                }
            } else {
                android.util.Log.w(TAG, "广播动作不匹配: " + intent.getAction());
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
    
    // ======================== 登录状态变化广播接收器 ========================
    private BroadcastReceiver loginStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.couplecredit.USER_LOGOUT".equals(intent.getAction())) {
                // 用户退出登录，隐藏聊天界面并完全清空数据
                hideChatInterface();
                if (viewModel != null) {
                    viewModel.clearChatMessages(true); // 完全清理数据库
                }
            } else if ("com.example.couplecredit.USER_LOGIN".equals(intent.getAction())) {
                // 用户重新登录，检查登录状态并更新UI
                checkLoginStatusAndUpdateUI();
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
                    android.util.Log.w(TAG, "relationshipId为null，使用默认值");
                    viewModel.setUserInfo(userId, -1);
                }
            }
            
            @Override
            public void onError(String error) {
                android.util.Log.w(TAG, "获取用户信息失败: " + error);
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
        
        // 检查用户登录状态并相应地显示界面
        checkLoginStatusAndUpdateUI();
    }
    
    /**
     * 检查登录状态并更新UI显示
     */
    private void checkLoginStatusAndUpdateUI() {
        if (UserInfoManager.isUserLoggedIn(requireContext())) {
            // 用户已登录，显示聊天界面并初始化数据
            showChatInterface();
            viewModel.initializeData();
        } else {
            // 用户未登录，隐藏聊天界面并清空数据
            hideChatInterface();
            if (viewModel != null) {
                viewModel.clearChatMessages(true); // 完全清理数据
            }
        }
    }
    
    /**
     * 显示聊天界面
     */
    private void showChatInterface() {
        if (rvChatMessages != null) {
            rvChatMessages.setVisibility(View.VISIBLE);
        }
        if (etMessageInput != null) {
            etMessageInput.setVisibility(View.VISIBLE);
        }
        if (btnSend != null) {
            btnSend.setVisibility(View.VISIBLE);
        }
        if (inputSection != null) {
            inputSection.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * 隐藏聊天界面
     */
    private void hideChatInterface() {
        if (rvChatMessages != null) {
            rvChatMessages.setVisibility(View.GONE);
        }
        if (etMessageInput != null) {
            etMessageInput.setVisibility(View.GONE);
        }
        if (btnSend != null) {
            btnSend.setVisibility(View.GONE);
        }
        if (inputSection != null) {
            inputSection.setVisibility(View.GONE);
        }
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
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        etMessageInput = view.findViewById(R.id.et_message_input);
        etSearch = view.findViewById(R.id.et_search);
        btnSend = view.findViewById(R.id.btn_send);
        btnSearch = view.findViewById(R.id.btn_search);
        inputSection = view.findViewById(R.id.input_section);
        loadingNewerIndicator = view.findViewById(R.id.loading_newer_indicator);
        loadingOlderIndicator = view.findViewById(R.id.loading_older_indicator);
        
        // 设置下拉刷新的颜色和样式
        setupSwipeRefresh();
        
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
            // 点赞功能已移除
            // @Override
            // public void onLikeStatusChanged(ChatMessage message, int position, boolean isLiked) {
            //     记录点赞操作信息，用于失败时回滚
            //     lastLikedMessage = message;
            //     lastLikedPosition = position;
            //     lastLikedStatus = !isLiked; // 记录操作前的状态
            //     
            //     只更新数据模型，不触发RecyclerView更新，避免界面污染
            //     LikeButton已经在视觉上完成了状态更新
            //     直接更新message对象的状态即可，无需通过adapter触发界面更新
            //     
            //     异步更新数据库，避免阻塞UI
            //     viewModel.toggleMessageLike(message);
            // }
            
            @Override
            public void onMessageLongClick(View anchorView, ChatMessage message, int position) {
                // 显示弹出菜单
                popupManager.showMessagePopupMenu(anchorView, message, position);
            }
            
            @Override
            public void onAvatarClick(ChatMessage message, int position) {
                // 头像点击事件 - 只有点击当前用户（左侧）头像才跳转到个人设置页面
                if (message.isSentByMe()) {
                    // 检查用户是否已登录
                    if (!UserInfoManager.isUserLoggedIn(getContext())) {
                        CustomToast.show(getContext(), "请先登录", Toast.LENGTH_SHORT);
                        return;
                    }

                    // 当前用户的消息，跳转到个人设置页面
                    Intent intent = new Intent(getActivity(), UserSettingsActivity.class);
                    intent.putExtra("username", message.getUsername());
                    intent.putExtra("id", message.getUserId());
                    startActivity(intent);
                }
                // 伴侣的头像点击不做任何操作
            }
        });
        
        // 设置RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        rvChatMessages.setLayoutManager(layoutManager);
        rvChatMessages.setAdapter(messageAdapter);
        
        // 添加双向滑动监听器
        rvChatMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager == null) return;
                
                int totalItemCount = manager.getItemCount();
                int firstVisibleItem = manager.findFirstVisibleItemPosition();
                int lastVisibleItem = manager.findLastVisibleItemPosition();
                
                // 在顶部向上滑动（dy < 0）时，加载历史消息
                if (firstVisibleItem == 0 && dy < 0 && !isLoadingOlder && hasMoreOlder) {
                    loadOlderMessages();
                }
                
                // 在底部向下滑动（dy > 0）时，加载新消息
                if (lastVisibleItem >= totalItemCount - 1 && dy > 0 && !isLoadingNewer && hasMoreNewer) {
                    loadNewerMessages();
                }
            }
        });
    }
    
    /**
     * 设置下拉刷新功能
     */
    private void setupSwipeRefresh() {
        // 设置刷新指示器的颜色
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        );
        
        // 设置刷新监听器
        swipeRefreshLayout.setOnRefreshListener(() -> {
            android.util.Log.d(TAG, "用户触发下拉刷新");
            refreshMessages();
        });
        
        // 设置刷新触发距离
        swipeRefreshLayout.setDistanceToTriggerSync(150);
        
        // 设置刷新指示器的大小
        swipeRefreshLayout.setSize(SwipeRefreshLayout.DEFAULT);
    }
    
    /**
     * 刷新聊天消息
     */
    private void refreshMessages() {
        if (viewModel != null) {
            android.util.Log.d(TAG, "开始刷新消息列表");
            
            // 显示刷新动画
            swipeRefreshLayout.setRefreshing(true);
            
            // 调用ViewModel刷新数据
            viewModel.refreshMessages();
            
            // 延迟停止刷新动画，确保用户能看到刷新效果
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                    android.util.Log.d(TAG, "刷新完成，停止刷新动画");
                }
            }, 1000); // 1秒后停止刷新动画
        }
    }
    
    /**
     * 加载更新的消息（向上滑动触发）
     */
    private void loadNewerMessages() {
        if (isLoadingNewer || !hasMoreNewer || viewModel == null) {
            return;
        }
        
        isLoadingNewer = true;
        android.util.Log.d(TAG, "开始加载新消息");
        
        // 显示加载指示器
        if (loadingNewerIndicator != null) {
            loadingNewerIndicator.setVisibility(View.VISIBLE);
        }
        
        // 调用ViewModel加载新消息
        viewModel.loadNewerMessages();
        
        // 延迟重置加载状态和隐藏指示器
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isLoadingNewer = false;
            if (loadingNewerIndicator != null) {
                loadingNewerIndicator.setVisibility(View.GONE);
            }
            android.util.Log.d(TAG, "新消息加载完成");
        }, 1500);
    }
    
    /**
     * 加载历史消息（向下滑动触发）
     */
    private void loadOlderMessages() {
        if (isLoadingOlder || !hasMoreOlder || viewModel == null) {
            return;
        }
        
        isLoadingOlder = true;
        android.util.Log.d(TAG, "开始加载历史消息");
        
        // 显示加载指示器
        if (loadingOlderIndicator != null) {
            loadingOlderIndicator.setVisibility(View.VISIBLE);
        }
        
        // 调用ViewModel加载历史消息
        viewModel.loadOlderMessages();
        
        // 延迟重置加载状态和隐藏指示器
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isLoadingOlder = false;
            if (loadingOlderIndicator != null) {
                loadingOlderIndicator.setVisibility(View.GONE);
            }
            android.util.Log.d(TAG, "历史消息加载完成");
        }, 1000);
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
                    // Toast.makeText(getContext(), "消息已复制到剪贴板", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onDeleteMessage(ChatMessage message, int position) {
                viewModel.deleteMessage(message);
            }
            
            @Override
            public void onBillingAction(ChatMessage message) {
                // 打开聊天记账弹窗
                if (getContext() != null) {
                    ChatBillingDialog dialog = new ChatBillingDialog(getContext(), message);
                    
                    // 设置账单保存监听器
                    dialog.setOnBillSavedListener(new ChatBillingDialog.OnBillSavedListener() {
                        @Override
                        public void onBillSaved(long billId) {
                            // 账单保存成功，可以在这里添加额外的处理逻辑
                            // 例如：刷新统计数据、发送广播通知等
                        }
                        
                        @Override
                        public void onBillSaveError(String error) {
                            // 账单保存失败，显示错误信息
                            // Toast.makeText(getContext(), "保存失败: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                    
                    dialog.show();
                }
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
                // 记录当前滚动位置
                int currentPosition = -1;
                LinearLayoutManager layoutManager = (LinearLayoutManager) rvChatMessages.getLayoutManager();
                if (layoutManager != null) {
                    currentPosition = layoutManager.findLastVisibleItemPosition();
                }
                
                messageAdapter.updateMessages(messages);
                // 为当前用户的消息设置头像URI
                loadCurrentUserAvatarForMessages();
                
                // 只有在用户已经在底部或者是首次加载时才自动滚动到最新消息
                if (!messages.isEmpty()) {
                    boolean shouldScrollToBottom = currentPosition == -1 || // 首次加载
                            currentPosition >= messages.size() - 2; // 用户在底部附近
                    if (shouldScrollToBottom) {
                        rvChatMessages.scrollToPosition(messages.size() - 1);
                    }
                }
            }
        });
        
        // 添加搜索结果观察者
        viewModel.getSearchResults().observe(getViewLifecycleOwner(), searchResults -> {
            if (searchResults != null && isSearchMode) {
                messageAdapter.updateMessages(searchResults);
                // 为搜索结果中的当前用户消息设置头像URI
                loadCurrentUserAvatarForMessages();
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
                
                // 点赞功能已移除 - 不再处理点赞失败回滚
            // 如果是点赞操作失败，回滚UI状态
            // if (errorMsg.contains("操作失败") && lastLikedMessage != null && lastLikedPosition >= 0) {
            //     回滚消息对象的状态
            //     lastLikedMessage.setLiked(lastLikedStatus);
            //     
            //     直接通过ViewHolder回滚UI显示，避免触发RecyclerView更新
            //     RecyclerView.ViewHolder viewHolder = rvChatMessages.findViewHolderForAdapterPosition(lastLikedPosition);
            //     if (viewHolder instanceof ChatMessageAdapter.MessageViewHolder) {
            //         ((ChatMessageAdapter.MessageViewHolder) viewHolder).updateLikeButton(lastLikedStatus);
            //     }
            //     
            //     清除记录
            //     lastLikedMessage = null;
            //     lastLikedPosition = -1;
            // }
            }
        });
        
        // 观察成功消息
//        viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), successMsg -> {
//            if (successMsg != null && !successMsg.isEmpty()) {
//                Toast.makeText(getContext(), successMsg, Toast.LENGTH_SHORT).show();
//            }
//        });
        
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
                // Toast.makeText(getContext(), "网络连接不可用，已切换到离线模式", Toast.LENGTH_SHORT).show();
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
                //Toast.makeText(getContext(), "同步错误: " + errorMsg, Toast.LENGTH_LONG).show();
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
            CustomToast.show(getContext(), "请输入消息内容", Toast.LENGTH_SHORT);
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
            CustomToast.show(getContext(), "请输入搜索关键词", Toast.LENGTH_SHORT);
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
    public void onStart() {
        super.onStart();
        
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
        
        // 注册登录状态变化广播接收器
        IntentFilter loginFilter = new IntentFilter();
        loginFilter.addAction("com.example.couplecredit.USER_LOGOUT");
        loginFilter.addAction("com.example.couplecredit.USER_LOGIN");
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(loginStatusReceiver, loginFilter);
        android.util.Log.d("ChatModelFragment", "注册登录状态变化广播接收器");
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // 检查登录状态并更新UI
        checkLoginStatusAndUpdateUI();
        
        // 重新加载聊天背景，确保从设置页面返回时能看到最新的背景
        if (rootView != null) {
            android.util.Log.d(TAG, "onResume: 重新加载聊天背景");
            loadChatBackground(rootView);
        }
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
        
        // ... existing code ...
    }
    
    @Override
    public void onStop() {
        super.onStop();
        
        android.util.Log.d("ChatModelFragment", "注销头像更新广播接收器");
        // 注销头像更新广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(avatarUpdateReceiver);
            
        android.util.Log.d("ChatModelFragment", "注销背景更新广播接收器");
        // 注销背景更新广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(backgroundUpdateReceiver);
        
        // 注销登录状态变化广播接收器
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(loginStatusReceiver);
        android.util.Log.d("ChatModelFragment", "注销登录状态变化广播接收器");
    }
    
}

