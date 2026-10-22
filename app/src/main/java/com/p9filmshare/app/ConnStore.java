package com.p9filmshare.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 连接信息持久化（SharedPreferences）。
 *
 * 两端各存一半：
 *  - 接收端（鸿蒙设备）：保存用户改过的 热点名称/密码，以及一个稳定的 Token，
 *    这样下次打开 App 不用重新配、二维码里的凭据也不变；
 *  - 发送端（P9）：保存「上一次连成功」的连接信息（热点 SSID/密码 + 接收端地址/端口/Token），
 *    供「历史连接」按钮一键重连。
 *
 * 注意：Token 只是局域网内的一次性隔离手段，接收端每次点“接收”后会通过 /api/hello
 * 免鉴权告知最新 Token，所以历史里的 Token 过期也不会导致连不上。
 */
public class ConnStore {

    private static final String SP = "p9share_store";

    private static final String K_RX_SSID = "rx_ssid";
    private static final String K_RX_PASS = "rx_pass";
    private static final String K_RX_TOKEN = "rx_token";
    private static final String K_RX_DUP = "rx_dup"; // 重复文件处理策略：0询问 1覆盖 2复制 3跳过
    private static final String K_THEME = "theme";   // 0浅色 1深色 2跟随系统

    /** 主题模式：0=浅色 1=深色 2=跟随系统（默认）。 */
    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_SYSTEM = 2;

    private static final String K_TX_SSID = "tx_ssid";
    private static final String K_TX_PASS = "tx_pass";
    private static final String K_TX_IP = "tx_ip";
    private static final String K_TX_PORT = "tx_port";
    private static final String K_TX_TOKEN = "tx_token";
    private static final String K_TX_AT = "tx_at";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 接收端配置
    // ------------------------------------------------------------------

    public static String rxSsid(Context c) {
        String v = sp(c).getString(K_RX_SSID, "");
        return (v == null || v.trim().isEmpty()) ? HotspotHelper.DEFAULT_SSID : v;
    }

    /** 上次保存的热点密码；没有则返回空串（调用方自行生成随机密码）。 */
    public static String rxPass(Context c) {
        String v = sp(c).getString(K_RX_PASS, "");
        return v == null ? "" : v;
    }

    public static String rxToken(Context c) {
        String v = sp(c).getString(K_RX_TOKEN, "");
        return v == null ? "" : v;
    }

    public static void saveRxToken(Context c, String token) {
        if (token == null || token.isEmpty()) return;
        sp(c).edit().putString(K_RX_TOKEN, token).apply();
    }

    /** 重复文件处理策略：0=询问 1=覆盖 2=复制 3=跳过，默认 0。 */
    public static int rxDup(Context c) {
        return sp(c).getInt(K_RX_DUP, 0);
    }

    public static void saveRxDup(Context c, int v) {
        sp(c).edit().putInt(K_RX_DUP, v).apply();
    }

    // ------------------------------------------------------------------
    // 主题（浅色 / 深色 / 跟随系统）
    // ------------------------------------------------------------------

    /** 当前主题模式；默认跟随系统。 */
    public static int theme(Context c) {
        return sp(c).getInt(K_THEME, THEME_SYSTEM);
    }

    public static void saveTheme(Context c, int v) {
        sp(c).edit().putInt(K_THEME, v).apply();
    }

    /** 保存接收端热点凭据（只在校验通过后调用）。 */
    public static void saveRxConfig(Context c, String ssid, String pass) {
        SharedPreferences.Editor e = sp(c).edit();
        if (ssid != null && !ssid.trim().isEmpty()) e.putString(K_RX_SSID, ssid.trim());
        if (pass != null && pass.length() >= 8) e.putString(K_RX_PASS, pass);
        e.apply();
    }

    // ------------------------------------------------------------------
    // 发送端历史连接
    // ------------------------------------------------------------------

    public static boolean hasHistory(Context c) {
        String ip = sp(c).getString(K_TX_IP, "");
        return ip != null && !ip.trim().isEmpty();
    }

    /** 读取历史连接；没有则返回 null。 */
    public static History history(Context c) {
        SharedPreferences p = sp(c);
        String ip = p.getString(K_TX_IP, "");
        if (ip == null || ip.trim().isEmpty()) return null;
        History h = new History();
        h.ssid = p.getString(K_TX_SSID, "");
        h.pass = p.getString(K_TX_PASS, "");
        h.ip = ip;
        h.port = p.getInt(K_TX_PORT, ShareService.PORT);
        h.token = p.getString(K_TX_TOKEN, "");
        h.at = p.getLong(K_TX_AT, 0L);
        return h;
    }

    public static void saveHistory(Context c, String ssid, String pass,
                                   String ip, int port, String token) {
        if (ip == null || ip.trim().isEmpty()) return;
        SharedPreferences.Editor e = sp(c).edit();
        e.putString(K_TX_SSID, ssid == null ? "" : ssid);
        if (pass != null && !pass.isEmpty()) e.putString(K_TX_PASS, pass);
        e.putString(K_TX_IP, ip.trim());
        e.putInt(K_TX_PORT, port > 0 ? port : ShareService.PORT);
        if (token != null && !token.isEmpty()) e.putString(K_TX_TOKEN, token);
        e.putLong(K_TX_AT, System.currentTimeMillis());
        e.apply();
    }

    /** 只更新地址/Token（历史已存在时用，不覆盖 SSID/密码）。 */
    public static void touchHistory(Context c, String ip, int port, String token) {
        History h = history(c);
        if (h == null) return;
        saveHistory(c, h.ssid, h.pass, ip, port, token);
    }

    public static void clearHistory(Context c) {
        sp(c).edit()
                .remove(K_TX_SSID).remove(K_TX_PASS).remove(K_TX_IP)
                .remove(K_TX_PORT).remove(K_TX_TOKEN).remove(K_TX_AT)
                .apply();
    }

    /** 历史连接的展示文本，如 “P9FilmShare_HMOS · 192.168.43.1:8421 · 10-21 23:10”。 */
    public static String describe(History h) {
        if (h == null) return "（无）";
        String ssid = (h.ssid == null || h.ssid.isEmpty()) ? "（未记录热点）" : h.ssid;
        String time = "";
        try {
            if (h.at > 0) {
                time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(h.at));
            }
        } catch (Throwable ignore) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ssid).append(" · ").append(h.ip).append(':').append(h.port);
        if (!time.isEmpty()) sb.append(" · ").append(time);
        return sb.toString();
    }

    public static class History {
        public String ssid;
        public String pass;
        public String ip;
        public int port;
        public String token;
        public long at;
    }
}
