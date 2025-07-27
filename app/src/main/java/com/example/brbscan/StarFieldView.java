package com.example.brbscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

public class StarFieldView extends View implements Choreographer.FrameCallback {

    private static class Star {
        float x, y;
        float speed;
        float radius;
    }

    private final Star[] stars;
    private final Paint paint;
    private final Random random = new Random();

    private static final int STAR_COUNT = 80;
    private static final int STAR_COLOR = 0xFFAA00FF; // Vibrant purple
    private static final long FIXED_TIMESTEP = 16; // ~60fps, in ms

    private long lastUpdateTime = 0L;

    public StarFieldView(Context context) {
        this(context, null);
    }

    public StarFieldView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StarFieldView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(STAR_COLOR);

        stars = new Star[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            stars[i] = createRandomStar();
        }
    }

    private Star createRandomStar() {
        Star star = new Star();
        star.x = random.nextFloat();
        star.y = random.nextFloat();
        star.radius = 0.5f + random.nextFloat() * 1.5f;
        star.speed = 0.0005f + random.nextFloat() * 0.001f; // speed per ms
        return star;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastUpdateTime = System.currentTimeMillis();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdateTime;

        // Fixed timestep loop to avoid spiral of death if lag spikes
        while (elapsed >= FIXED_TIMESTEP) {
            updateStars(FIXED_TIMESTEP);
            elapsed -= FIXED_TIMESTEP;
            lastUpdateTime += FIXED_TIMESTEP;
        }

        invalidate();

        // Schedule next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updateStars(long deltaMillis) {
        for (Star star : stars) {
            star.y += star.speed * deltaMillis;
            if (star.y > 1f) {
                star.y = 0f;
                star.x = random.nextFloat();
                star.radius = 0.5f + random.nextFloat() * 1.5f;
                star.speed = 0.0005f + random.nextFloat() * 0.001f;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();

        for (Star star : stars) {
            float cx = star.x * width;
            float cy = star.y * height;
            canvas.drawCircle(cx, cy, star.radius, paint);
        }
    }
}
