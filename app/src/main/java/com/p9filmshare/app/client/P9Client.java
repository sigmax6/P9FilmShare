package com.p9filmshare.app.client;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问鸿蒙设备热点主机（上传接收端）的极简 HTTP 客户端。
 * 全部使用系统自带 HttpURLConnection，因此同一套代码既能在 P9(Android 6) 运行，
 * 也能在卓易通(安卓16 内核) 运行。
 */
public class P9Client {

    private final String baseUrl; // 如 http://192.168.43.1:8421
    private final String token;
    private final int connectTimeoutMs;

    public P9Client(String baseUrl, String token) {
        this(baseUrl, token, 10000);
    }

    /** 可指定连接超时（探测候选地址时用短超时，避免卡住）。 */
    public P9Client(String baseUrl, String token, int connectTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 10000;
    }

    /** 连通性测试（免鉴权端点）。 */
    public boolean hello() throws Exception {
        HttpURLConnection c = open("/api/hello?t=" + enc(token), "GET");
        try {
            return c.getResponseCode() == 200;
        } finally {
            c.disconnect();
        }
    }

    /**
     * 握手并取回接收端「当前」的 Token。
     *
     * 接收端每次重新点“接收”后会换一个 Token，历史连接里存的那个就会过期；
     * /api/hello 免鉴权并会回传最新 Token，所以历史重连时先调它，再拿新 Token 上传。
     * 抛异常表示该地址不可达。
     */
    public String helloToken() throws Exception {
        HttpURLConnection c = open("/api/hello", "GET");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("hello HTTP " + code);
            InputStream in = c.getInputStream();
            String body = readAll(in);
            int i = body.indexOf("\"token\"");
            if (i >= 0) {
                int c1 = body.indexOf(':', i);
                int q1 = body.indexOf('"', c1 + 1);
                if (q1 >= 0) {
                    int q2 = body.indexOf('"', q1 + 1);
                    if (q2 > q1) {
                        String t = body.substring(q1 + 1, q2);
                        if (!t.isEmpty()) return t;
                    }
                }
            }
            return token == null ? "" : token;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * 拉取接收端已收照片的 key 列表（name|size），用于 P9 侧做“增量传输”——
     * 已传过的照片在选图界面会被标记，全选时默认跳过，单独点选才会再次上传（触发覆盖/复制选择）。
     */
    public List<String> fetchReceived() throws Exception {
        HttpURLConnection c = open("/api/received?t=" + enc(token), "GET");
        try {
            if (c.getResponseCode() != 200) return new ArrayList<>();
            String body = readAll(c.getInputStream());
            List<String> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(body);
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i);
                    if (s != null && !s.isEmpty()) out.add(s);
                }
            } catch (Throwable ignore) {
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** 读取 Uri 指向的照片并上传到 X7，带进度回调（在工作线程调用）。 */
    public boolean upload(Context ctx, Uri uri, String name, Progress cb) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new RuntimeException("无法打开该照片：" + uri);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int r;
        long got = 0;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            got += r;
            if (cb != null) cb.onProgress(got, -1);
        }
        in.close();
        byte[] data = bos.toByteArray();

        String path = "/api/upload?t=" + enc(token) + "&name=" + enc(name) + "&size=" + data.length;
        HttpURLConnection c = open(path, "POST");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("上传失败 HTTP " + code);
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    public interface Progress {
        void onProgress(long got, long total);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
