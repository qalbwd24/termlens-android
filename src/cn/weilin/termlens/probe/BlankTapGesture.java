package cn.weilin.termlens.probe;

/** Observation only: never steals a gesture from text selection or scrolling. */
final class BlankTapGesture {
    private boolean candidate;
    private float x,y;
    private long time;
    void down(float x,float y,long time,boolean blank){this.x=x;this.y=y;this.time=time;candidate=blank;}
    void move(float x,float y,float slop){if(Math.abs(x-this.x)>slop||Math.abs(y-this.y)>slop)candidate=false;}
    void cancel(){candidate=false;}
    boolean up(float x,float y,long time,boolean blank,float slop,long longPress){
        move(x,y,slop);boolean tap=candidate&&blank&&time-this.time<longPress;cancel();return tap;
    }
}
