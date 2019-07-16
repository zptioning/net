package com.zptioning.net.section01_animation;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.AnimationDrawable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.TextView;

import com.zptioning.net.R;

// Animation 常用方法

/**
 * Animation还有几个方法
 *
 * setFillAfter(boolean fillAfter)
 * 如果fillAfter的值为真的话，动画结束后，控件停留在执行后的状态
 *
 * setFillBefore(boolean fillBefore)
 * 如果fillBefore的值为真的话，动画结束后，控件停留在动画开始的状态
 *
 * setStartOffset(long startOffset)
 * 设置动画控件执行动画之前等待的时间
 *
 * setRepeatCount(int repeatCount)
 * 设置动画重复执行的次数
 */
// 加速器
/*
LinearInterpolator(匀速）
AccelerateInterpolator（先慢后快）
AccelerateDecelerateInterpolator（先慢中快后慢）
DecelerateInterpolator（先快后慢）
CycleInterpolator（循环播放，速度为正弦曲线）
AnticipateInterpolator（先回撤，再匀速向前）
OvershootInterpolator（超过，拉回）
BounceInterpolator(回弹）
 */
// 帧动画 即 Drawable Animation ，xml文件在 Drawable文件夹中
/*
    mButton.setBackgroundResource(R.drawable.animation_list);
    AnimationDrawable drawable = (AnimationDrawable)mButton.getBackground();
    drawable.start();
*/
public class AnimationActivity extends AppCompatActivity {


    private TextView mBtnSample;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_animation);
        mBtnSample = (TextView) findViewById(R.id.btn_sample);
        setClickListener();
        setClickListenerPropertyAnimation();
    }


    /**
     * 补间动画
     */
    private void setClickListener() {
        //mBtnSample.setVisibility(View.INVISIBLE);
        Button btnTranslation = (Button) findViewById(R.id.translate);
        btnTranslation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                TranslateAnimation translateAnimation = new TranslateAnimation(1, 100, 1, 100);
                translateAnimation.setDuration(2000);
                mBtnSample.startAnimation(translateAnimation);
            }
        });
        Button btnScale = (Button) findViewById(R.id.scale);
        btnScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ScaleAnimation scaleAnimation = new ScaleAnimation(0.5f, 1, 0.5f, 1
                        , Animation.RELATIVE_TO_SELF, 0.5f
                        , Animation.RELATIVE_TO_SELF, 0.5f);
                scaleAnimation.setDuration(2000);
                mBtnSample.startAnimation(scaleAnimation);
            }
        });
        Button btnAlpha = (Button) findViewById(R.id.alpha);
        btnAlpha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AlphaAnimation alphaAnimation = new AlphaAnimation(0, 1);
                alphaAnimation.setDuration(2000);
                mBtnSample.startAnimation(alphaAnimation);
                //mBtnSample.setvisibility(View.VISIBLE);
            }
        });
        Button btnRotate = (Button) findViewById(R.id.rotate);
        btnRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                RotateAnimation rotateAnimation = new RotateAnimation(0, 540
                        , Animation.RELATIVE_TO_SELF, 0.5f
                        , Animation.RELATIVE_TO_SELF, 0.5f);
                rotateAnimation.setFillAfter(true);
                rotateAnimation.setInterpolator(new DecelerateInterpolator());
                rotateAnimation.setDuration(2000);
                mBtnSample.startAnimation(rotateAnimation);
            }
        });
        Button btnSet = (Button) findViewById(R.id.set);
        btnSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                AnimationSet animationSet = new AnimationSet(true);
                AnimationDrawable anim = new AnimationDrawable();
                /* 旋转动画 */
                RotateAnimation rotateAnimation = new RotateAnimation(0, 540
                        , Animation.RELATIVE_TO_SELF, 0.5f
                        , Animation.RELATIVE_TO_SELF, 0.5f);
                // 动画结束后 固定
                rotateAnimation.setFillAfter(true);
                // 加速
                rotateAnimation.setInterpolator(new DecelerateInterpolator());
                // 持续时间
                rotateAnimation.setDuration(2000);
                /* 平移动画 */
                TranslateAnimation translateAnimation = new TranslateAnimation(1, 100, 1, 100);
                translateAnimation.setDuration(2000);
                // Z轴位置
                animationSet.setZAdjustment(Animation.ZORDER_BOTTOM);
                // 添加监听
                animationSet.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {

                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });

                animationSet.addAnimation(rotateAnimation);
                animationSet.addAnimation(translateAnimation);
                mBtnSample.startAnimation(animationSet);
            }
        });
        Button btnXML = (Button) findViewById(R.id.xml);
        btnXML.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animation animation = AnimationUtils.loadAnimation(AnimationActivity.this, R.anim.scale_animation);
                mBtnSample.startAnimation(animation);
            }
        });
    }

    /**
     * 属性动画
     */
    private void setClickListenerPropertyAnimation() {
        // 平移
        Button btnTranslation = (Button) findViewById(R.id.translate1);
        btnTranslation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                float curTranslationX = mBtnSample.getTranslationX();
//                ObjectAnimator animator = ObjectAnimator.ofFloat(mBtnSample,
//                    "translationX",
//                    curTranslationX,
//                    -500f,
//                    curTranslationX);
//                animator.setDuration(5000);
//                animator.start();
//                // 插值器  先快后慢
//                animator.setInterpolator(new DecelerateInterpolator(2f));
                /******************************************
                 *          ViewPropertyAnimator          *
                 ******************************************/
                mBtnSample.animate().x(500).y(500).setDuration(5000)
                        .setInterpolator(new BounceInterpolator());
            }
        });
        // 缩放
        Button btnScale = (Button) findViewById(R.id.scale1);
        btnScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(mBtnSample,
                        "scaleY", 1f, 3f, 1f);
                animator.setDuration(5000);
                animator.start();
            }
        });
        // 透明
        Button btnAlpha = (Button) findViewById(R.id.alpha1);
        btnAlpha.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(mBtnSample,
                        "alpha", 1f, 0f, 1f);
                animator.setDuration(1000);
                animator.start();
            }
        });
        // 旋转
        Button btnRotate = (Button) findViewById(R.id.rotate1);
        btnRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(mBtnSample,
                        "rotation", 0f, 360f);
                animator.setDuration(5000);
                animator.start();
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                    }
                });
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {

                    }
                });
            }
        });
        // 动画集
        Button btnSet = (Button) findViewById(R.id.set1);
        btnSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                ObjectAnimator translate = ObjectAnimator.ofFloat(mBtnSample,
                        "translationX", 0f, -500f, 0f);
                ObjectAnimator rotate = ObjectAnimator.ofFloat(mBtnSample,
                        "rotation", 0f, 360f);
                ObjectAnimator alpha = ObjectAnimator.ofFloat(mBtnSample,
                        "alpha", 1f, 0f, 1f);
                ObjectAnimator sacle = ObjectAnimator.ofFloat(mBtnSample,
                        "scaleY", 1f, 3f, 1f);
                AnimatorSet animSet = new AnimatorSet();
                // 延迟1s --> 平移 --> 旋转 + 透明度 --> 缩放
                animSet.play(rotate).with(alpha).after(translate).before(sacle).after(1000);
                animSet.setDuration(5000);
                animSet.start();
                animSet.addListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                    }
                    @Override
                    public void onAnimationEnd(Animator animation) {
                    }
                    @Override
                    public void onAnimationCancel(Animator animation) {
                    }
                    @Override
                    public void onAnimationRepeat(Animator animation) {
                    }
                });
            }
        });
        // xml 文件
        Button btnXML = (Button) findViewById(R.id.xml1);
        btnXML.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Animator animator = AnimatorInflater.loadAnimator(AnimationActivity.this,
                        R.animator.animation_set);
                animator.setTarget(mBtnSample);
                animator.start();
//                mBtnSample.animate().rotationX(360).setDuration(1000).start();
            }
        });

        /* 绕轴旋转 */
        Button btnRotate2 = (Button) findViewById(R.id.rotate2);
        btnRotate2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mTvCenter, View.ALPHA, 1f, 0f, 1f);
//                ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mTvCenter, View.ROTATION_Y, 0, -360);
//                ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mTvCenter, View.TRANSLATION_X, 0, 360);
//                ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mTvCenter, View.SCALE_X, 1f, 3f, 1f);
                ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mBtnSample, View.ROTATION_Y, 0, -360);

//                objectAnimator.setInterpolator(new DecelerateInterpolator(2f));
//                mBtnSample.setPivotX(0f);
//                int measuredHeight = mBtnSample.getMeasuredHeight();
//                mBtnSample.setPivotY(measuredHeight);

                objectAnimator.setDuration(2000);
                objectAnimator.start();
            }
        });
    }

    /**
     * 例子
     */
    private void sample() {
        Point point1 = new Point(0, 0);
        Point point2 = new Point(300, 300);
        final ValueAnimator anim =
                ValueAnimator.ofObject(new PointEvaluator(), point1, point2);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                Point tempPoint = (Point) animation.getAnimatedValue();
                Log.e("zption", "point:" + tempPoint.getX() + "y:" + tempPoint.getY());
            }
        });
    }


    /**
     * 类型估值器
     */
    public class PointEvaluator implements TypeEvaluator {
        @Override
        public Object evaluate(float fraction, Object startValue, Object endValue) {
            Point startPoint = (Point) startValue;
            Point endPoint = (Point) endValue;
            float x = startPoint.getX() + fraction * (endPoint.getX() - startPoint.getX());
            float y = startPoint.getY() + fraction * (endPoint.getY() - startPoint.getY());
            Point point = new Point(x, y);
            return point;
        }
    }

    public class Point {
        private float x;
        private float y;

        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }
    }
}
