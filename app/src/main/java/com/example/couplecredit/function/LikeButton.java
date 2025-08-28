package com.example.couplecredit.function;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.example.couplecredit.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LikeButton extends RelativeLayout {
    private ImageView heartIcon;
    private boolean isLiked = false;
    private OnLikeClickListener onLikeClickListener;
    private List<ImageView> floatingHearts;
    private Random random;

    public interface OnLikeClickListener {
        void onLikeClick(boolean isLiked);
    }

    public LikeButton(Context context) {
        super(context);
        init();
    }

    public LikeButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LikeButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        floatingHearts = new ArrayList<>();
        random = new Random();
        
        // 创建心形图标
        heartIcon = new ImageView(getContext());
        heartIcon.setImageResource(R.drawable.ic_heart_empty);
        
        LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        params.addRule(RelativeLayout.CENTER_IN_PARENT);
        heartIcon.setLayoutParams(params);
        
        addView(heartIcon);
        
        // 设置点击事件
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLike();
            }
        });
    }

    private void toggleLike() {
        isLiked = !isLiked;
        
        if (isLiked) {
            performLikeAnimation();
        } else {
            performUnlikeAnimation();
        }
        
        if (onLikeClickListener != null) {
            onLikeClickListener.onLikeClick(isLiked);
        }
    }

    private void performLikeAnimation() {
        // 切换到实心图标
        heartIcon.setImageResource(R.drawable.ic_heart_filled);
        
        // 主心形缩放动画
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heartIcon, "scaleX", 1.0f, 1.3f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heartIcon, "scaleY", 1.0f, 1.3f, 1.0f);
        
        AnimatorSet scaleSet = new AnimatorSet();
        scaleSet.playTogether(scaleX, scaleY);
        scaleSet.setDuration(500);
        scaleSet.setInterpolator(new OvershootInterpolator());
        
        // 旋转动画
        ObjectAnimator rotation = ObjectAnimator.ofFloat(heartIcon, "rotation", 0f, 360f);
        rotation.setDuration(500);
        
        // 组合动画
        AnimatorSet mainAnimSet = new AnimatorSet();
        mainAnimSet.playTogether(scaleSet, rotation);
        mainAnimSet.start();
        
        // 创建飘散的小心形
        createFloatingHearts();
    }

    private void performUnlikeAnimation() {
        // 切换到空心图标
        heartIcon.setImageResource(R.drawable.ic_heart_empty);
        
        // 简单的缩放动画
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heartIcon, "scaleX", 1.0f, 0.8f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heartIcon, "scaleY", 1.0f, 0.8f, 1.0f);
        
        AnimatorSet scaleSet = new AnimatorSet();
        scaleSet.playTogether(scaleX, scaleY);
        scaleSet.setDuration(300);
        scaleSet.start();
    }

    private void createFloatingHearts() {
        int[] colors = {Color.parseColor("#FF69B4"), Color.parseColor("#FF1493"), 
                       Color.parseColor("#FF6347"), Color.parseColor("#FF4500"),
                       Color.parseColor("#FFB6C1"), Color.parseColor("#FFC0CB")};
        
        for (int i = 0; i < 6; i++) {
            ImageView floatingHeart = new ImageView(getContext());
            floatingHeart.setImageResource(R.drawable.ic_heart_filled);
            floatingHeart.setColorFilter(colors[i % colors.length]);
            
            LayoutParams params = new LayoutParams(60, 60);
            params.addRule(RelativeLayout.CENTER_IN_PARENT);
            floatingHeart.setLayoutParams(params);
            
            addView(floatingHeart);
            floatingHearts.add(floatingHeart);
            
            animateFloatingHeart(floatingHeart, i);
        }
    }

    private void animateFloatingHeart(ImageView heart, int index) {
        // 随机方向和距离
        float angle = (float) (Math.PI * 2 * index / 6); // 均匀分布
        float distance = 100 + random.nextFloat() * 50;
        float endX = (float) (Math.cos(angle) * distance);
        float endY = (float) (Math.sin(angle) * distance);
        
        // 移动动画
        ObjectAnimator moveX = ObjectAnimator.ofFloat(heart, "translationX", 0f, endX);
        ObjectAnimator moveY = ObjectAnimator.ofFloat(heart, "translationY", 0f, endY);
        
        // 缩放动画
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heart, "scaleX", 0.5f, 1.0f, 0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heart, "scaleY", 0.5f, 1.0f, 0f);
        
        // 透明度动画
        ObjectAnimator alpha = ObjectAnimator.ofFloat(heart, "alpha", 1.0f, 0f);
        
        // 旋转动画
        ObjectAnimator rotation = ObjectAnimator.ofFloat(heart, "rotation", 0f, 360f);
        
        AnimatorSet animSet = new AnimatorSet();
        animSet.playTogether(moveX, moveY, scaleX, scaleY, alpha, rotation);
        animSet.setDuration(1000 + random.nextInt(500));
        animSet.setStartDelay(index * 50);
        
        animSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                removeView(heart);
                floatingHearts.remove(heart);
            }
        });
        
        animSet.start();
    }

    public void setOnLikeClickListener(OnLikeClickListener listener) {
        this.onLikeClickListener = listener;
    }

    public boolean isLiked() {
        return isLiked;
    }

    public void setLiked(boolean liked) {
        this.isLiked = liked;
        if (liked) {
            heartIcon.setImageResource(R.drawable.ic_heart_filled);
        } else {
            heartIcon.setImageResource(R.drawable.ic_heart_empty);
        }
    }
}