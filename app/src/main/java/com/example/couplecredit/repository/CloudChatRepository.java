package com.example.couplecredit.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.api.AuthApiModels;
import com.example.couplecredit.model.ChatMessage;
import com.example.couplecredit.utils.UserInfoManager;

import java.util.ArrayList;
import java.util.List;

public class CloudChatRepository {

    private static final String TAG = "CloudChatRepository";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentRelationshipId = -1;
    private boolean relationshipResolved = false;
    private final List<Runnable> pendingOperations = new ArrayList<>();
    private int currentUserId = -1;
    private String currentUsername = null;
    private Context context;

    public CloudChatRepository(Context context) {
        this.context = context;
        initializeUserInfo();
    }

    public void initializeUserInfo() {
        if (UserInfoManager.isUserLoggedIn(context)) {
            int userId = UserInfoManager.getCurrentUserId(context);
            String username = UserInfoManager.getCurrentUsername(context);

            if (userId > 0 && username != null) {
                currentUserId = userId;
                currentUsername = username;
            }

            UserInfoManager.getCurrentUserInfo(context, new UserInfoManager.UserInfoCallback() {
                @Override
                public void onUserInfoLoaded(int userId, String username, Integer relationshipId) {
                    currentRelationshipId = relationshipId != null ? relationshipId : -1;
                    relationshipResolved = true;
                    flushPendingOperations();
                }
                @Override
                public void onError(String error) {
                    currentRelationshipId = -1;
                    relationshipResolved = true;
                    flushPendingOperations();
                }
            });
        } else {
            relationshipResolved = true;
        }
    }

    private synchronized void flushPendingOperations() {
        for (Runnable op : pendingOperations) {
            op.run();
        }
        pendingOperations.clear();
    }

    private synchronized void executeWhenReady(Runnable operation) {
        if (relationshipResolved) {
            operation.run();
        } else {
            pendingOperations.add(operation);
        }
    }

    public void insertMessage(ChatMessage message, int userId, InsertCallback callback) {
        executeWhenReady(() -> {
            AuthApiClient.insertChatMessage(context, currentRelationshipId, userId,
                    message.getContent(), "text", message.getTimestamp(), message.isLiked(),
                    new AuthApiClient.ChatInsertCallback() {
                        @Override
                        public void onSuccess(long messageId) {
                            Log.d(TAG, "消息插入成功，ID: " + messageId);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onSuccess(messageId));
                            }
                        }
                        @Override
                        public void onError(String e) {
                            Log.e(TAG, "消息插入失败: " + e);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError(new Exception(e)));
                            }
                        }
                    });
        });
    }

    public void insertMessage(long relationshipId, int userId, String content,
                              String messageType, String displayTime, String avatarUrl, boolean isLiked, InsertCallback callback) {
        insertMessage(relationshipId, userId, content, messageType, displayTime, isLiked, callback);
    }

    public void insertMessage(long relationshipId, int userId, String content,
                              String messageType, String displayTime, boolean isLiked, InsertCallback callback) {
        executeWhenReady(() -> {
            int relId = relationshipId > 0 ? (int) relationshipId : currentRelationshipId;
            AuthApiClient.insertChatMessage(context, relId, userId,
                    content, messageType, displayTime, isLiked,
                    new AuthApiClient.ChatInsertCallback() {
                        @Override
                        public void onSuccess(long messageId) {
                            Log.d(TAG, "消息插入成功，ID: " + messageId);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onSuccess(messageId));
                            }
                        }
                        @Override
                        public void onError(String e) {
                            Log.e(TAG, "消息插入失败: " + e);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError(new Exception(e)));
                            }
                        }
                    });
        });
    }

    public void getAllMessages(QueryCallback callback) {
        executeWhenReady(() -> {
            if (currentRelationshipId <= 0) {
                if (callback != null) callback.onSuccess(new ArrayList<>());
                return;
            }
            AuthApiClient.getChatMessages(context, currentUserId, currentRelationshipId, 200, null,
                    new AuthApiClient.ChatMessageListCallback() {
                        @Override
                        public void onSuccess(List<AuthApiModels.ChatMessageData> messages) {
                            List<ChatMessage> result = convertToChatMessages(messages);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onSuccess(result));
                            }
                        }
                        @Override
                        public void onError(String e) {
                            Log.e(TAG, "查询消息失败: " + e);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError(new Exception(e)));
                            }
                        }
                    });
        });
    }

    public void updateMessageLikeStatus(long cloudMessageId, String messageContent, String timestamp, boolean isLiked, UpdateCallback callback) {
        AuthApiClient.toggleChatLike(context, currentUserId, cloudMessageId, isLiked, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess());
                }
            }
            @Override
            public void onError(String e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(new Exception(e)));
                }
            }
        });
    }

    public void deleteMessage(long messageId, String messageContent, String timestamp, DeleteCallback callback) {
        AuthApiClient.deleteChatMessage(context, currentUserId, messageId, new AuthApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess());
                }
            }
            @Override
            public void onError(String e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(new Exception(e)));
                }
            }
        });
    }

    public void searchMessages(String keyword, QueryCallback callback) {
        executeWhenReady(() -> {
            if (currentRelationshipId <= 0) {
                if (callback != null) callback.onSuccess(new ArrayList<>());
                return;
            }
            AuthApiClient.searchChatMessages(context, currentUserId, currentRelationshipId, keyword,
                    new AuthApiClient.ChatMessageListCallback() {
                        @Override
                        public void onSuccess(List<AuthApiModels.ChatMessageData> messages) {
                            List<ChatMessage> result = convertToChatMessages(messages);
                            if (callback != null) {
                                mainHandler.post(() -> callback.onSuccess(result));
                            }
                        }
                        @Override
                        public void onError(String e) {
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError(new Exception(e)));
                            }
                        }
                    });
        });
    }

    public void getBatchChatData(BatchDataCallback callback) {
        getAllMessages(new QueryCallback() {
            @Override
            public void onSuccess(List<ChatMessage> messages) {
                int liked = 0;
                for (ChatMessage m : messages) {
                    if (m.isLiked()) liked++;
                }
                BatchChatData data = new BatchChatData(messages, liked, currentUserId);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess(data));
                }
            }
            @Override
            public void onError(Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }

    private List<ChatMessage> convertToChatMessages(List<AuthApiModels.ChatMessageData> data) {
        List<ChatMessage> result = new ArrayList<>();
        if (data == null) return result;
        for (AuthApiModels.ChatMessageData d : data) {
            String timestamp = d.displayTime != null ? d.displayTime : String.valueOf(d.createdAt);
            boolean sentByMe = currentUserId > 0 && d.userId == currentUserId;
            ChatMessage msg = new ChatMessage("", d.userId, d.content, timestamp, 0, null, sentByMe);
            msg.setId((int) d.id);
            msg.setLiked(d.isLiked);
            msg.setMessageType(d.messageType != null ? d.messageType : "text");
            msg.setRelationshipId(d.relationshipId);
            msg.setCloudMessageId(d.id);
            result.add(msg);
        }
        return result;
    }

    public void setCurrentRelationshipId(int relationshipId) {
        this.currentRelationshipId = relationshipId;
    }

    public void setCurrentUserInfo(int userId, String username) {
        this.currentUserId = userId;
        this.currentUsername = username;
    }

    public void setCurrentUserInfo(int userId, String username, Integer relationshipId) {
        this.currentUserId = userId;
        this.currentUsername = username;
        if (relationshipId != null) {
            this.currentRelationshipId = relationshipId;
        }
    }

    public void testConnection(ConnectionTestCallback callback) {
        if (callback != null) {
            callback.onSuccess(true, "HTTP API mode");
        }
    }

    public void cleanup() {
        mainHandler.removeCallbacksAndMessages(null);
    }

    public interface InsertCallback {
        void onSuccess(long messageId);
        void onError(Exception e);
    }

    public interface QueryCallback {
        void onSuccess(List<ChatMessage> messages);
        void onError(Exception e);
    }

    public interface UpdateCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface BatchDataCallback {
        void onSuccess(BatchChatData batchData);
        void onError(Exception e);
    }

    public static class BatchChatData {
        public List<ChatMessage> messages;
        public int likedMessages;
        public int currentUserId;

        public BatchChatData(List<ChatMessage> messages, int likedMessages, int currentUserId) {
            this.messages = messages;
            this.likedMessages = likedMessages;
            this.currentUserId = currentUserId;
        }
    }

    public interface ConnectionTestCallback {
        void onSuccess(boolean connected, String message);
    }
}
