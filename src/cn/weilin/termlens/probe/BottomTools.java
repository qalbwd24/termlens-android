package cn.weilin.termlens.probe;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Horizontal, width-adaptive icons; no hidden placeholders and no side rails. */
final class BottomTools {
    static void add(LinearLayout row, Button action, ImageButton... icons){
        Context context=row.getContext();row.setGravity(Gravity.CENTER_VERTICAL);
        for(ImageButton icon:icons){
            icon.setPadding(0,0,0,0);icon.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(icon,new LinearLayout.LayoutParams(0,Ui.dp(context,48),1));
        }
        action.setTextSize(12);action.setMinWidth(0);action.setMinimumWidth(0);
        action.setPadding(Ui.dp(context,4),0,Ui.dp(context,4),0);action.setSingleLine(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(context,96),Ui.dp(context,44));
        lp.leftMargin=Ui.dp(context,4);row.addView(action,lp);
    }
}
