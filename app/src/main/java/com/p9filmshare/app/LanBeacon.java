package com.p9filmshare.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * 接收端（鸿蒙设备）的局域网应答器：UDP 8422。
 *
 * 作用：让 P9 在不扫码的情况下也能“找回”接收端——P9 往 255.255.255.255:8422 广播一个探针，
 * 本类收到后用当前连接信息（与二维码同一格式的 P9SHARE2 载荷）回一份。
 * 这样“双方连同一个 Wi-Fi”时，点「历史连接」也能自动重连，不需要重新扫码。
 *
 * 只是尽力而为：若卓易通容器把 UDP 广播拦掉，P9 侧仍会退回“历史 IP / 网关 IP”逐个探测。
 */
public class LanBeacon {

    public static final int PORT = 8422;

    private static volatile String payload = "";
    private static volatile boolean running;
    private static DatagramSocket socket;
    private static Thread thread;

    /** 更新应答内容（与二维码载荷一致）。每次二维码刷新时调用。 */
    public static void setPayload(String p) {
        payload = (p == null ? "" : p);
    }

    public static synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(LanBeacon::loop, "p9share-beacon");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignore) {
            }
            socket = null;
        }
        thread = null;
    }

    private static void loop() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            byte[] buf = new byte[512];
            while (running) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (Throwable t) {
                    if (!running) break;
                    continue;
                }
                String body = payload;
                if (body.isEmpty()) continue;
                try {
                    byte[] out = body.getBytes("UTF-8");
                    socket.send(new DatagramPacket(out, out.length, in.getAddress(), in.getPort()));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
            // 端口被占用 / 无权限时静默失败，不影响扫码流程
        }
    }
}
