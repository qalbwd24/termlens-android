package cn.weilin.termlens.probe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = Color.rgb(31, 43, 40);
    static final int MUTED = Color.rgb(103, 115, 110);
    static final int ACCENT = Color.rgb(52, 100, 88);
    static final int CANVAS = Color.rgb(247, 248, 246);

    static int dp(android.content.Context activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static TextView text(Activity activity, String text, float size, int color) {
        TextView view = new TextView(activity);
        // Android suppresses PROCESS_TEXT actions when a TextView has NO_ID.
        view.setId(View.generateViewId());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(activity, 4), 1);
        return view;
    }

    static TextView title(Activity activity, String text, float size) {
        TextView view = text(activity, text, size, INK);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static GradientDrawable surface(Activity activity, int color, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(activity, radius));
        return background;
    }

    static void space(Activity activity, LinearLayout parent, int height) {
        parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(activity, height)));
    }

    static Button button(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(surface(activity, ACCENT, 14));
        button.setMinHeight(dp(activity, 48));
        button.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        return button;
    }
    static Button quietButton(Activity activity,String text){
        Button button=button(activity,text);button.setTextSize(13);button.setTextColor(ACCENT);button.setBackground(surface(activity,Color.WHITE,9));
        button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(activity,6),0,dp(activity,6),0);button.setSingleLine(true);button.setEllipsize(android.text.TextUtils.TruncateAt.END);button.setStateListAnimator(null);button.setElevation(0);return button;
    }
}
