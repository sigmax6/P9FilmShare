package com.p9filmshare.app;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.EnumMap;
import java.util.Map;

/**
 * 使用 zxing core 生成二维码位图(无需 zxing-android-embedded)。
 *
 * P9FilmShare 扫码协议载荷（V2，当前使用）：
 *   P9SHARE2|<urlencode ssid>|<urlencode pass>|<urlencode ip>|<port>|<urlencode token>
 * 含义：鸿蒙设备是热点主机，二维码由鸿蒙设备生成、P9 扫码后自动连热点并上传。
 * 同时兼容旧版 P9SHARE1 格式（解析时两种都认）。
 */
public class QrUtil {

    /** 生成二维码，可指定误差校正等级（小屏用 L 模块更少、更易扫）。 */
    public static Bitmap encode(String content, int sizePx, ErrorCorrectionLevel ec) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ec == null ? ErrorCorrectionLevel.M : ec);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new MultiFormatWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        for (int x = 0; x < sizePx; x++) {
            for (int y = 0; y < sizePx; y++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return bmp;
    }

    /** 组包：鸿蒙设备接收端（热点主机）调用。 */
    public static String buildPayload(String ssid, String pass, String ip, int port, String token) {
        return "P9SHARE2|" + enc(ssid) + "|" + enc(pass) + "|" + enc(ip) + "|" + port + "|" + enc(token);
    }

    /** 解包：P9 发送端扫码后调用。失败返回 null。兼容 P9SHARE1 / P9SHARE2。 */
    public static Payload parsePayload(String q) {
        if (q == null) return null;
        // 二维码扫出来常常带前后空白或换行，先 trim
        String text = q.trim().replaceAll("\\s+", "");
        String[] p = text.split("\\|");
        if (p.length < 6) return null;
        if (!"P9SHARE1".equals(p[0]) && !"P9SHARE2".equals(p[0])) return null;
        try {
            Payload r = new Payload();
            r.ssid = dec(p[1]);
            r.pass = dec(p[2]);
            r.ip = dec(p[3]);
            r.port = Integer.parseInt(p[4]);
            r.token = dec(p[5]);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static class Payload {
        public String ssid;
        public String pass;
        public String ip;
        public int port;
        public String token;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
