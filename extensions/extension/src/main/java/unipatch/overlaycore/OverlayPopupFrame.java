package unipatch.overlaycore;

import android.content.Context;
import android.view.View;
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

    void addHeader(String titleText, OverlayConfig config) {
        if (!config.showExtraPopupHeaders) return;
        android.widget.TextView title = new android.widget.TextView(getContext());
        title.setText(titleText);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(config.menuTextColor1);
        title.setTypeface(OverlayViews.typeface(config.menuTextFont, android.graphics.Typeface.BOLD));
        addView(title, new LayoutParams(-1, -2));
        if (config.titleSeparator) {
            View line = new View(getContext());
            line.setBackgroundColor(config.menuTextColor1);
            LayoutParams lineParams = new LayoutParams(-1, dp(getContext(), 1));
            lineParams.topMargin = dp(getContext(), 6);
            addView(line, lineParams);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }
}
