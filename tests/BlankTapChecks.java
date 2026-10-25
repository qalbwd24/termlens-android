package cn.weilin.termlens.probe;
public final class BlankTapChecks {
 static int checks;static void check(boolean x){checks++;if(!x)throw new AssertionError(checks);}
 public static void main(String[] ignored){BlankTapGesture g=new BlankTapGesture();
 g.down(10,10,0,true);check(g.up(12,12,50,true,8,500));
 g.down(10,10,0,true);g.move(30,10,8);check(!g.up(10,10,100,true,8,500));
 g.down(10,10,0,true);check(!g.up(10,10,600,true,8,500));
 g.down(10,10,0,false);check(!g.up(10,10,50,true,8,500));
 g.down(10,10,0,true);check(!g.up(10,10,50,false,8,500));
 g.down(10,10,0,true);g.cancel();check(!g.up(10,10,50,true,8,500));
 check(!g.up(10,10,50,true,8,500));
 g.down(10,10,0,true);check(!g.up(19,10,50,true,8,500));
 System.out.println(checks+" blank tap gesture model checks passed");}
}
