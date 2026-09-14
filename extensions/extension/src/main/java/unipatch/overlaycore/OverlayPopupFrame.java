package unipatch.overlaycore;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;

/** Shared card frame for every overlay popup. */
final class OverlayPopupFrame extends LinearLayout {
    OverlayPopupFrame(Context context, OverlayConfig config) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 12));
        setBackground(OverlayViews.background(config.background, config.outline, false,
                config.outlineWidth, !"square".equals(config.menuCorners)));
        setClickable(true);
        setOnClickListener(v -> { });
    }

    void addHeader(String titleText, OverlayConfig config, View leftIcon, View rightIcon) {
        if (!config.showExtraPopupHeaders) return;
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(getContext(), 8), dp(getContext(), 6), dp(getContext(), 8), dp(getContext(), 6));
        boolean leftIconEnabled = "left".equals(config.titleIconPlacement) || "both".equals(config.titleIconPlacement);
        boolean rightIconEnabled = "right".equals(config.titleIconPlacement) || "both".equals(config.titleIconPlacement);
        if (leftIcon != null) row.addView(leftIcon, iconParams());
        TextView title = new TextView(getContext());
        title.setText(titleText);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(config.menuTextColor1);
        title.setTypeface(OverlayViews.typeface(config.menuTextFont, Typeface.BOLD));
        title.setGravity("center".equals(config.titleAlignment) ? Gravity.CENTER
                : ("right".equals(config.titleAlignment) ? Gravity.RIGHT : Gravity.LEFT));
        LayoutParams titleParams = new LayoutParams(0, -2, 1f);
        titleParams.leftMargin = leftIconEnabled ? dp(getContext(), 8) : 0;
        titleParams.rightMargin = rightIconEnabled ? dp(getContext(), 8) : 0;
        row.addView(title, titleParams);
        if (rightIcon != null) row.addView(rightIcon, iconParams());
        addView(row, new LayoutParams(-1, -2));
        if (config.titleSeparator) {
            View line = new View(getContext());
            line.setBackgroundColor(config.outline);
            LayoutParams lineParams = new LayoutParams(-1, dp(getContext(), 1));
            lineParams.topMargin = dp(getContext(), 6);
            lineParams.leftMargin = -getPaddingLeft();
            lineParams.rightMargin = -getPaddingRight();
            addView(line, lineParams);
        }
    }

    private LayoutParams iconParams() {
        LayoutParams params = new LayoutParams(dp(getContext(), 32), dp(getContext(), 32));
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }
}
