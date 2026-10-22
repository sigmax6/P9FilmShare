package com.p9filmshare.app.client;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.util.List;

/**
 * 让 P9(Android 6 / EMUI 4) 连接到鸿蒙设备开的个人热点。
 *
 * 关键点：Android 6 上第三方 App 可以用 WifiManager.addNetwork + enableNetwork 主动连一个
 * 已知 SSID/密码的 WPA/WPA2 热点（不需要 Wi-Fi 直连，也不需要定位权限），这正是本方案
 * 能在 EMUI 4 上跑通的原因。
 */
public class WifiJoiner {

    /** 连接指定 WPA/WPA2 热点。返回 networkId（>=0 表示已加入，不代表已拿到 IP）。 */
    public static int connect(Context ctx, String ssid, String pass) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return -1;

            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            int netId = -1;
            if (list != null) {
                for (WifiConfiguration c : list) {
                    if (c.SSID != null && c.SSID.equals("\"" + ssid + "\"")) {
                        netId = c.networkId;
                        break;
                    }
                }
            }
            if (netId == -1) {
                WifiConfiguration cfg = new WifiConfiguration();
                cfg.SSID = "\"" + ssid + "\"";
                cfg.preSharedKey = "\"" + pass + "\"";
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                netId = wm.addNetwork(cfg);
            }
            if (netId == -1) return -1;

            wm.disconnect();
            wm.enableNetwork(netId, true);
            wm.reconnect();
            return netId;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 是否已连接到指定 SSID。 */
    public static boolean isConnectedTo(Context ctx, String ssid) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            String cur = wm.getConnectionInfo().getSSID();
            return cur != null && cur.equals("\"" + ssid + "\"");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前连着的 SSID（不含引号）；没连返回 null。 */
    public static String currentSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            String cur = wm.getConnectionInfo().getSSID();
            if (cur == null) return null;
            cur = cur.replace("\"", "").trim();
            if (cur.isEmpty() || "<unknown ssid>".equals(cur)) return null;
            return cur;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前 Wi-Fi 的默认网关地址。
     *
     * 用途：连上「鸿蒙设备热点」后，网关就是鸿蒙设备自己（无论它是 192.168.43.1 还是 44.x/45.x），
     * 比历史里存的 IP 更可靠，所以历史重连会把它作为一个候选地址。
     */
    public static String gatewayIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            android.net.DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "."
                    + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }
}
