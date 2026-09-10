package unipatch.overlaycore;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import android.graphics.RectF;

/** Shared view construction and styling primitives for the overlay controller. */
final class OverlayViews {
    static android.graphics.Typeface typeface(String font, int style) {
        String family;
        if ("roboto".equals(font)) family = "sans-serif";
        else if ("sansSerif".equals(font)) family = "sans-serif";
        else if ("serif".equals(font)) family = "serif";
        else if ("monospace".equals(font)) family = "monospace";
        else if ("sansCondensed".equals(font)) family = "sans-serif-condensed";
        else if ("sansMedium".equals(font)) family = "sans-serif-medium";
        else if ("sansBlack".equals(font)) family = "sans-serif-black";
        else family = "sans-serif";
        return android.graphics.Typeface.create(family, style);
    }
    private OverlayViews() { }
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

    /** Compact controls use the menu fill/outline while their geometry follows the selected theme. */
    static GradientDrawable themedControlBackground(int color, int stroke, int strokeWidth, String theme) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius("legacy".equals(theme) ? 0f : ("monet".equals(theme) ? 1000f : 18f));
        drawable.setStroke(Math.max(1, strokeWidth), stroke);
        return drawable;
    }

    /** Builds the legacy icon background while keeping its gradient and outline independent. */
    static Drawable gradientBackground(int first, int second, float angle, int stroke, int strokeWidth, boolean circle) {
        return gradientBackground(first, second, angle, stroke, strokeWidth, circle ? "circle" : "squircle");
    }

    static Drawable gradientBackground(int first, int second, float angle, int stroke, int strokeWidth,
                                       String buttonShape) {
        return new GradientBackground(first, second, angle, stroke, strokeWidth, buttonShape, true);
    }

    static Drawable icon(int background1, int background2, float backgroundAngle, boolean backgroundGradient,
                         int outline1, int outline2, float outlineAngle, boolean outlineGradient,
                         int outlineWidth, String buttonShape, String style, String shape,
                         int shapeColor1, int shapeColor2, boolean shapeGradient, float shapeAngle,
                         float shapeStrokeWidth, float shapeScale, boolean highlight, boolean shadow,
                         String backgroundStyle, int backgroundColor3, int backgroundColor4,
                         String[] iconParts) {
        return new IconDrawable(background1, background2, backgroundAngle, backgroundGradient,
                outline1, outline2, outlineAngle, outlineGradient, outlineWidth, buttonShape,
                style, shape, shapeColor1, shapeColor2, shapeGradient, shapeAngle,
                shapeStrokeWidth, shapeScale, highlight, shadow, backgroundStyle, backgroundColor3, backgroundColor4,
                iconParts);
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
        return new GradientBackground(first, second, angle, stroke, strokeWidth,
            rounded ? "squircle" : "square", rounded);
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
        private final Matrix shaderMatrix = new Matrix();
        private Shader outlineShader;
        private float shaderWidth = -1f;
        private float shaderHeight = -1f;
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
            if (outlineShader == null || shaderWidth != width || shaderHeight != height) {
                outlineShader = vertical
                        ? new LinearGradient(0f, -height, 0f, height,
                        colors, positions, Shader.TileMode.REPEAT)
                        : new LinearGradient(-width, 0f, width, 0f,
                        colors, positions, Shader.TileMode.REPEAT);
                shaderWidth = width;
                shaderHeight = height;
            }
            shaderMatrix.setTranslate(vertical ? 0f : shift, vertical ? shift : 0f);
            outlineShader.setLocalMatrix(shaderMatrix);
            border.setShader(outlineShader);
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
        private final String buttonShape;
        private final boolean rounded;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

        GradientBackground(int first, int second, float angle, int stroke, int strokeWidth,
                   String buttonShape, boolean rounded) {
            this.first = first;
            this.second = second;
            this.angle = angle;
            this.stroke = stroke;
            this.strokeWidth = Math.max(1, strokeWidth);
                this.buttonShape = "square".equals(buttonShape) || "squircle".equals(buttonShape)
                    ? buttonShape : "circle";
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
                float radius = !rounded || "square".equals(buttonShape) ? 0f
                    : ("circle".equals(buttonShape)
                    ? Math.min(bounds.width(), bounds.height()) / 2f : 24f);
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
        private static final float PREVIEW_BODY_SIZE = 376f;
        private final int background1, background2, outline1, outline2, shapeColor1, shapeColor2;
        private final float backgroundAngle, outlineAngle, shapeAngle, shapeStrokeWidth, shapeScale;
        private final boolean backgroundGradient, outlineGradient, shapeGradient, highlight, shadow;
        private final String style, shape, buttonShape, backgroundStyle;
        private final int backgroundColor3, backgroundColor4;
        private final String[] iconParts;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable(int background1, int background2, float backgroundAngle, boolean backgroundGradient,
                     int outline1, int outline2, float outlineAngle, boolean outlineGradient,
                     int outlineWidth, String buttonShape, String style, String shape,
                     int shapeColor1, int shapeColor2, boolean shapeGradient, float shapeAngle,
                     float shapeStrokeWidth, float shapeScale, boolean highlight, boolean shadow,
                     String backgroundStyle, int backgroundColor3, int backgroundColor4,
                     String[] iconParts) {
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
            this.iconParts = iconParts == null ? new String[0] : iconParts.clone();
            this.outlineWidth = Math.max(0, outlineWidth);
                this.buttonShape = "square".equals(buttonShape) || "squircle".equals(buttonShape)
                    ? buttonShape : "circle";
            this.style = style == null ? "shape" : style;
            this.shape = shape == null ? "triangle" : shape;
        }

        private final int outlineWidth;

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float halfOutline = outlineWidth / 2f;
            RectF body = new RectF(bounds.left + halfOutline, bounds.top + halfOutline,
                    bounds.right - halfOutline, bounds.bottom - halfOutline);
                float radius = "circle".equals(buttonShape)
                    ? Math.min(body.width(), body.height()) / 2f
                    : ("squircle".equals(buttonShape) ? 24f : 0f);
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

            if ("parts".equals(style) && iconParts.length > 0) drawParts(canvas, body);
            else drawShape(canvas, body);
            if (highlight && "parts".equals(style)) {
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

        private boolean drawParts(Canvas canvas, RectF body) {
            List<IconPart> parts = new ArrayList<>();
            for (String encoded : iconParts) {
                IconPart part = IconPart.parse(encoded);
                if (part != null) parts.add(part);
            }
            if (parts.isEmpty()) {
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(1f);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeJoin(Paint.Join.MITER);
                paint.setShader(null);
                return false;
            }
            Collections.sort(parts, (first, second) -> Integer.compare(first.layer, second.layer));
            for (IconPart part : parts) {
                float width = body.width() * part.width / 100f;
                float height = body.height() * part.height / 100f;
                RectF area = new RectF(
                        body.left + body.width() * part.x / 100f - width / 2f,
                        body.top + body.height() * part.y / 100f - height / 2f,
                        body.left + body.width() * part.x / 100f + width / 2f,
                        body.top + body.height() * part.y / 100f + height / 2f);
                int save = canvas.save();
                canvas.rotate(part.rotation, area.centerX(), area.centerY());
                paint.setAlpha(Math.round(255f * part.opacity / 100f));
                paint.setColor(part.color1);
                paint.setShader("gradient".equals(part.fill)
                        ? linear(part.color1, part.color2, part.gradientAngle, area) : null);
                drawPartShape(canvas, area, body, part);
                canvas.restoreToCount(save);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(1f);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeJoin(Paint.Join.MITER);
            paint.setShader(null);
            return true;
        }

        private void drawPartShape(Canvas canvas, RectF area, RectF body, IconPart part) {
            String shape = part.shape;
            float width = area.width();
            float height = area.height();
            float stroke = Math.max(1f, part.strokeWidth
                    * Math.min(body.width(), body.height()) / PREVIEW_BODY_SIZE);
            Path path = new Path();
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if ("circle".equals(shape)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawOval(area, paint);
            } else if ("ring".equals(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawOval(area, paint);
            } else if ("square".equals(shape) || "roundedRect".equals(shape)) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(area, "roundedRect".equals(shape) ? Math.min(width, height) * .18f : 0f,
                        "roundedRect".equals(shape) ? Math.min(width, height) * .18f : 0f, paint);
            } else if ("v".equals(shape)) {
                path.moveTo(area.left + width * .22f, area.top + height * .18f);
                path.lineTo(area.centerX(), area.bottom - height * .18f);
                path.lineTo(area.right - width * .22f, area.top + height * .18f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(path, paint);
            } else if ("roundedTriangle".equals(shape)) {
                float radius = Math.min(width, height) * .14f;
                path.moveTo(area.left + radius, area.top);
                path.lineTo(area.right - radius, area.top);
                path.quadTo(area.right, area.top, area.right - radius * .42f, area.top + radius * .62f);
                path.lineTo(area.centerX() + radius * .42f, area.bottom - radius * 1.18f);
                path.quadTo(area.centerX(), area.bottom, area.centerX() - radius * .42f, area.bottom - radius * 1.18f);
                path.lineTo(area.left + radius * .42f, area.top + radius * .62f);
                path.quadTo(area.left, area.top, area.left + radius, area.top);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            } else if ("line".equals(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawLine(area.left, area.centerY(), area.right, area.centerY(), paint);
            } else if ("arc".equals(shape)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawArc(area, 20f, 140f, false, paint);
            } else if ("text".equals(shape)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(typeface(part.font,
                    part.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
                paint.setTextSize(Math.max(1f, Math.min(width / Math.max(1, part.text.length()), height) * .82f));
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float baseline = area.centerY() - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(part.text, area.centerX(), baseline, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            } else if ("diamond".equals(shape)) {
                path.moveTo(area.centerX(), area.top);
                path.lineTo(area.right, area.centerY());
                path.lineTo(area.centerX(), area.bottom);
                path.lineTo(area.left, area.centerY());
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            } else if ("star".equals(shape)) {
                path.moveTo(area.centerX(), area.top);
                for (int i = 1; i < 10; i++) {
                    double angle = -Math.PI / 2d + i * Math.PI / 5d;
                    float radius = i % 2 == 0 ? Math.min(width, height) / 2f : Math.min(width, height) * .2f;
                    path.lineTo(area.centerX() + (float) Math.cos(angle) * radius,
                            area.centerY() + (float) Math.sin(angle) * radius);
                }
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            } else if ("heart".equals(shape)) {
                path.moveTo(area.centerX(), area.bottom);
                path.cubicTo(area.left - width * .05f, area.top + height * .48f,
                        area.left + width * .16f, area.top, area.centerX(), area.top + height * .28f);
                path.cubicTo(area.right - width * .16f, area.top,
                        area.right + width * .05f, area.top + height * .48f, area.centerX(), area.bottom);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            } else {
                path.moveTo(area.centerX(), area.top);
                path.lineTo(area.right, area.bottom);
                path.lineTo(area.left, area.bottom);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }


        private static final class IconPart {
            final String shape, fill, text;
            final float x, y, width, height, rotation, strokeWidth, opacity, gradientAngle;
            final int color1, color2, layer;
            final boolean bold;
            final String font;

            private IconPart(String shape, float x, float y, float width, float height, float rotation,
                             String fill, int color1, int color2, float strokeWidth, float opacity, int layer,
                             String text, boolean bold, String font) {
                this.shape = shape; this.x = x; this.y = y; this.width = width; this.height = height;
                this.rotation = rotation; this.fill = fill; this.color1 = color1; this.color2 = color2;
                this.strokeWidth = strokeWidth; this.opacity = opacity; this.layer = layer;
                this.text = text;
                this.bold = bold;
                this.font = font;
                // Rotation belongs to the geometry. The canvas transform rotates the fill with
                // the part, so reusing this value as a separate gradient direction is redundant.
                this.gradientAngle = 0f;
            }

            static IconPart parse(String encoded) {
                try {
                    String[] fields = encoded.split("\\|", -1);
                    if (fields.length < 12 || fields.length > 15) return null;
                    String shape = fields[0].trim();
                    boolean legacyChevron = "chevron".equals(shape);
                    if (legacyChevron) shape = "text";
                    String fill = "gradient".equals(fields[6].trim()) ? "gradient" : "solid";
                    if (!isSupportedShape(shape)) return null;
                    float x = bounded(fields[1], 50f, 0f, 100f);
                    float y = bounded(fields[2], 50f, 0f, 100f);
                    float width = bounded(fields[3], 50f, 1f, 100f);
                    float height = bounded(fields[4], 50f, 1f, 100f);
                    float rotation = bounded(fields[5], 0f, -360f, 360f);
                    int color1 = parseColor(fields[7], Color.WHITE);
                    int color2 = parseColor(fields[8], color1);
                    float stroke = bounded(fields[9], 3f, 0f, 32f);
                    float opacity = bounded(fields[10], 100f, 0f, 100f);
                    int layer = Math.round(bounded(fields[11], 0f, -32f, 32f));
                    String text = legacyChevron ? "V" : (fields.length >= 13 ? fields[12].trim().replace('|', ' ') : "");
                        boolean bold = legacyChevron || (fields.length >= 14 && "true".equalsIgnoreCase(fields[13].trim()));
                        String font = fields.length >= 15 ? fields[14].trim() : "default";
                        if (!"default".equals(font) && !"roboto".equals(font) && !"sansSerif".equals(font)
                            && !"serif".equals(font) && !"monospace".equals(font)
                            && !"sansCondensed".equals(font) && !"sansMedium".equals(font)
                            && !"sansBlack".equals(font)) font = "default";
                    if ("text".equals(shape)) text = text.substring(0, Math.min(3, text.length()));
                    if ("text".equals(shape) && text.isEmpty()) text = "?";
                    return new IconPart(shape, x, y, width, height, rotation, fill, color1, color2, stroke, opacity, layer, text, bold, font);
                } catch (RuntimeException ignored) {
                    return null;
                }
            }

            private static boolean isSupportedShape(String shape) {
                return "triangle".equals(shape) || "roundedTriangle".equals(shape) || "v".equals(shape)
                        || "circle".equals(shape) || "ring".equals(shape)
                        || "square".equals(shape) || "roundedRect".equals(shape)
                        || "line".equals(shape)
                        || "arc".equals(shape) || "diamond".equals(shape)
                        || "star".equals(shape) || "heart".equals(shape) || "text".equals(shape);
            }

            private static float bounded(String value, float fallback, float min, float max) {
                try { return Math.max(min, Math.min(max, Float.parseFloat(value.trim()))); }
                catch (RuntimeException ignored) { return fallback; }
            }

            private static int parseColor(String value, int fallback) {
                try { return Color.parseColor(value.trim()); }
                catch (RuntimeException ignored) { return fallback; }
            }
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

            if ("revanced".equals(shape)) {
                // ReVanced-inspired composite mark: a thick white V framing a small
                // downward gradient triangle. Draw the V first so the triangle remains
                // visually centered inside it.
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(shapeStrokeWidth, size * .085f));
                paint.setColor(0xFFFFFFFF);
                Path v = new Path();
                v.moveTo(area.left + size * .18f, area.top + size * .18f);
                v.lineTo(area.centerX(), area.top + size * .82f);
                v.lineTo(area.right - size * .18f, area.top + size * .18f);
                canvas.drawPath(v, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setShader(shapeGradient ? linear(shapeColor1, shapeColor2, shapeAngle, area) : null);
                paint.setColor(shapeColor1);
                Path triangle = new Path();
                float radius = size * .055f;
                float triangleTop = area.top + size * .28f;
                float triangleBottom = area.top + size * .62f;
                triangle.moveTo(area.left + radius, triangleTop);
                triangle.lineTo(area.right - radius, triangleTop);
                triangle.quadTo(area.right, triangleTop, area.right - radius * .42f, triangleTop + radius * .62f);
                triangle.lineTo(area.centerX() + radius * .42f, triangleBottom - radius * 1.18f);
                triangle.quadTo(area.centerX(), triangleBottom, area.centerX() - radius * .42f, triangleBottom - radius * 1.18f);
                triangle.lineTo(area.left + radius * .42f, triangleTop + radius * .62f);
                triangle.quadTo(area.left, triangleTop, area.left + radius, triangleTop);
                triangle.close();
                canvas.drawPath(triangle, paint);
                return;
            }

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

            Path path = new Path();
            if ("roundedTriangle".equals(shape)) {
                float radius = size * .16f;
                float topPoint = area.top + radius * .35f;
                float topSide = area.top + radius;
                float bottomSide = area.bottom - radius;
                path.moveTo(area.centerX(), topPoint);
                path.quadTo(area.centerX(), area.top, area.centerX() + radius, topSide);
                path.lineTo(area.right - radius, bottomSide);
                path.quadTo(area.right, area.bottom, area.right - radius * .35f, area.bottom);
                path.lineTo(area.left + radius * .35f, area.bottom);
                path.quadTo(area.left, area.bottom, area.left + radius, bottomSide);
                path.lineTo(area.centerX() - radius, topSide);
                path.quadTo(area.centerX(), area.top, area.centerX(), topPoint);
            } else {
                path.moveTo(area.centerX(), area.top + size * .12f);
                path.lineTo(area.right - size * .12f, area.bottom - size * .16f);
                path.lineTo(area.left + size * .12f, area.bottom - size * .16f);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
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
