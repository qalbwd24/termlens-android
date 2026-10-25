package cn.weilin.termlens.probe;

import android.graphics.Rect;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.TextView;

/** Watches blank short taps inside the answer viewport without consuming any touch event. */
final class BlankContentTap {
    private final BlankTapGesture gesture=new BlankTapGesture();
    boolean observe(MotionEvent event,View viewport){
        if(viewport==null)return false;
        float x=event.getRawX(),y=event.getRawY();
        int action=event.getActionMasked();
        boolean blank=inside(viewport,x,y)&&!occupied(viewport,x,y);
        int slop=ViewConfiguration.get(viewport.getContext()).getScaledTouchSlop();
        if(action==MotionEvent.ACTION_DOWN)gesture.down(x,y,event.getEventTime(),blank);
        else if(action==MotionEvent.ACTION_MOVE)gesture.move(x,y,slop);
        else if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_DOWN)gesture.cancel();
        else if(action==MotionEvent.ACTION_UP)return gesture.up(x,y,event.getEventTime(),blank,slop,ViewConfiguration.getLongPressTimeout());
        return false;
    }
    private static boolean inside(View view,float x,float y){Rect r=new Rect();return view.getVisibility()==View.VISIBLE&&view.getGlobalVisibleRect(r)&&r.contains((int)x,(int)y);}
    private static boolean occupied(View view,float x,float y){
        if(!inside(view,x,y))return false;
        if(view instanceof EditText)return true;
        if(view instanceof TextView){
            TextView text=(TextView)view;
            if(text.isClickable()&&!text.isTextSelectable())return true;
            Layout layout=text.getLayout();if(layout==null)return false;
            int[] at=new int[2];text.getLocationOnScreen(at);
            float localX=x-at[0]-text.getTotalPaddingLeft()+text.getScrollX();
            float localY=y-at[1]-text.getTotalPaddingTop()+text.getScrollY();
            if(localY<0||localY>=layout.getHeight())return false;
            int line=layout.getLineForVertical((int)localY);
            // Treat the whole text line as occupied, including selection-handle edges.
            float margin=Ui.dp(view.getContext(),8);
            return localX>=layout.getLineLeft(line)-margin&&localX<=layout.getLineRight(line)+margin;
        }
        if(view.isClickable()||view.isLongClickable())return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--)if(occupied(group.getChildAt(i),x,y))return true;}
        return false;
    }
}
