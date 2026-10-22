package cn.weilin.termlens.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class LlmClient {
    interface Progress { void update(String answer); }
    interface Callback { void done(String answer, String error, Timing timing); }
    static final class Timing {
        final long start = android.os.SystemClock.elapsedRealtime();
        long headers = -1, firstText = -1, complete = -1;
        int characters, chunks;
        boolean streaming;
        JSONObject json() {
            JSONObject value = new JSONObject();
            try { value.put("headers_ms", headers).put("first_text_ms", firstText).put("complete_ms", complete)
                .put("characters", characters).put("chunks", chunks).put("stream", streaming); } catch (Exception ignored) { }
            return value;
        }
        long elapsed() { return android.os.SystemClock.elapsedRealtime() - start; }
    }
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private LlmClient() { }

    static final class Request {
        private volatile boolean cancelled;
        private volatile boolean finished;
        private String latest = "";
        private boolean scheduled;
        private Runnable delivery;
        synchronized void progress(String text, Progress callback) {
            if (cancelled || finished || callback == null) return;
            latest = text;
            if (scheduled) return;
            scheduled = true;
            delivery = () -> {
                String value;
                synchronized (Request.this) { scheduled = false; if (cancelled || finished) return; value = latest; }
                callback.update(value);
            };
            MAIN.postDelayed(delivery, text.length() < 12 ? 0 : 85);
        }
        private Future<?> future;
        private HttpURLConnection connection;
        synchronized void attach(Future<?> value) { future = value; if (cancelled) value.cancel(true); }
        synchronized boolean attach(HttpURLConnection value) {
            if (cancelled) { value.disconnect(); return false; }
            connection = value; return true;
        }
        void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                cancelled = true;
                if (delivery != null) MAIN.removeCallbacks(delivery);
                if (future != null) future.cancel(true);
                active = connection; connection = null;
            }
            // Disconnect away from the UI thread; closing Explain must remain immediate.
            if (active != null) EXECUTOR.execute(active::disconnect);
        }
    }

    static Request request(Context context, String userText, String systemPrompt, Progress progress, Callback callback) {
        return request(context, userText, systemPrompt, true, progress, callback);
    }
    static Request request(Context context, String userText, String systemPrompt, boolean streaming, Progress progress, Callback callback) {
        final Request request = new Request();
        final Timing timing = new Timing(); timing.streaming = streaming;
        final String key = TermLensPrefs.apiKey(context);
        final String baseUrl = TermLensPrefs.baseUrl(context);
        final String model = TermLensPrefs.model(context);
        final int maxTokens = DesktopPrompts.maxTokens(context);
        if (key.isEmpty()) { post(request, callback, "", "请先在设置中填写 API 密钥", timing); return request; }
        request.attach(EXECUTOR.submit(() -> {
            HttpURLConnection connection = null;
            StringBuilder answer = new StringBuilder();
            try {
                if (request.cancelled) return;
                String base = baseUrl;
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                URL url = new URL(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
                connection = (HttpURLConnection) url.openConnection();
                if (!request.attach(connection)) return;
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", streaming ? "text/event-stream" : "application/json");
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                messages.put(new JSONObject().put("role", "user").put("content", userText));
                JSONObject body = new JSONObject().put("model", model).put("messages", messages)
                    .put("max_tokens", maxTokens).put("temperature", 0.2).put("stream", streaming);
                if ("api.deepseek.com".equalsIgnoreCase(url.getHost())) body.put("thinking", new JSONObject().put("type", "disabled"));
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream output = connection.getOutputStream();
                output.write(bytes); output.close();
                int status = connection.getResponseCode();
                timing.headers = timing.elapsed();
                if (status < 200 || status >= 300) throw new Exception("模型请求失败（" + status + "）");
                String type = connection.getContentType();
                if (!streaming || type != null && type.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                    JSONObject choice = new JSONObject(read(connection.getInputStream())).getJSONArray("choices").getJSONObject(0);
                    answer.append(choice.getJSONObject("message").optString("content", "")); timing.firstText = timing.elapsed();
                    if ("length".equals(choice.optString("finish_reason"))) throw new Exception("回答达到输出上限，请提高上限后重试");
                } else {
                    StreamAnswer stream = new StreamAnswer(); String line;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        while (!request.cancelled && (line = reader.readLine()) != null) {
                            String delta = stream.line(line);
                            if (!delta.isEmpty()) {
                                answer.append(delta); timing.chunks++;
                                if (timing.firstText < 0) timing.firstText = timing.elapsed();
                                request.progress(answer.toString(), progress);
                            }
                            if (stream.done) break;
                        }
                    }
                    if (request.cancelled) return;
                    stream.requireComplete();
                }
                if (answer.toString().trim().isEmpty()) throw new Exception("模型没有返回内容");
                post(request, callback, answer.toString().trim(), "", timing);
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                post(request, callback, answer.toString(), key.isEmpty() ? message : message.replace(key, "[hidden]"), timing);
            }
            finally { if (connection != null) connection.disconnect(); }
        }));
        return request;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) { result.append(line); if (result.length() > 2 * 1024 * 1024) throw new Exception("接口返回的数据过大"); }
        reader.close(); return result.toString();
    }

    private static void post(Request request, Callback callback, String answer, String error, Timing timing) {
        timing.complete = timing.elapsed(); timing.characters = answer.length();
        synchronized (request) { request.finished = true; if (request.delivery != null) MAIN.removeCallbacks(request.delivery); }
        android.util.Log.i("TLPerf", timing.json().toString());
        MAIN.post(() -> { if (!request.cancelled) callback.done(answer, error, timing); });
    }
}
