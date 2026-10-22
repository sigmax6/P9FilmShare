package com.p9filmshare.app;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 零依赖极简 HTTP 服务（鸿蒙设备热点主机端运行）。
 *
 * 端点:
 *   GET  /api/hello?t=TOKEN                      握手 + 暴露本机 Token（免鉴权，仅用于连通性测试）
 *   GET  /api/received?t=TOKEN                  返回本机已收照片的 key 列表（JSON 数组），供 P9 做增量比对
 *   POST /api/upload?t=TOKEN&name=文件名&size=N   接收 P9 上传的原图字节流
 *
 * 仅暴露到热点局域网内，用一次性 Token 做基本隔离；生产版应升级为本地自签名 TLS。
 */
public class HttpFileServer {

    /** 重复文件处理决策。 */
    public static final int DUP_OVERWRITE = 1;
    public static final int DUP_COPY = 2;
    public static final int DUP_SKIP = 3;

    public interface DuplicateResolver {
        /**
         * 收到重复文件（与已收记录 key 相同）时，由实现者决定如何处理。
         * 接收端（ReceiverActivity）会在此弹出对话框“覆盖/复制/跳过/全部…”。
         * 抛 InterruptedException 时调用方会退化为“复制”。
         */
        int resolve(String name, String key) throws InterruptedException;
    }

    public interface UploadHandler {
        /** 处理一个上传文件（tmp 为临时文件），返回给客户端的 JSON 状态字符串。可抛异常。 */
        String handleUpload(String name, long size, File tmp) throws Exception;
    }

    private final int port;
    private final UploadHandler handler;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Context appCtx;
    private DuplicateResolver dupResolver;

    public HttpFileServer(int port, UploadHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void setContext(Context c) {
        this.appCtx = c;
    }

    public void setDuplicateResolver(DuplicateResolver r) {
        this.dupResolver = r;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::serveLoop, "p9share-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serveLoop() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                final Socket client = serverSocket.accept();
                Thread worker = new Thread(() -> {
                    try {
                        handle(client);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handle(Socket socket) throws IOException {
        byte[] buf = new byte[8192];
        InputStream in = socket.getInputStream();

        // 读取请求头（读到 \r\n\r\n 为止）
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int headerEnd = 0;
        int read;
        while ((read = in.read(buf)) > 0) {
            headerBuf.write(buf, 0, read);
            byte[] all = headerBuf.toByteArray();
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
            if (headerEnd > 0) break;
            if (headerBuf.size() > 65536) break;
        }

        byte[] all = headerBuf.toByteArray();
        if (headerEnd == 0) return; // 没拿到完整请求头

        String header = new String(all, 0, Math.min(headerEnd, all.length), "ISO-8859-1");
        int lineEnd = header.indexOf("\r\n");
        if (lineEnd < 0) return;
        String requestLine = header.substring(0, lineEnd);
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) return;
        String method = parts[0];
        String path = parts[1];
        int q = path.indexOf('?');
        String uri = q >= 0 ? path.substring(0, q) : path;
        String rawQuery = q >= 0 ? path.substring(q + 1) : "";
        Map<String, String> query = parseQuery(rawQuery);

        OutputStream out = new BufferedOutputStream(socket.getOutputStream());

        // /api/hello 免 Token，供 P9 做连通性测试
        if ("/api/hello".equals(uri)) {
            String json = "{\"device\":\"HarmonyOS\",\"service\":\"p9share\",\"port\":" + port
                    + ",\"token\":\"" + (ShareService.TOKEN == null ? "" : ShareService.TOKEN) + "\"}";
            write(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
            out.flush();
            return;
        }

        if (ShareService.TOKEN == null || !ShareService.TOKEN.equals(query.get("t"))) {
            write(out, 403, "Forbidden", "application/json",
                    "{\"error\":\"bad token\"}".getBytes());
            out.flush();
            return;
        }

        // 已收文件列表（增量传输用）
        if ("/api/received".equals(uri) && "GET".equals(method)) {
            String json = (appCtx != null) ? TransferLog.namesJson(appCtx) : "[]";
            write(out, 200, "OK", "application/json; charset=utf-8", json.getBytes("UTF-8"));
            out.flush();
            return;
        }

        if ("/api/upload".equals(uri) && "POST".equals(method)) {
            int contentLength = -1;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ignore) {
                    }
                }
            }
            String name = query.get("name");
            if (name == null) {
                write(out, 400, "Bad Request", "application/json",
                        "{\"error\":\"missing name\"}".getBytes());
                out.flush();
                return;
            }
            name = URLDecoder.decode(name, "UTF-8");
            String sizeStr = query.get("size");
            long size = -1;
            if (sizeStr != null) {
                try {
                    size = Long.parseLong(sizeStr);
                } catch (Exception ignore) {
                }
            }

            File tmp = new File(System.getProperty("java.io.tmpdir"),
                    "p9up_" + System.nanoTime() + ".tmp");
            try {
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    int already = all.length - headerEnd;
                    if (already > 0) fos.write(all, headerEnd, already);
                    long got = already;
                    while (contentLength < 0 || got < contentLength) {
                        int r = in.read(buf);
                        if (r <= 0) break;
                        fos.write(buf, 0, r);
                        got += r;
                    }
                    fos.flush();
                }
                String json = handler.handleUpload(name, size, tmp);
                write(out, 200, "OK", "application/json", json.getBytes("UTF-8"));
            } catch (Throwable e) {
                write(out, 500, "Internal Error", "application/json",
                        ("{\"error\":\"" + (e.getMessage() == null ? e : e.getMessage()) + "\"}").getBytes("UTF-8"));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            out.flush();
            return;
        }

        write(out, 404, "Not Found", "application/json",
                "{\"error\":\"not found\"}".getBytes());
        out.flush();
    }

    // ---------- helpers ----------

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                String k = eq >= 0 ? pair.substring(0, eq) : pair;
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                map.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private static void write(OutputStream out, int code, String status, String type, byte[] body)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.write(body);
    }
}
