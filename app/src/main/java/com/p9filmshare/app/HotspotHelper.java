package com.p9filmshare.app;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Random;

/**
 * 个人热点开关（尽力而为）。
 *
 * 说明：Android 上第三方 App 通过反射 setWifiApEnabled 开启热点，在大多数 ROM（尤其鸿蒙/EMUI）
 * 上会被权限拦掉，所以 enable() 返回 true 只代表“API 调用没抛异常”，不代表热点真的开起来了。
 * 因此本类只负责“尽量开”，真正的可用性由 ReceiverActivity 的界面文案 + 手动兜底来保障：
 * 即使自动开失败，App 也会把 SSID / 密码显示在屏幕上、写进二维码，用户到系统设置里手动开即可。
 */
public class HotspotHelper {

    public static final String DEFAULT_SSID = "P9FilmShare_HMOS";

    /** 生成 8 位 WPA2 合规密码（字母+数字，避开易混字符）。 */
    public static String randomPass() {
        String a = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(a.charAt(r.nextInt(a.length())));
        return sb.toString();
    }

    /** 尽力开启热点（SSID/密码）。返回是否 API 调用成功。 */
    public static boolean enable(Context ctx, String ssid, String pass) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;

            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            cfg.preSharedKey = "\"" + pass + "\"";
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            cfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            cfg.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            cfg.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            cfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            cfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            cfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

            Method m = wm.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            Object ok = m.invoke(wm, cfg, true);
            return ok instanceof Boolean && (Boolean) ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 关闭热点。 */
    public static void disable(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            Method m = wm.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            m.invoke(wm, null, false);
        } catch (Throwable ignore) {
        }
    }

    /** 热点是否已开启（尽力探测）。 */
    public static boolean isEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            Method m = wm.getClass().getMethod("isWifiApEnabled");
            Object v = m.invoke(wm);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 取热点主机（AP）地址；Android 热点网关通常是 192.168.43.1，
     * 部分 ROM 用 192.168.44~46.x。找不到时回退 192.168.43.1。
     */
    public static String hotspotIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.")
                            || ip.startsWith("192.168.45.") || ip.startsWith("192.168.46.")) {
                        return ip;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return "192.168.43.1";
    }

    /**
     * 枚举本机所有可用的 IPv4 地址，按“被 P9 连上的可能性”从高到低排序：
     * 热点网段(192.168.43~46) &gt; 其它 192.168 &gt; 10.x &gt; 172.16~31 &gt; 其它。
     *
     * 卓易通容器可能同时有容器地址(172.17/10.x)和宿主 Wi-Fi 地址，这里全部返回，
     * 让 P9 逐个探测，哪个通就用哪个（IPv6/回环/链路本地已排除）。
     */
    public static java.util.List<String> localIps() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                    if (!out.contains(ip)) out.add(ip);
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            Collections.sort(out, (x, y) -> ipScore(y) - ipScore(x));
        } catch (Throwable ignore) {
        }
        return out;
    }

    /** 最可能被对端访问到的本机地址；一个都没取到时回退热点默认网关。 */
    public static String bestLocalIp() {
        java.util.List<String> ips = localIps();
        return ips.isEmpty() ? "192.168.43.1" : ips.get(0);
    }

    private static int ipScore(String ip) {
        if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.")
                || ip.startsWith("192.168.45.") || ip.startsWith("192.168.46.")) return 100;
        if (ip.startsWith("192.168.")) return 90;
        if (ip.startsWith("10.")) return 80;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return 70;
            } catch (Throwable ignore) {
            }
        }
        if (ip.startsWith("172.17.") || ip.startsWith("172.18.")) return 30; // 常见容器网段
        return 50;
    }
}
