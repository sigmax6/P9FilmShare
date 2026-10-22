package com.p9filmshare.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.p9filmshare.app.client.PhotoSaver;

import java.io.File;
import java.util.Random;

/**
 * 前台服务：在鸿蒙设备热点主机端持有 HTTP 上传接收服务，保证后台不被轻易回收。
 * P9 连上鸿蒙设备热点后，会把选中的照片 POST 到这里；本服务负责去重与落盘到图库「华为分享」相册。
 *
 * 增量传输（v0.8.0）：
 *  - 用 name|size 作为照片标识，记录到 TransferLog；
 *  - 同一张照片再次上传时判定为“重复”，按用户策略处理：新文件直接存 / 覆盖 / 复制为新文件 / 跳过；
 *  - 策略“询问”时，若接收端界面在前台，会弹出对话框让用户逐张或批量决定。
 */
public class ShareService extends Service {
    public static final int PORT = 8421;
    public static volatile String TOKEN;

    private HttpFileServer server;
    private HttpFileServer.DuplicateResolver resolver;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) {
            if (TOKEN == null) {
                TOKEN = randomToken();
            }
            resolver = (name, key) -> ReceiverActivity.resolveStatic(name, key);
            server = new HttpFileServer(PORT, this::handleUpload);
            server.setContext(this);
            server.setDuplicateResolver(resolver);
            server.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 处理一个上传：去重 + 落盘 + 记录。返回给 P9 的 JSON 状态串。 */
    private String handleUpload(String name, long size, File tmp) throws Exception {
        String safe = sanitize(name);
        String key = safe + "|" + size;
        boolean dup = size > 0 && TransferLog.exists(this, key);

        if (!dup) {
            String saved = PhotoSaver.save(this, tmp, safe, guessMime(safe), false);
            TransferLog.add(this, key, saved);
            return ok("new", saved);
        }

        int decision;
        try {
            decision = (resolver != null)
                    ? resolver.resolve(safe, key)
                    : HttpFileServer.DUP_COPY;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            decision = HttpFileServer.DUP_COPY;
        }

        if (decision == HttpFileServer.DUP_SKIP) {
            return ok("skipped", safe);
        }
        if (decision == HttpFileServer.DUP_OVERWRITE) {
            try {
                PhotoSaver.deleteByDisplayName(this, safe);
            } catch (Throwable ignore) {
            }
            String saved = PhotoSaver.save(this, tmp, safe, guessMime(safe), false);
            TransferLog.touch(this, key);
            return ok("overwrite", saved);
        }
        // DUP_COPY：自动加 (1)/(2) 后缀，保留原图
        String saved = PhotoSaver.save(this, tmp, safe, guessMime(safe), true);
        String newKey = saved + "|" + size;
        TransferLog.add(this, newKey, saved);
        return ok("copy", saved);
    }

    /** 去掉路径分隔符，避免写入到预期目录之外。 */
    private static String sanitize(String name) {
        if (name == null) name = "photo.jpg";
        name = name.replaceAll("[/\\\\]+", "_");
        if (name.isEmpty()) name = "photo.jpg";
        return name;
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".heic")) return "image/heic";
        return "image/jpeg";
    }

    private static String ok(String status, String file) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"status\":\"").append(status).append("\"");
        if (file != null) {
            sb.append(",\"file\":\"").append(file.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String randomToken() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void startForegroundCompat() {
        String channelId = "p9share";
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "P9FilmShare", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, channelId);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setContentTitle("P9FilmShare 接收中")
                .setContentText("热点已开，等待 P9 传照片")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }
}
