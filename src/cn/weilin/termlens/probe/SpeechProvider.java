package cn.weilin.termlens.probe;

/** The app-facing voice API. Remote forwarding can implement this without changing audio storage. */
interface SpeechProvider {
    String id();
    String extension();
    byte[] synthesize(String text,String voice,int rate) throws Exception;
}
