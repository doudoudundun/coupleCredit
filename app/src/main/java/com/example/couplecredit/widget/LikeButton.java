package com.example.couplecredit.widget;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
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
    private List<AnimatorSet> runningAnimators; // 跟踪正在运行的动画
    private Random random;
    private boolean isAnimating = false;

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
        runningAnimators = new ArrayList<>();
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
                if (!isAnimating) {
                    toggleLike();
                }
            }
        });
    }

    private void toggleLike() {
        // 防止动画期间重复点击
        if (isAnimating) {
            return;
        }
        
        isLiked = !isLiked;
        
        if (isLiked) {
            performEnhancedLikeAnimation();
        } else {
            performEnhancedUnlikeAnimation();
        }
        
        if (onLikeClickListener != null) {
            onLikeClickListener.onLikeClick(isLiked);
        }
    }

    private void performEnhancedLikeAnimation() {
        isAnimating = true;
        
        // 阶段1：快速缩小准备
        ObjectAnimator shrinkX = ObjectAnimator.ofFloat(heartIcon, "scaleX", 1.0f, 0.7f);
        ObjectAnimator shrinkY = ObjectAnimator.ofFloat(heartIcon, "scaleY", 1.0f, 0.7f);
        
        AnimatorSet shrinkSet = new AnimatorSet();
        shrinkSet.playTogether(shrinkX, shrinkY);
        shrinkSet.setDuration(100);
        shrinkSet.setInterpolator(new AccelerateDecelerateInterpolator());
        
        shrinkSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 阶段2：切换图标并爆炸式放大
                heartIcon.setImageResource(R.drawable.ic_heart_filled);
                performExplosionAnimation();
            }
        });
        
        shrinkSet.start();
    }
    
    private void performExplosionAnimation() {
        // 爆炸式放大动画
        ObjectAnimator explodeX = ObjectAnimator.ofFloat(heartIcon, "scaleX", 0.7f, 1.6f, 1.0f);
        ObjectAnimator explodeY = ObjectAnimator.ofFloat(heartIcon, "scaleY", 0.7f, 1.6f, 1.0f);
        
        // 旋转动画
        ObjectAnimator rotation = ObjectAnimator.ofFloat(heartIcon, "rotation", 0f, 360f);
        
        // 颜色闪烁效果（通过alpha实现）
        ValueAnimator colorFlash = ValueAnimator.ofFloat(0.6f, 1.0f, 0.8f, 1.0f);
        colorFlash.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float alpha = (Float) animation.getAnimatedValue();
                heartIcon.setAlpha(alpha);
            }
        });
        
        AnimatorSet explodeSet = new AnimatorSet();
        explodeSet.playTogether(explodeX, explodeY, rotation, colorFlash);
        explodeSet.setDuration(600);
        explodeSet.setInterpolator(new BounceInterpolator());
        
        explodeSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                heartIcon.setAlpha(1.0f);
                isAnimating = false;
            }
        });
        
        explodeSet.start();
        
        // 同时创建增强版飘散效果
        createEnhancedFloatingHearts();
    }

    private void performEnhancedUnlikeAnimation() {
        isAnimating = true;
        
        // 反向动画：先旋转缩小
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heartIcon, "scaleX", 1.0f, 0.3f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heartIcon, "scaleY", 1.0f, 0.3f, 1.0f);
        ObjectAnimator rotation = ObjectAnimator.ofFloat(heartIcon, "rotation", 0f, -180f, 0f);
        
        // 透明度变化
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(1.0f, 0.3f, 1.0f);
        alphaAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float alpha = (Float) animation.getAnimatedValue();
                heartIcon.setAlpha(alpha);
            }
        });
        
        AnimatorSet unlikeSet = new AnimatorSet();
        unlikeSet.playTogether(scaleX, scaleY, rotation, alphaAnim);
        unlikeSet.setDuration(400);
        unlikeSet.setInterpolator(new OvershootInterpolator());
        
        unlikeSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                // 在动画中途切换图标
                heartIcon.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        heartIcon.setImageResource(R.drawable.ic_heart_empty);
                    }
                }, 200);
            }
            
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                heartIcon.setAlpha(1.0f);
                isAnimating = false;
            }
        });
        
        unlikeSet.start();
    }

    private void createEnhancedFloatingHearts() {
        // 更丰富的颜色组合
        int[] colors = {
            Color.parseColor("#FF69B4"), // 热粉色
            Color.parseColor("#FF1493"), // 深粉色
            Color.parseColor("#FF6347"), // 番茄红
            Color.parseColor("#FF4500"), // 橙红色
            Color.parseColor("#FFB6C1"), // 浅粉色
            Color.parseColor("#FFC0CB"), // 粉色
            Color.parseColor("#FF69B4"), // 热粉色
            Color.parseColor("#FF20B2")  // 亮粉色
        };
        
        // 创建更多的飘散心形
        for (int i = 0; i < 8; i++) {
            ImageView floatingHeart = new ImageView(getContext());
            floatingHeart.setImageResource(R.drawable.ic_heart_filled);
            floatingHeart.setColorFilter(colors[i % colors.length]);
            
            // 随机大小
            int size = 40 + random.nextInt(30);
            LayoutParams params = new LayoutParams(size, size);
            params.addRule(RelativeLayout.CENTER_IN_PARENT);
            floatingHeart.setLayoutParams(params);
            
            addView(floatingHeart);
            floatingHearts.add(floatingHeart);
            
            animateEnhancedFloatingHeart(floatingHeart, i);
        }
    }

    private void animateEnhancedFloatingHeart(ImageView heart, int index) {
        // 更自然的分布角度
        float baseAngle = (float) (Math.PI * 2 * index / 8);
        float angleVariation = (random.nextFloat() - 0.5f) * 0.5f;
        float angle = baseAngle + angleVariation;
        
        // 随机距离和轨迹
        float distance = 120 + random.nextFloat() * 80;
        float endX = (float) (Math.cos(angle) * distance);
        float endY = (float) (Math.sin(angle) * distance) - 20; // 稍微向上偏移
        
        // 曲线路径动画
        float midX = endX * 0.5f + (random.nextFloat() - 0.5f) * 40;
        float midY = endY * 0.3f - 30;
        
        // 分阶段移动动画
        ObjectAnimator moveX1 = ObjectAnimator.ofFloat(heart, "translationX", 0f, midX);
        ObjectAnimator moveY1 = ObjectAnimator.ofFloat(heart, "translationY", 0f, midY);
        ObjectAnimator moveX2 = ObjectAnimator.ofFloat(heart, "translationX", midX, endX);
        ObjectAnimator moveY2 = ObjectAnimator.ofFloat(heart, "translationY", midY, endY);
        
        // 复杂的缩放动画
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heart, "scaleX", 0.3f, 1.2f, 0.8f, 0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heart, "scaleY", 0.3f, 1.2f, 0.8f, 0f);
        
        // 透明度动画
        ObjectAnimator alpha = ObjectAnimator.ofFloat(heart, "alpha", 0f, 1.0f, 0.8f, 0f);
        
        // 旋转动画
        ObjectAnimator rotation = ObjectAnimator.ofFloat(heart, "rotation", 0f, 720f + random.nextFloat() * 360f);
        
        // 第一阶段动画
        AnimatorSet firstStage = new AnimatorSet();
        firstStage.playTogether(moveX1, moveY1);
        firstStage.setDuration(400);
        firstStage.setInterpolator(new AccelerateDecelerateInterpolator());
        
        // 第二阶段动画
        AnimatorSet secondStage = new AnimatorSet();
        secondStage.playTogether(moveX2, moveY2);
        secondStage.setDuration(600);
        secondStage.setInterpolator(new AccelerateDecelerateInterpolator());
        
        // 整体效果动画
        AnimatorSet effectSet = new AnimatorSet();
        effectSet.playTogether(scaleX, scaleY, alpha, rotation);
        effectSet.setDuration(1000);
        
        // 组合所有动画
        AnimatorSet finalSet = new AnimatorSet();
        finalSet.playSequentially(firstStage, secondStage);
        finalSet.playTogether(effectSet);
        finalSet.setStartDelay(index * 60 + random.nextInt(100));
        
        finalSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                removeView(heart);
                floatingHearts.remove(heart);
                runningAnimators.remove(finalSet);
            }
        });
        
        runningAnimators.add(finalSet);
        finalSet.start();
    }

    public void setOnLikeClickListener(OnLikeClickListener listener) {
        this.onLikeClickListener = listener;
    }

    public boolean isLiked() {
        return isLiked;
    }

    public void setLiked(boolean liked) {
        // 如果状态相同，直接返回
        if (this.isLiked == liked) return;
        
        // 如果正在动画中，直接返回，不进行状态设置
        if (isAnimating) {
            return;
        }
        
        // 防止在布局过程中进行状态更改
        if (heartIcon == null) {
            return;
        }
        
        try {
            this.isLiked = liked;
            if (liked) {
                heartIcon.setImageResource(R.drawable.ic_heart_filled);
            } else {
                heartIcon.setImageResource(R.drawable.ic_heart_empty);
            }
        } catch (Exception e) {
            // 防止布局异常导致崩溃
            e.printStackTrace();
        }
    }
    
    /**
     * 强制重置LikeButton状态，停止所有动画
     * 用于ViewHolder复用时清理状态
     */
    public void forceReset() {
        try {
            // 停止所有AnimatorSet动画
            for (AnimatorSet animator : runningAnimators) {
                if (animator != null && animator.isRunning()) {
                    animator.cancel();
                }
            }
            runningAnimators.clear();
            
            // 停止所有动画
            clearAnimation();
            if (heartIcon != null) {
                heartIcon.clearAnimation();
            }
            
            // 停止所有子视图的动画
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.clearAnimation();
                if (child.animate() != null) {
                    child.animate().cancel();
                }
            }
            
            // 清理飘散的心形 - 使用副本避免ConcurrentModificationException
            List<ImageView> heartsToRemove = new ArrayList<>(floatingHearts);
            for (ImageView heart : heartsToRemove) {
                if (heart.getParent() == this) {
                    // 停止心形的动画
                    heart.clearAnimation();
                    if (heart.animate() != null) {
                        heart.animate().cancel();
                    }
                    removeView(heart);
                }
            }
            floatingHearts.clear();
            
            // 重置状态
            isAnimating = false;
            if (heartIcon != null) {
                heartIcon.setScaleX(1.0f);
                heartIcon.setScaleY(1.0f);
                heartIcon.setRotation(0f);
                heartIcon.setAlpha(1.0f);
            }
            
            // 清除点击监听器，防止状态混乱
            setOnLikeClickListener(null);
        } catch (Exception e) {
            // 防止布局异常导致崩溃
            e.printStackTrace();
        }
    }
}