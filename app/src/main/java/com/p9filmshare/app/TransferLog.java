package com.p9filmshare.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 接收端已收文件的持久化记录（SharedPreferences 里存一个 JSON 数组）。
 *
 * 用途：实现“增量传输”——记录每一张已经传到本机的照片的标识（name|size），
 * 再次收到同一张时由 ShareService 判定为重复，进而询问用户“覆盖 / 复制 / 跳过”。
 *
 * key 采用 “显示名|字节大小”，无需计算哈希，速度快；对同一型号手机来说足以标识“同一张照片”。
 */
public class TransferLog {

    private static final String SP = "p9share_rxlog";
    private static final String K_LOG = "log";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static JSONArray arr(Context c) {
        String s = sp(c).getString(K_LOG, "[]");
        try {
            return new JSONArray(s);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** 该 key（name|size）是否已收过。 */
    public static boolean exists(Context c, String key) {
        JSONArray a = arr(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && key.equals(o.optString("k"))) return true;
        }
        return false;
    }

    /** 新增一条记录（已存在则刷新时间）。 */
    public static void add(Context c, String key, String name) {
        try {
            JSONArray a = arr(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && key.equals(o.optString("k"))) {
                    o.put("t", System.currentTimeMillis());
                    o.put("n", name);
                    save(c, a);
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("k", key);
            o.put("n", name);
            o.put("t", System.currentTimeMillis());
            a.put(o);
            save(c, a);
        } catch (Throwable ignore) {
        }
    }

    /** 仅刷新时间（覆盖场景用，key 不变）。 */
    public static void touch(Context c, String key) {
        try {
            JSONArray a = arr(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && key.equals(o.optString("k"))) {
                    o.put("t", System.currentTimeMillis());
                    save(c, a);
                    return;
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 所有已收 key 列表（供发送端做增量比对）。 */
    public static List<String> keys(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray a = arr(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                String k = o.optString("k", "");
                if (!k.isEmpty()) out.add(k);
            }
        }
        return out;
    }

    /** 输出 JSON 数组字符串（/api/received 用）。 */
    public static String namesJson(Context c) {
        try {
            JSONArray a = arr(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    String k = o.optString("k", "");
                    if (!k.isEmpty()) out.put(k);
                }
            }
            return out.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    private static void save(Context c, JSONArray a) {
        sp(c).edit().putString(K_LOG, a.toString()).apply();
    }
}
