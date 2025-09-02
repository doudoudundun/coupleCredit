package com.example.couplecredit.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 头像上传API类
 * 负责处理用户头像的上传和存储到服务器数据库
 */
public class AvatarUploadApi {
    
    private static final String TAG = "AvatarUploadApi";
    
    // 数据库连接配置
    private static final String DB_HOST = "101.37.68.240";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "demodb";
    private static final String DB_USER = "demodb";
    private static final String DB_PASSWORD = "root";
    private static final String DB_URL = "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    // 头像压缩配置
    private static final int MAX_AVATAR_SIZE = 512; // 最大尺寸512x512
    private static final int JPEG_QUALITY = 80; // JPEG压缩质量
    
    /**
     * 上传用户头像
     * @param context 上下文
     * @param userId 用户ID
     * @param imageUri 图片URI
     * @param callback 上传结果回调
     */
    public static void uploadAvatar(Context context, int userId, Uri imageUri, AvatarUploadCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage = "";
            private String avatarUrl = "";
            
            @Override
            protected Boolean doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                
                try {
                    // 1. 压缩图片
                    String base64Image = compressAndEncodeImage(context, imageUri);
                    if (base64Image == null) {
                        errorMessage = "图片处理失败";
                        return false;
                    }
                    
                    // 2. 连接数据库
                    connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                    
                    // 3. 更新用户头像字段
                    String updateSQL = "UPDATE users SET avatar = ? WHERE id = ?";
                    preparedStatement = connection.prepareStatement(updateSQL);
                    preparedStatement.setString(1, base64Image);
                    preparedStatement.setInt(2, userId);
                    
                    int rowsAffected = preparedStatement.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        avatarUrl = "data:image/jpeg;base64," + base64Image;
                        return true;
                    } else {
                        errorMessage = "用户不存在或更新失败";
                        return false;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "上传头像失败: " + e.getMessage(), e);
                    return false;
                } catch (Exception e) {
                    errorMessage = "上传失败: " + e.getMessage();
                    Log.e(TAG, "头像上传异常: " + e.getMessage(), e);
                    return false;
                } finally {
                    closeResources(connection, preparedStatement);
                }
            }
            
            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onUploadSuccess(avatarUrl);
                    } else {
                        callback.onUploadError(errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 获取用户头像
     * @param userId 用户ID
     * @param callback 获取结果回调
     */
    public static void getAvatar(int userId, AvatarGetCallback callback) {
        new AsyncTask<Void, Void, String>() {
            private String errorMessage = "";
            
            @Override
            protected String doInBackground(Void... voids) {
                Connection connection = null;
                PreparedStatement preparedStatement = null;
                java.sql.ResultSet resultSet = null;
                
                try {
                    connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                    
                    String selectSQL = "SELECT avatar FROM users WHERE id = ?";
                    preparedStatement = connection.prepareStatement(selectSQL);
                    preparedStatement.setInt(1, userId);
                    
                    resultSet = preparedStatement.executeQuery();
                    
                    if (resultSet.next()) {
                        String avatar = resultSet.getString("avatar");
                        if (avatar != null && !avatar.isEmpty()) {
                            return "data:image/jpeg;base64," + avatar;
                        } else {
                            return null; // 用户没有设置头像
                        }
                    } else {
                        errorMessage = "用户不存在";
                        return null;
                    }
                    
                } catch (SQLException e) {
                    errorMessage = "数据库错误: " + e.getMessage();
                    Log.e(TAG, "获取头像失败: " + e.getMessage(), e);
                    return null;
                } catch (Exception e) {
                    errorMessage = "获取失败: " + e.getMessage();
                    Log.e(TAG, "头像获取异常: " + e.getMessage(), e);
                    return null;
                } finally {
                    closeResources(connection, preparedStatement, resultSet);
                }
            }
            
            @Override
            protected void onPostExecute(String avatarUrl) {
                if (callback != null) {
                    if (avatarUrl != null) {
                        callback.onAvatarLoaded(avatarUrl);
                    } else {
                        callback.onAvatarError(errorMessage.isEmpty() ? "头像不存在" : errorMessage);
                    }
                }
            }
        }.execute();
    }
    
    /**
     * 压缩并编码图片为Base64
     * @param context 上下文
     * @param imageUri 图片URI
     * @return Base64编码的图片字符串
     */
    private static String compressAndEncodeImage(Context context, Uri imageUri) {
        try {
            // 读取图片
            InputStream inputStream = context.getContentResolver().openInputStream(imageUri);
            Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();
            
            if (originalBitmap == null) {
                return null;
            }
            
            // 计算压缩比例
            int width = originalBitmap.getWidth();
            int height = originalBitmap.getHeight();
            float scale = Math.min((float) MAX_AVATAR_SIZE / width, (float) MAX_AVATAR_SIZE / height);
            
            // 如果图片已经足够小，不需要压缩
            if (scale >= 1.0f) {
                scale = 1.0f;
            }
            
            // 压缩图片
            int newWidth = Math.round(width * scale);
            int newHeight = Math.round(height * scale);
            Bitmap compressedBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true);
            
            // 转换为JPEG格式的字节数组
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            compressedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();
            
            // 清理资源
            originalBitmap.recycle();
            compressedBitmap.recycle();
            byteArrayOutputStream.close();
            
            // 编码为Base64
            return Base64.encodeToString(imageBytes, Base64.DEFAULT);
            
        } catch (Exception e) {
            Log.e(TAG, "图片压缩编码失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 关闭数据库资源
     */
    private static void closeResources(Connection connection, PreparedStatement statement) {
        closeResources(connection, statement, null);
    }
    
    /**
     * 关闭数据库资源
     */
    private static void closeResources(Connection connection, PreparedStatement statement, java.sql.ResultSet resultSet) {
        try {
            if (resultSet != null) {
                resultSet.close();
            }
            if (statement != null) {
                statement.close();
            }
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            Log.e(TAG, "关闭数据库资源失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 头像上传回调接口
     */
    public interface AvatarUploadCallback {
        void onUploadSuccess(String avatarUrl);
        void onUploadError(String error);
    }
    
    /**
     * 头像获取回调接口
     */
    public interface AvatarGetCallback {
        void onAvatarLoaded(String avatarUrl);
        void onAvatarError(String error);
    }
}