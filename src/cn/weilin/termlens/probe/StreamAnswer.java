package cn.weilin.termlens.probe;
import org.json.JSONArray;
import org.json.JSONObject;

/** Incremental SSE framing; partial or length-limited replies never become complete records. */
final class StreamAnswer {
    private final StringBuilder data = new StringBuilder();
    boolean done;
    private String finish = "";
    String line(String line) throws Exception {
        if (done) return "";
        if (line.isEmpty()) {
            String payload = data.toString().trim(); data.setLength(0);
            if (payload.isEmpty()) return "";
            if (payload.equals("[DONE]")) { done = true; return ""; }
            JSONObject event = new JSONObject(payload);
            if (event.has("error")) throw new Exception("模型流返回错误，请重试");
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject choice = choices.getJSONObject(0);
            if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason");
            JSONObject delta = choice.optJSONObject("delta");
            return delta == null || delta.isNull("content") ? "" : delta.optString("content", "");
        }
        if (line.startsWith("data:")) {
            if (data.length() > 0) data.append('\n');
            data.append(line.substring(5).trim());
            if (data.length() > 1024 * 1024) throw new Exception("接口返回的数据过大");
        }
        return "";
    }
    void requireComplete() throws Exception {
        if (!done || !(finish.equals("stop") || finish.equals("length"))) throw new Exception("连接在回答完成前中断，已收到的文字保留，可重试");
        if (finish.equals("length")) throw new Exception("回答达到输出上限，已收到的文字保留，请提高上限后重试");
    }
}
