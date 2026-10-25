package cn.weilin.termlens.probe;
import java.util.ArrayList;

/** Host graph checks for mixed translation/Ask nesting and bounded depth. */
public final class StudyNavigationChecks {
    static int checks;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
    static final class Frame {final String mode,text,rounds;final int scroll;Frame(int i){mode=i%2==0?"translation":"ask";text="query-"+i;rounds="rounds-"+i;scroll=i*91;}}
    public static void main(String[] args){
        ArrayList<Frame> stack=new ArrayList<>();stack.add(new Frame(0));int depth=0;
        for(int i=1;i<=StudyNavigation.MAX_DEPTH;i++){check(StudyNavigation.canOpen(depth),"child permitted "+i);depth=StudyNavigation.child(depth);stack.add(new Frame(i));}
        check(!StudyNavigation.canOpen(depth),"resource limit reached");
        boolean rejected=false;try{StudyNavigation.child(depth);}catch(IllegalStateException expected){rejected=true;}check(rejected,"no unbounded push");
        for(int i=StudyNavigation.MAX_DEPTH;i>0;i--){stack.remove(stack.size()-1);depth--;Frame parent=stack.get(stack.size()-1);check(parent.text.equals("query-"+(i-1))&&parent.scroll==(i-1)*91&&parent.rounds.equals("rounds-"+(i-1)),"one-level return preserves parent fixture "+i);}
        check(stack.size()==1&&depth==0,"root remains after all child returns");
        check(StudyNavigation.depth(-5)==0&&StudyNavigation.depth(999)==StudyNavigation.MAX_DEPTH,"inbound depth bounded");
        System.out.println("PASS: "+checks+" bounded mixed navigation model checks (not Android Activity runtime)");
    }
}
