package com.example.couplecredit.view;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.example.couplecredit.R;
import com.example.couplecredit.api.AuthApiClient;
import com.example.couplecredit.utils.BillUtils;
import com.example.couplecredit.utils.ImageCompressor;

import java.util.ArrayList;
import java.util.List;

/**
 * 账单图片条：照片（photos）+ 小票凭证（receipts）的缩略图横条。
 *
 * 与服务端契约对齐（server/src/routes/bills.js）：
 *  - 每类最多 MAX_BILL_IMAGES = 4 张，只提交 /uploads/... 相对路径；
 *  - 上传统一走 POST /api/upload/image（multipart 字段 image）。
 *
 * 两种形态：
 *  - 编辑态（setEditMode(true)）：显示「照片 / 小票」添加按钮，缩略图带角标「×」可删除，
 *    由宿主（Fragment/弹窗）通过 OnPickImageListener 发起系统选图，选完回调 onImagePicked；
 *  - 查看态（setEditMode(false)）：只显示缩略图，点缩略图全屏预览。
 * 没有图片且处于查看态时整体隐藏，不留空白。
 */
public class BillImageStripView extends LinearLayout {

    public static final int TYPE_PHOTO = 0;
    public static final int TYPE_RECEIPT = 1;

    /** 与服务端 bills.js 的 MAX_BILL_IMAGES 保持一致 */
    public static final int MAX_IMAGES_PER_TYPE = 4;

    private static final String TAG = "BillImageStripView";

    public interface OnPickImageListener {
        void onPickImage(int type);
    }

    private final List<String> photos = new ArrayList<>();
    private final List<String> receipts = new ArrayList<>();
    private final List<String> pendingTypes = new ArrayList<>(); // 正在上传中的占位："photo"/"receipt"

    private boolean editMode = false;
    private OnPickImageListener pickListener;

    private TextView tvAddPhoto;
    private TextView tvAddReceipt;
    private LinearLayout thumbStrip;

    public BillImageStripView(Context context) {
        super(context);
        init();
    }

    public BillImageStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        // 「添加」磁贴与缩略图同尺寸，视觉上构成一排等大的格子
        LayoutParams tileParams = new LayoutParams(dp(52), dp(52));
        tileParams.rightMargin = dp(8);

        tvAddPhoto = buildAddTile("照片");
        tvAddPhoto.setLayoutParams(tileParams);
        tvAddPhoto.setOnClickListener(v -> requestPick(TYPE_PHOTO));
        addView(tvAddPhoto);

        tvAddReceipt = buildAddTile("小票");
        tvAddReceipt.setLayoutParams(tileParams);
        tvAddReceipt.setOnClickListener(v -> requestPick(TYPE_RECEIPT));
        addView(tvAddReceipt);

        HorizontalScrollView scrollView = new HorizontalScrollView(getContext());
        scrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        scrollView.setLayoutParams(scrollParams);

        thumbStrip = new LinearLayout(getContext());
        thumbStrip.setOrientation(HORIZONTAL);
        scrollView.addView(thumbStrip);
        addView(scrollView);
    }

    /**
     * 虚线边框的「+ 标签」添加磁贴：+ 号在上、文字在下，与缩略图等大。
     * 不用图标素材 —— 矢量图标的固有尺寸在代码里不可控，文字 + 号更干净；
     * 用 Spannable 让 + 号比标签大一号。
     */
    private TextView buildAddTile(String label) {
        TextView tile = new TextView(getContext());
        tile.setGravity(Gravity.CENTER);
        tile.setTextColor(0xFF999999);
        tile.setBackgroundResource(R.drawable.bg_bill_image_add_tile);
        tile.setLineSpacing(0, 1.0f);
        android.text.SpannableString span = new android.text.SpannableString("+\n" + label);
        span.setSpan(new android.text.style.AbsoluteSizeSpan(17, true), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new android.text.style.AbsoluteSizeSpan(9, true), 2, span.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new android.text.style.ForegroundColorSpan(0xFFAAAAAA), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tile.setText(span);
        return tile;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        refresh();
    }

    public void setOnPickImageListener(OnPickImageListener listener) {
        this.pickListener = listener;
    }

    /** 用已有图片渲染（元素为 /uploads/... 相对路径或 https 绝对地址，允许 null） */
    public void setImages(List<String> photos, List<String> receipts) {
        this.photos.clear();
        this.receipts.clear();
        if (photos != null) {
            for (String p : photos) {
                if (p != null && !p.trim().isEmpty()) this.photos.add(p.trim());
            }
        }
        if (receipts != null) {
            for (String r : receipts) {
                if (r != null && !r.trim().isEmpty()) this.receipts.add(r.trim());
            }
        }
        refresh();
    }

    public List<String> getPhotos() {
        return new ArrayList<>(photos);
    }

    public List<String> getReceipts() {
        return new ArrayList<>(receipts);
    }

    public boolean isUploading() {
        return !pendingTypes.isEmpty();
    }

    public void clear() {
        photos.clear();
        receipts.clear();
        pendingTypes.clear();
        refresh();
    }

    private void requestPick(int type) {
        List<String> target = type == TYPE_PHOTO ? photos : receipts;
        if (target.size() >= MAX_IMAGES_PER_TYPE) {
            Toast.makeText(getContext(), (type == TYPE_PHOTO ? "照片" : "小票") + "最多 " + MAX_IMAGES_PER_TYPE + " 张", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pickListener != null) {
            pickListener.onPickImage(type);
        }
    }

    /**
     * 宿主把系统选图结果交给这里：压缩 → 上传 → 追加缩略图。
     * 失败只 Toast，不打断记账流程。
     */
    public void onImagePicked(Uri imageUri, int type) {
        if (imageUri == null || getContext() == null) return;
        Context context = getContext();

        List<String> target = type == TYPE_PHOTO ? photos : receipts;
        if (target.size() >= MAX_IMAGES_PER_TYPE) return;

        String placeholderKind = type == TYPE_PHOTO ? "photo" : "receipt";
        pendingTypes.add(placeholderKind);
        final View placeholder = buildUploadingThumb(placeholderKind);
        refresh();

        byte[] compressed = ImageCompressor.compress(context, imageUri, 1080, 85);
        if (compressed == null) {
            pendingTypes.remove(placeholderKind);
            thumbStrip.removeView(placeholder);
            Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }

        String fileName = "bill_" + placeholderKind + "_" + System.currentTimeMillis() + ".jpg";
        AuthApiClient.uploadImage(context, new java.io.ByteArrayInputStream(compressed), fileName,
                new AuthApiClient.ImageUploadCallback() {
                    @Override
                    public void onSuccess(String imageUrl) {
                        post(() -> {
                            pendingTypes.remove(placeholderKind);
                            target.add(imageUrl);
                            refresh();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "账单图片上传失败: " + error);
                        post(() -> {
                            pendingTypes.remove(placeholderKind);
                            refresh();
                            Toast.makeText(context, "图片上传失败: " + error, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void refresh() {
        boolean hasAny = !photos.isEmpty() || !receipts.isEmpty() || !pendingTypes.isEmpty();

        tvAddPhoto.setVisibility(editMode ? VISIBLE : GONE);
        tvAddReceipt.setVisibility(editMode ? VISIBLE : GONE);

        // 容量满时把添加按钮置灰
        float enabledAlpha = photos.size() >= MAX_IMAGES_PER_TYPE ? 0.4f : 1f;
        tvAddPhoto.setAlpha(enabledAlpha);
        float enabledAlphaReceipt = receipts.size() >= MAX_IMAGES_PER_TYPE ? 0.4f : 1f;
        tvAddReceipt.setAlpha(enabledAlphaReceipt);

        thumbStrip.removeAllViews();
        for (String url : photos) {
            thumbStrip.addView(buildImageThumb(url, TYPE_PHOTO));
        }
        for (String url : receipts) {
            thumbStrip.addView(buildImageThumb(url, TYPE_RECEIPT));
        }
        for (String kind : pendingTypes) {
            thumbStrip.addView(buildUploadingThumb(kind));
        }

        setVisibility(hasAny || editMode ? VISIBLE : GONE);
    }

    private FrameLayout buildImageThumb(String url, int type) {
        Context context = getContext();
        FrameLayout frame = new FrameLayout(context);
        LinearLayout.LayoutParams params = new LayoutParams(dp(52), dp(52));
        params.rightMargin = dp(8);
        frame.setLayoutParams(params);

        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String absolute = BillUtils.absoluteImageUrl(context, url);
        Glide.with(context)
                .load(absolute != null ? absolute : url)
                .placeholder(R.drawable.image_placeholder_background)
                .transform(new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(6)))
                .into(imageView);
        frame.addView(imageView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView kindBadge = buildKindBadge(type);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        badgeParams.setMargins(dp(3), 0, 0, dp(3));
        frame.addView(kindBadge, badgeParams);

        imageView.setOnClickListener(v -> showPreview(url));
        if (editMode) {
            View remove = buildRemoveBadge(url, type);
            FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
            removeParams.setMargins(0, dp(3), dp(3), 0);
            frame.addView(remove, removeParams);
        }
        return frame;
    }

    private View buildUploadingThumb(String kind) {
        Context context = getContext();
        FrameLayout frame = new FrameLayout(context);
        LinearLayout.LayoutParams params = new LayoutParams(dp(52), dp(52));
        params.rightMargin = dp(8);
        frame.setLayoutParams(params);
        frame.setBackgroundResource(R.drawable.image_placeholder_background);
        frame.setAlpha(0.6f);

        ProgressBar bar = new ProgressBar(context);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        frame.addView(bar, barParams);

        TextView kindBadge = buildKindBadge("photo".equals(kind) ? TYPE_PHOTO : TYPE_RECEIPT);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        badgeParams.setMargins(dp(3), 0, 0, dp(3));
        frame.addView(kindBadge, badgeParams);
        return frame;
    }

    private TextView buildKindBadge(int type) {
        TextView badge = new TextView(getContext());
        badge.setText(type == TYPE_PHOTO ? "照" : "票");
        badge.setTextSize(8);
        badge.setTextColor(getResources().getColor(android.R.color.white));
        badge.setBackgroundResource(R.drawable.bg_bill_image_badge);
        badge.setPadding(dp(3), dp(1), dp(3), dp(1));
        return badge;
    }

    private View buildRemoveBadge(String url, int type) {
        TextView remove = new TextView(getContext());
        remove.setText("×");
        remove.setTextSize(12);
        remove.setTextColor(getResources().getColor(android.R.color.white));
        remove.setBackgroundResource(R.drawable.bg_bill_image_remove);
        remove.setGravity(Gravity.CENTER);
        remove.setOnClickListener(v -> {
            List<String> target = type == TYPE_PHOTO ? photos : receipts;
            target.remove(url);
            refresh();
        });
        return remove;
    }

    private void showPreview(String url) {
        Context context = getContext();
        String absolute = BillUtils.absoluteImageUrl(context, url);
        ImageView preview = new ImageView(context);
        preview.setAdjustViewBounds(true);
        int pad = dp(16);
        preview.setPadding(pad, pad, pad, pad);

        Dialog dialog = new Dialog(context);
        Glide.with(context).load(absolute != null ? absolute : url).placeholder(R.drawable.image_placeholder_background).into(preview);
        preview.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(preview);
        dialog.show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
