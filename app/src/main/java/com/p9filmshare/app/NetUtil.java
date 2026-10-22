package com.p9filmshare.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 枚举本机 IPv4 地址。
 * 当 P9 开启便携热点且未连接其它 Wi-Fi 时, 老版本安卓热点网关通常是 192.168.43.1；
 * 同 Wi-Fi 场景则是当前 WLAN IP。全部列出，由接收端扫码后自动尝试。
 */
public class NetUtil {
    public static List<String> localIpv4() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> all = Collections.list(ifaces);
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) result.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 老安卓热点网关兜底
        if (!result.contains("192.168.43.1")) result.add("192.168.43.1");
        return result;
    }

    /**
     * 取当前 WLAN(或以太网) 上的 IPv4 地址，用于把地址展示给接收端。
     * 优先 wlan0/wifi/eth 接口，跳过 p2p 与回环。
     */
    public static String wifiIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getName() == null ? "" : ni.getName()).toLowerCase();
                if (name.startsWith("p2p") || name.startsWith("dummy")) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) continue;
                    if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fallback == null ? "0.0.0.0" : fallback;
    }

    /**
     * 取指定网络接口(如 Wi-Fi 直连组的 p2p-p2p0)上首个 IPv4 地址。
     * 找不到时回退到 Wi-Fi 直连组 Owner 的惯例地址 192.168.49.1。
     */
    public static String p2pIp(String ifaceName) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (ifaceName != null && !ifaceName.isEmpty() && !ifaceName.equals(ni.getName())
                        && !ifaceName.equals(ni.getDisplayName())) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            if (ifaceName != null && !ifaceName.isEmpty()) return ip;
                            // 无指定接口时, 优先返回 192.168.49.x 直连网段
                            if (ip.startsWith("192.168.49.")) return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "192.168.49.1";
    }
}
