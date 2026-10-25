package cn.weilin.termlens.probe;

/** Per-path safety limit, not an unbounded transcript or global navigation singleton. */
final class StudyNavigation {
    static final String DEPTH = "termlens_study_depth";
    static final String LOOKUP = "termlens_parent_lookup";
    static final String PROFILE = "termlens_study_profile";
    static final String ROOT = "termlens_root_context";
    static final int MAX_DEPTH = 32;
    static int depth(int value){return Math.max(0,Math.min(MAX_DEPTH,value));}
    static boolean canOpen(int depth){return depth>=0&&depth<MAX_DEPTH;}
    static int child(int depth){if(!canOpen(depth))throw new IllegalStateException("Study depth limit reached");return depth+1;}
}
