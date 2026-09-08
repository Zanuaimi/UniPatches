package unipatch.universaloverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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

    static Drawable icon(int background1, int background2, float backgroundAngle, boolean backgroundGradient,
                         int outline1, int outline2, float outlineAngle, boolean outlineGradient,
                         int outlineWidth, boolean circle, String style, String shape,
                         int shapeColor1, int shapeColor2, boolean shapeGradient, float shapeAngle,
                         float shapeStrokeWidth, float shapeScale, boolean highlight, boolean shadow,
                         String backgroundStyle, int backgroundColor3, int backgroundColor4) {
        return new IconDrawable(background1, background2, backgroundAngle, backgroundGradient,
                outline1, outline2, outlineAngle, outlineGradient, outlineWidth, circle,
                style, shape, shapeColor1, shapeColor2, shapeGradient, shapeAngle,
                shapeStrokeWidth, shapeScale, highlight, shadow, backgroundStyle, backgroundColor3, backgroundColor4);
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

    static AnimatedOutline animatedOutline(int fillColor, int first, int second, boolean vertical, boolean rainbow,
                                           int strokeWidth, boolean rounded, int speed) {
        return new AnimatedOutline(fillColor, first, second, vertical, rainbow, strokeWidth, rounded, speed);
    }

    static final class AnimatedOutline extends Drawable {
        private final int fillColor;
        private final int first;
        private final int second;
        private final boolean vertical;
        private final boolean rainbow;
        private final int direction;
        private final int strokeWidth;
        private final boolean rounded;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors;
        private final float[] positions;
        private final android.animation.ValueAnimator animator;
        private float phase;

        AnimatedOutline(int fillColor, int first, int second, boolean vertical, boolean rainbow, int strokeWidth,
                        boolean rounded, int speed) {
            this.fillColor = fillColor;
            this.first = first;
            this.second = second;
            this.vertical = vertical;
            this.rainbow = rainbow;
            this.direction = speed < 0 ? -1 : 1;
            this.strokeWidth = Math.max(1, strokeWidth);
            this.rounded = rounded;
            fill.setColor(fillColor);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(this.strokeWidth);
            if (rainbow) {
                colors = new int[] { 0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                        0xFF0000FF, 0xFFFF00FF, 0xFFFF0000 };
                positions = new float[] { 0f, .166f, .333f, .5f, .666f, .833f, 1f };
            } else {
                colors = new int[] { first, second, first };
                positions = new float[] { 0f, .5f, 1f };
            }
            long duration = Math.max(250L, 5000L / Math.max(1, Math.abs(speed)));
            // Two shader periods make the repeat seam land on an identical gradient phase.
            // REPEAT keeps the color order stable at the loop boundary; MIRROR would reverse
            // the order and can visibly jump, especially with the rainbow palette.
            animator = android.animation.ValueAnimator.ofFloat(0f, 2f);
            animator.setDuration(duration);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(value -> {
                phase = (Float) value.getAnimatedValue();
                invalidateSelf();
            });
        }

        void start() { if (!animator.isRunning()) animator.start(); }
        void stop() { if (animator.isRunning()) animator.cancel(); }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float inset = strokeWidth / 2f;
            bounds.inset(inset, inset);
            float radius = rounded ? Math.max(0f, 24f - inset) : 0f;
            fill.setShader(null);
            canvas.drawRoundRect(bounds, radius, radius, fill);
            float width = Math.max(1f, bounds.width());
            float height = Math.max(1f, bounds.height());
            float shift = phase * (vertical ? height : width) * direction;
            border.setShader(vertical
                    ? new LinearGradient(0f, -height + shift, 0f, height + shift,
                    colors, positions, Shader.TileMode.REPEAT)
                    : new LinearGradient(-width + shift, 0f, width + shift, 0f,
                    colors, positions, Shader.TileMode.REPEAT));
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

    /** Original vector-like legacy icon renderer used by configurable icon styles. */
    private static final class IconDrawable extends Drawable {
        private final int background1, background2, outline1, outline2, shapeColor1, shapeColor2;
        private final float backgroundAngle, outlineAngle, shapeAngle, shapeStrokeWidth, shapeScale;
        private final boolean backgroundGradient, outlineGradient, circle, shapeGradient, highlight, shadow;
        private final String style, shape, backgroundStyle;
        private final int backgroundColor3, backgroundColor4;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable(int background1, int background2, float backgroundAngle, boolean backgroundGradient,
                     int outline1, int outline2, float outlineAngle, boolean outlineGradient,
                     int outlineWidth, boolean circle, String style, String shape,
                     int shapeColor1, int shapeColor2, boolean shapeGradient, float shapeAngle,
                     float shapeStrokeWidth, float shapeScale, boolean highlight, boolean shadow,
                     String backgroundStyle, int backgroundColor3, int backgroundColor4) {
            this.background1 = background1;
            this.background2 = background2;
            this.backgroundAngle = backgroundAngle;
            this.backgroundGradient = backgroundGradient;
            this.outline1 = outline1;
            this.outline2 = outline2;
            this.outlineAngle = outlineAngle;
            this.outlineGradient = outlineGradient;
            this.shapeColor1 = shapeColor1;
            this.shapeColor2 = shapeColor2;
            this.shapeGradient = shapeGradient;
            this.shapeAngle = shapeAngle;
            this.shapeStrokeWidth = Math.max(1f, shapeStrokeWidth);
            this.shapeScale = Math.max(.2f, Math.min(1f, shapeScale));
            this.highlight = highlight;
            this.shadow = shadow;
            this.backgroundStyle = backgroundStyle == null ? "flat" : backgroundStyle;
            this.backgroundColor3 = backgroundColor3;
            this.backgroundColor4 = backgroundColor4;
            this.outlineWidth = Math.max(0, outlineWidth);
            this.circle = circle;
            this.style = style == null ? "shape" : style;
            this.shape = shape == null ? "triangle" : shape;
        }

        private final int outlineWidth;

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float halfOutline = outlineWidth / 2f;
            RectF body = new RectF(bounds.left + halfOutline, bounds.top + halfOutline,
                    bounds.right - halfOutline, bounds.bottom - halfOutline);
            float radius = circle ? Math.min(body.width(), body.height()) / 2f : 24f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(backgroundGradient ? linear(background1, background2, backgroundAngle, body) : null);
            paint.setColor(background1);
            canvas.drawRoundRect(body, radius, radius, paint);
            if ("faceted".equals(backgroundStyle)) drawFacets(canvas, body, radius);

            if (outlineWidth > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(outlineWidth);
                paint.setShader(outlineGradient ? linear(outline1, outline2, outlineAngle, body) : null);
                paint.setColor(outline1);
                canvas.drawRoundRect(body, radius, radius, paint);
            }

            if (!"text".equals(style)) drawShape(canvas, body);
            if (highlight && !"text".equals(style)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(0x55FFFFFF);
                RectF shine = new RectF(body.left + body.width() * .18f, body.top + body.height() * .12f,
                        body.left + body.width() * .62f, body.top + body.height() * .33f);
                canvas.drawOval(shine, paint);
            }
        }

        private void drawFacets(Canvas canvas, RectF body, float radius) {
            Path clip = new Path();
            clip.addRoundRect(body, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            float cx = body.centerX();
            float cy = body.centerY();
            Path facet = new Path();
            paint.setColor(backgroundColor3);
            facet.moveTo(body.left, body.top);
            facet.lineTo(cx, body.top);
            facet.lineTo(body.left, cy);
            facet.close();
            canvas.drawPath(facet, paint);
            facet.reset();
            paint.setColor(backgroundColor4);
            facet.moveTo(cx, body.top);
            facet.lineTo(body.right, body.top);
            facet.lineTo(body.right, cy * .72f + body.top * .28f);
            facet.lineTo(cx, cy);
            facet.close();
            canvas.drawPath(facet, paint);
            facet.reset();
            paint.setColor(background2);
            facet.moveTo(body.left, cy);
            facet.lineTo(cx, cy);
            facet.lineTo(body.left, body.bottom);
            facet.close();
            canvas.drawPath(facet, paint);
            facet.reset();
            paint.setColor(backgroundColor3);
            facet.moveTo(cx, cy);
            facet.lineTo(body.right, cy * .72f + body.top * .28f);
            facet.lineTo(body.right, body.bottom);
            facet.lineTo(cx, body.bottom);
            facet.close();
            canvas.drawPath(facet, paint);
            facet.reset();
            paint.setColor(backgroundColor4);
            facet.moveTo(cx, cy);
            facet.lineTo(cx, body.bottom);
            facet.lineTo(body.left + body.width() * .42f, body.bottom);
            facet.close();
            canvas.drawPath(facet, paint);
            canvas.restoreToCount(save);
        }

        private void drawShape(Canvas canvas, RectF body) {
            float size = Math.min(body.width(), body.height()) * shapeScale;
            float left = body.centerX() - size / 2f;
            float top = body.centerY() - size / 2f;
            RectF area = new RectF(left, top, left + size, top + size);
            paint.setShader(shapeGradient ? linear(shapeColor1, shapeColor2, shapeAngle, area) : null);
            paint.setColor(shapeColor1);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if ("smile".equals(shape)) {
                paint.setStyle(Paint.Style.FILL);
                if (shadow) {
                    paint.setShader(null);
                    paint.setColor(0x66000000);
                    canvas.drawOval(new RectF(area.left + size * .22f, area.top + size * .22f,
                            area.left + size * .35f, area.top + size * .48f), paint);
                    canvas.drawOval(new RectF(area.left + size * .65f, area.top + size * .22f,
                            area.left + size * .78f, area.top + size * .48f), paint);
                }
                paint.setShader(null);
                paint.setColor(shapeColor1);
                canvas.drawOval(new RectF(area.left + size * .20f, area.top + size * .18f,
                        area.left + size * .34f, area.top + size * .46f), paint);
                canvas.drawOval(new RectF(area.left + size * .66f, area.top + size * .18f,
                        area.left + size * .80f, area.top + size * .46f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(shapeStrokeWidth);
                paint.setColor(shapeColor1);
                canvas.drawArc(new RectF(area.left + size * .22f, area.top + size * .25f,
                        area.right - size * .22f, area.bottom - size * .10f), 20f, 140f, false, paint);
                return;
            }

            if ("circle".equals(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(shapeStrokeWidth);
                canvas.drawOval(new RectF(area.left + shapeStrokeWidth, area.top + shapeStrokeWidth,
                        area.right - shapeStrokeWidth, area.bottom - shapeStrokeWidth), paint);
                return;
            }

            if ("z".equals(shape)) {
                paint.setStyle(Paint.Style.FILL);
                Path z = new Path();
                float stroke = size * .18f;
                z.moveTo(area.left + size * .14f, area.top + size * .16f);
                z.lineTo(area.right - size * .14f, area.top + size * .16f);
                z.lineTo(area.left + size * .36f, area.bottom - size * .16f);
                z.lineTo(area.right - size * .14f, area.bottom - size * .16f);
                z.lineTo(area.right - size * .14f, area.bottom - size * .16f + stroke);
                z.lineTo(area.left + size * .14f, area.bottom - size * .16f + stroke);
                z.lineTo(area.right - size * .36f, area.top + size * .16f + stroke);
                z.lineTo(area.left + size * .14f, area.top + size * .16f + stroke);
                z.close();
                canvas.drawPath(z, paint);
                return;
            }

            Path path = new Path();
            if ("chevron".equals(shape)) {
                path.moveTo(area.left + size * .18f, area.top + size * .30f);
                path.lineTo(area.centerX(), area.top + size * .70f);
                path.lineTo(area.right - size * .18f, area.top + size * .30f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(shapeStrokeWidth);
                canvas.drawPath(path, paint);
            } else {
                path.moveTo(area.centerX(), area.top + size * .12f);
                path.lineTo(area.right - size * .12f, area.bottom - size * .16f);
                path.lineTo(area.left + size * .12f, area.bottom - size * .16f);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            }
        }

        private LinearGradient linear(int first, int second, float angle, RectF area) {
            float radians = (float) Math.toRadians(angle);
            float dx = (float) Math.sin(radians);
            float dy = (float) Math.cos(radians);
            float length = (float) Math.hypot(area.width(), area.height());
            float cx = area.centerX(), cy = area.centerY();
            return new LinearGradient(cx - dx * length / 2f, cy - dy * length / 2f,
                    cx + dx * length / 2f, cy + dy * length / 2f,
                    first, second, Shader.TileMode.CLAMP);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
