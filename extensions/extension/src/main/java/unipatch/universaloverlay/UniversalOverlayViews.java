package unipatch.universaloverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.RectF;

/** Shared view construction and styling primitives for the overlay controller. */
final class UniversalOverlayViews {
    private UniversalOverlayViews() { }
    static GradientDrawable background(int color, int stroke, boolean circle) {
        return background(color, stroke, circle, 1);
    }
    static GradientDrawable background(int color, int stroke, boolean circle, int strokeWidth) {
        return background(color, stroke, circle, strokeWidth, true);
    }
    static GradientDrawable background(int color, int stroke, boolean circle, int strokeWidth, boolean rounded) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(rounded ? (circle ? 1000f : 24f) : 0f);
        drawable.setStroke(Math.max(1, strokeWidth), stroke);
        return drawable;
    }

    /** Builds the legacy icon background while keeping its gradient and outline independent. */
    static Drawable gradientBackground(int first, int second, float angle, int stroke, int strokeWidth, boolean circle) {
        return new GradientBackground(first, second, angle, stroke, strokeWidth, circle, true);
    }

    /** Uses the overlay theme context so host-app selectable colors do not leak into our controls. */
    static Drawable selectableBackground(Context context) {
        android.content.res.TypedArray attributes = context.obtainStyledAttributes(
                new int[] { android.R.attr.selectableItemBackgroundBorderless });
        Drawable drawable = attributes.getDrawable(0);
        attributes.recycle();
        return drawable;
    }

    static Drawable solidOrGradientBackground(int first, int second, float angle, int stroke,
                                               int strokeWidth, boolean rounded, boolean gradient) {
        if (!gradient) return background(first, stroke, false, strokeWidth, rounded);
        return new GradientBackground(first, second, angle, stroke, strokeWidth, false, rounded);
    }

    static AnimatedOutline animatedOutline(int fillColor, int first, int second, boolean rainbow,
                                           int strokeWidth, boolean rounded, int speed) {
        return new AnimatedOutline(fillColor, first, second, rainbow, strokeWidth, rounded, speed);
    }

    static final class AnimatedOutline extends Drawable {
        private final int fillColor;
        private final int first;
        private final int second;
        private final boolean rainbow;
        private final int strokeWidth;
        private final boolean rounded;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ValueAnimator animator;
        private float phase;

        AnimatedOutline(int fillColor, int first, int second, boolean rainbow, int strokeWidth,
                        boolean rounded, int speed) {
            this.fillColor = fillColor;
            this.first = first;
            this.second = second;
            this.rainbow = rainbow;
            this.strokeWidth = Math.max(1, strokeWidth);
            this.rounded = rounded;
            fill.setColor(fillColor);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(this.strokeWidth);
            long duration = Math.max(250L, 5000L / Math.max(1, speed));
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.addUpdateListener(value -> {
                phase = (Float) value.getAnimatedValue();
                invalidateSelf();
            });
        }

        void start() { if (!animator.isStarted()) animator.start(); }
        void stop() { animator.cancel(); }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float radius = rounded ? 24f : 0f;
            fill.setShader(null);
            canvas.drawRoundRect(bounds, radius, radius, fill);
            int[] colors;
            float[] positions;
            if (rainbow) {
                colors = new int[] { 0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                        0xFF0000FF, 0xFFFF00FF, 0xFFFF0000 };
                positions = new float[] { 0f, .166f, .333f, .5f, .666f, .833f, 1f };
            } else {
                colors = new int[] { first, second, first };
                positions = new float[] { 0f, .5f, 1f };
            }
            float width = Math.max(1f, bounds.width());
            float shift = phase * width;
            border.setShader(new LinearGradient(-width + shift, 0f, width + shift, 0f,
                    colors, positions, Shader.TileMode.MIRROR));
            canvas.drawRoundRect(bounds, radius, radius, border);
        }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); border.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { fill.setColorFilter(filter); border.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class GradientBackground extends Drawable {
        private final int first;
        private final int second;
        private final float angle;
        private final int stroke;
        private final int strokeWidth;
        private final boolean circle;
        private final boolean rounded;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

        GradientBackground(int first, int second, float angle, int stroke, int strokeWidth, boolean circle, boolean rounded) {
            this.first = first;
            this.second = second;
            this.angle = angle;
            this.stroke = stroke;
            this.strokeWidth = Math.max(1, strokeWidth);
            this.circle = circle;
            this.rounded = rounded;
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(this.strokeWidth);
            border.setColor(stroke);
        }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float radians = (float) Math.toRadians(angle);
            float dx = (float) Math.sin(radians);
            float dy = (float) Math.cos(radians);
            float length = (float) Math.hypot(bounds.width(), bounds.height());
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            fill.setShader(new LinearGradient(
                    cx - dx * length / 2f, cy - dy * length / 2f,
                    cx + dx * length / 2f, cy + dy * length / 2f,
                    first, second, Shader.TileMode.CLAMP));
            float radius = rounded ? (circle ? Math.min(bounds.width(), bounds.height()) / 2f : 24f) : 0f;
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, border);
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            border.setAlpha(alpha);
            invalidateSelf();
        }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            fill.setColorFilter(filter);
            border.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
