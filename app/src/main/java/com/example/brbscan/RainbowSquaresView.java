package com.example.brbscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class RainbowSquaresView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int numCols = 18;
    private int numRows = 2;
    private float squareSize;
    private float spacing = 0f;

    private int frame = 0;

    public RainbowSquaresView(Context context) {
        super(context);
    }

    public RainbowSquaresView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    private final Runnable animator = new Runnable() {
        @Override
        public void run() {
            frame++;
            invalidate();
            postDelayed(this, 80); // smoother on phones
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(animator);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(animator);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        squareSize = (getWidth() - (numCols + 1) * spacing) / (float) numCols;

        for (int row = 0; row < numRows; row++) {
            for (int col = 0; col < numCols; col++) {

                float hue = ((col * 360f / numCols) + frame) % 360;
                int color = android.graphics.Color.HSVToColor(255, new float[]{hue, 1f, 1f});

                double phase = frame * 0.15 - (col + row) * 0.5;
                int alpha = (int) ((Math.sin(phase) + 1) / 2 * (255 - 60) + 60);
                alpha = Math.min(255, Math.max(60, alpha));

                paint.setColor(color);
                paint.setAlpha(alpha);

                float left = spacing + col * (squareSize + spacing);
                float top = spacing + row * (squareSize + spacing);
                RectF rect = new RectF(left, top, left + squareSize, top + squareSize);

                canvas.drawRect(rect, paint);
            }
        }
    }
}
