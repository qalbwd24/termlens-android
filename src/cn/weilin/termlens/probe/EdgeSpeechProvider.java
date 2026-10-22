/* Edge read-aloud protocol adapted from rany2/edge-tts and TermLens EdgeSpeech.cs.
 * Copyright rany and edge-tts contributors; Java adaptation licensed LGPL-3.0.
 * See licenses/edge-tts-LICENSE.txt and licenses/edge-tts-NOTICE.txt.
 */
package cn.weilin.termlens.probe;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.*;

/** Small bounded TLS/WebSocket client; no Python, local server, key, or installed TTS engine. */
final class EdgeSpeechProvider implements SpeechProvider {
    private static final String HOST="speech.platform.bing.com",TOKEN="6A5AA1D4EAFF4E9FB37E23D68491D6F4",VERSION="143.0.3650.75";
    private static final SecureRandom RANDOM=new SecureRandom();
    private volatile long skew;
    public String id(){return "edge";}
    public String extension(){return ".mp3";}
    public byte[] synthesize(String text,String voice,int rate) throws Exception {
        if(text.trim().isEmpty()||text.length()>4000)throw new Exception("朗读文本为空或超出单段长度");
        for(int attempt=0;attempt<2;attempt++) {
            Socket tcp=new Socket();long started=android.os.SystemClock.elapsedRealtime();
            try {
                tcp.setTcpNoDelay(true);tcp.connect(new InetSocketAddress(HOST,443),15000);tcp.setSoTimeout(45000);
                try(SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(tcp,HOST,443,true)) {
                    SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.startHandshake();
                    android.util.Log.i("TLSpeech","edge_tls_ready ms="+(android.os.SystemClock.elapsedRealtime()-started));
                    InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();
                    byte[] nonce=new byte[16];RANDOM.nextBytes(nonce);String key=android.util.Base64.encodeToString(nonce,android.util.Base64.NO_WRAP);
                    String path="/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken="+TOKEN+"&ConnectionId="+AppData.id()+"&Sec-MS-GEC="+EdgeToken.compute(System.currentTimeMillis()+skew)+"&Sec-MS-GEC-Version=2-"+VERSION;
                    String request="GET "+path+" HTTP/1.1\r\nHost: "+HOST+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\nOrigin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0\r\nPragma: no-cache\r\nCache-Control: no-cache\r\nAccept-Language: en-US,en;q=0.9\r\nCookie: muid="+AppData.id().toUpperCase(Locale.ROOT)+";\r\n\r\n";
                    output.write(request.getBytes(StandardCharsets.US_ASCII));output.flush();
                    String status=readLine(input);Map<String,String> headers=new HashMap<>();int length=status.length();String line;
                    while(!(line=readLine(input)).isEmpty()){length+=line.length();if(length>32768)throw new IOException("语音服务握手过大");int at=line.indexOf(':');if(at>0)headers.put(line.substring(0,at).toLowerCase(Locale.ROOT),line.substring(at+1).trim());}
                    if(!status.startsWith("HTTP/1.1 101 ")) {
                        if(status.contains(" 403 ")&&attempt==0&&headers.containsKey("date")){
                            SimpleDateFormat format=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);skew=format.parse(headers.get("date")).getTime()-System.currentTimeMillis();continue;
                        }
                        throw new IOException("Edge 语音连接失败（"+status.replaceAll("[^0-9 ]","").trim()+"）");
                    }
                    String expected=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),android.util.Base64.NO_WRAP);
                    if(!expected.equals(headers.get("sec-websocket-accept"))||!"websocket".equalsIgnoreCase(headers.get("upgrade"))||headers.containsKey("sec-websocket-extensions"))throw new IOException("语音服务 WebSocket 握手无效");
                    android.util.Log.i("TLSpeech","edge_websocket_ready ms="+(android.os.SystemClock.elapsedRealtime()-started));
                    send(output,1,config().getBytes(StandardCharsets.UTF_8));send(output,1,ssml(text,voice,rate).getBytes(StandardCharsets.UTF_8));
                    ByteArrayOutputStream audio=new ByteArrayOutputStream(),message=new ByteArrayOutputStream();int messageType=0;long deadline=System.currentTimeMillis()+120000;
                    for(int count=0;count<20000;count++) {
                        if(Thread.currentThread().isInterrupted()||System.currentTimeMillis()>deadline)throw new IOException("Edge 语音超时或已取消");
                        Frame frame=frame(input);
                        if(frame.opcode==9){send(output,10,frame.bytes);continue;}if(frame.opcode==10)continue;
                        if(frame.opcode==8)throw new IOException("语音服务在完成前断开连接");
                        if(frame.opcode==1||frame.opcode==2){if(messageType!=0)throw new IOException("WebSocket 消息分片顺序无效");messageType=frame.opcode;}
                        else if(frame.opcode!=0||messageType==0)throw new IOException("WebSocket 消息类型无效");
                        if(message.size()+frame.bytes.length>1024*1024)throw new IOException("语音消息过大");message.write(frame.bytes);
                        if(!frame.fin)continue;byte[] bytes=message.toByteArray();message.reset();int type=messageType;messageType=0;
                        if(type==2){byte[] payload=audioPayload(bytes);if(audio.size()+payload.length>32*1024*1024)throw new IOException("语音音频过大");if(audio.size()==0&&payload.length>0)android.util.Log.i("TLSpeech","edge_first_audio ms="+(android.os.SystemClock.elapsedRealtime()-started));audio.write(payload);}
                        else {String event=new String(bytes,StandardCharsets.UTF_8);int at=event.indexOf("\r\n\r\n");String eventPath=header(at<0?event:event.substring(0,at),"Path");
                            if("turn.end".equals(eventPath)){if(audio.size()==0)throw new IOException("Edge 没有返回音频");android.util.Log.i("TLSpeech","edge_complete ms="+(android.os.SystemClock.elapsedRealtime()-started));send(output,8,new byte[]{3,(byte)232});return audio.toByteArray();}
                            if(!"turn.start".equals(eventPath)&&!"response".equals(eventPath)&&!"audio.metadata".equals(eventPath))throw new IOException("Edge 返回未知语音消息");}
                    }
                    throw new IOException("语音消息数量超限");
                }
            }finally{try{tcp.close();}catch(Exception ignored){}}
        }
        throw new IOException("Edge 语音连接失败");
    }
    static String gec(long millis) throws Exception {long ticks=(millis/1000+11644473600L)*10000000L;ticks-=ticks%3000000000L;return SpeechOutput.hash((Long.toString(ticks)+TOKEN).getBytes(StandardCharsets.US_ASCII)).toUpperCase(Locale.ROOT);}
    static String escape(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\n'&&c!='\r'&&c!='\t'||c=='\ufffe'||c=='\uffff')out.append(' ');else out.append(c);}return out.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String timestamp(){SimpleDateFormat f=new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    static String ssml(String text,String voice,int rate) throws Exception {
        if(!voice.matches("[a-z]{2,}-[A-Z]{2,}-[A-Za-z0-9-]+Neural"))throw new Exception("声音名称无效");rate=Math.max(-50,Math.min(50,rate));
        return "X-RequestId:"+AppData.id()+"\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:"+timestamp()+"Z\r\nPath:ssml\r\n\r\n<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='"+escape(voice)+"'><prosody pitch='+0Hz' rate='"+(rate>=0?"+":"")+rate+"%' volume='+0%'>"+escape(text)+"</prosody></voice></speak>";
    }
    private static String config(){return "X-Timestamp:"+timestamp()+"\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";}
    private static String readLine(InputStream input) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;while((value=input.read())!=-1){if(value=='\n')return new String(bytes.toByteArray(),StandardCharsets.US_ASCII).replace("\r","");if(bytes.size()>8192)throw new IOException("握手头过长");bytes.write(value);}throw new EOFException("语音握手中断");}
    static String header(String text,String name){for(String line:text.split("\r\n")){int at=line.indexOf(':');if(at>0&&line.substring(0,at).equalsIgnoreCase(name))return line.substring(at+1).trim();}return "";}
    static byte[] audioPayload(byte[] bytes) throws Exception {if(bytes.length<2)throw new IOException("音频消息不完整");int size=(bytes[0]&255)*256+(bytes[1]&255);if(size>bytes.length-2)throw new IOException("音频消息头不完整");String headers=new String(bytes,2,size,StandardCharsets.UTF_8);if(!header(headers,"Path").equals("audio"))throw new IOException("消息不是音频");int count=bytes.length-size-2;if(count==0&&header(headers,"Content-Type").isEmpty())return new byte[0];if(count==0||!header(headers,"Content-Type").equals("audio/mpeg"))throw new IOException("音频格式无效");return Arrays.copyOfRange(bytes,size+2,bytes.length);}
    static final class Frame {boolean fin;int opcode;byte[] bytes;}
    static Frame frame(InputStream input) throws Exception {
        DataInputStream data=new DataInputStream(input);int first=data.readUnsignedByte(),second=data.readUnsignedByte();Frame result=new Frame();result.fin=(first&128)!=0;result.opcode=first&15;
        if((first&112)!=0||(second&128)!=0)throw new IOException("WebSocket 帧无效");long length=second&127;if(length==126)length=data.readUnsignedShort();else if(length==127)length=data.readLong();
        if(length<0||length>1024*1024||result.opcode>=8&&(!result.fin||length>125))throw new IOException("WebSocket 帧过大或无效");result.bytes=new byte[(int)length];data.readFully(result.bytes);return result;
    }
    static void send(OutputStream output,int opcode,byte[] bytes) throws Exception {
        if(bytes.length>65535)throw new IOException("语音请求过大");ByteArrayOutputStream frame=new ByteArrayOutputStream(bytes.length+8);frame.write(128|opcode);if(bytes.length<126)frame.write(128|bytes.length);else{frame.write(128|126);frame.write(bytes.length>>>8);frame.write(bytes.length);}
        byte[] mask=new byte[4];RANDOM.nextBytes(mask);frame.write(mask);byte[] body=bytes.clone();for(int i=0;i<body.length;i++)body[i]^=mask[i%4];frame.write(body);output.write(frame.toByteArray());output.flush();
    }
}
