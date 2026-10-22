package com.p9filmshare.app.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * 发送端（P9）的局域网探针：往 UDP 8422 广播，等接收端应答。
 *
 * 用于「历史连接」在双方连同一个 Wi-Fi（接收端没有开热点、IP 变了）的场景：
 * 不扫码也能找回收件端地址与最新 Token。
 *
 * 应答内容与二维码载荷同格式（P9SHARE2|...），另外把收到的源地址也一起返回，
 * 因为卓易通容器可能 NAT，载荷里写的 IP 未必可达，源地址反而通。
 */
public class LanProbe {

    public static final int PORT = 8422;
    private static final String PING = "P9SHARE?";

    public static class Found {
        public String payload;
        public String srcIp;
    }

    /** 广播搜索接收端，最多等 timeoutMs 毫秒；找不到返回 null。 */
    public static Found discover(int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(400);

            LinkedHashSet<String> targets = new LinkedHashSet<>();
            targets.add("255.255.255.255");
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (!(a instanceof java.net.Inet4Address)) continue;
                        String ip = a.getHostAddress();
                        if (ip == null || ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                        targets.add(subnetBroadcast(ip));
                    }
                }
            } catch (Throwable ignore) {
            }

            byte[] ping = PING.getBytes("UTF-8");
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            int round = 0;
            while (System.currentTimeMillis() < end) {
                if (round < 5) {
                    for (String t : targets) {
                        try {
                            s.send(new DatagramPacket(ping, ping.length,
                                    InetAddress.getByName(t), PORT));
                        } catch (Throwable ignore) {
                        }
                    }
                    round++;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                    if (text.startsWith("P9SHARE")) {
                        Found f = new Found();
                        f.payload = text;
                        f.srcIp = p.getAddress() == null ? null : p.getAddress().getHostAddress();
                        return f;
                    }
                } catch (SocketTimeoutException te) {
                    // 没应答，继续发下一轮
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return null;
    }

    private static String subnetBroadcast(String ip) {
        int i = ip.lastIndexOf('.');
        if (i <= 0) return ip;
        return ip.substring(0, i) + ".255";
    }
}
