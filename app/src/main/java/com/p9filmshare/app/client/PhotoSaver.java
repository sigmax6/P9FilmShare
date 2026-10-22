package com.p9filmshare.app.client;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 把接收到的照片落到“能被系统图库看到”的位置，双策略：
 *
 *  - Android 10+(含卓易通安卓16内核)：用 MediaStore 写入
 *      Pictures/华为分享，系统媒体库立即可见；
 *      在鸿蒙7 + 卓易通“文件互通”环境下可同步/导出到宿主图库。
 *  - Android 9 及以下(含 P9)：直接写入公共目录
 *      /sdcard/Pictures/华为分享，并触发媒体扫描。
 *
 * 与 v0.7 的区别：新增「覆盖 / 复制为新文件」两种语义：
 *  - save(..., unique=false)：以指定文件名落盘，若已存在同名则“覆盖”（调用方先 deleteByDisplayName 删旧）；
 *  - save(..., unique=true) ：文件名重名时自动加 (1)/(2)… 后缀，避免冲突（用于“复制为新文件”）；
 *  - deleteByDisplayName(...) ：删除本相册内同名条目，配合 unique=false 实现真正的覆盖。
 *
 * 方法返回「实际使用的文件名」，便于 ShareService 记录到 TransferLog。
 */
public class PhotoSaver {

    public static final String ALBUM_DIR = "华为分享";
    private static final String REL = Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR;

    /** 以指定最终文件名落盘（unique=true 时若重名自动加 (1) 后缀）。返回实际使用的文件名。 */
    public static String save(Context ctx, File src, String fileName, String mime, boolean unique) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            return saveViaMediaStore(ctx, src, fileName, mime, unique);
        } else {
            return saveLegacy(src, fileName, mime, unique);
        }
    }

    private static String saveViaMediaStore(Context ctx, File src, String fileName, String mime, boolean unique)
            throws Exception {
        String name = unique ? findUniqueName(ctx, fileName) : fileName;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime != null ? mime : "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, REL);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = ctx.getContentResolver().insert(collection, v);
        if (item == null) throw new RuntimeException("MediaStore insert failed");

        try (OutputStream os = ctx.getContentResolver().openOutputStream(item);
             FileInputStream fis = new FileInputStream(src)) {
            if (os == null) throw new RuntimeException("openOutputStream failed");
            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = fis.read(buf)) > 0) os.write(buf, 0, r);
            os.flush();
        }

        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        ctx.getContentResolver().update(item, v, null, null);
        return name;
    }

    /** 删除本相册里“精确同名”的已有条目，用于“覆盖”。找不到也不报错。 */
    public static void deleteByDisplayName(Context ctx, String fileName) {
        try {
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String sel = MediaStore.Images.Media.DISPLAY_NAME + "=? AND "
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = new String[]{fileName, "%" + ALBUM_DIR + "%"};
            ctx.getContentResolver().delete(collection, sel, args);
        } catch (Throwable ignore) {
        }
    }

    /** 列出本相册内已有的显示名，避免复制时重名冲突。 */
    private static Set<String> existingNames(Context ctx) {
        Set<String> exist = new HashSet<>();
        try {
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] proj = {MediaStore.Images.Media.DISPLAY_NAME};
            String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            Cursor c = ctx.getContentResolver().query(collection, proj, sel,
                    new String[]{"%" + ALBUM_DIR + "%"}, null);
            if (c != null) {
                int idx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                while (c.moveToNext()) {
                    if (idx >= 0) {
                        String n = c.getString(idx);
                        if (n != null) exist.add(n);
                    }
                }
                c.close();
            }
        } catch (Throwable ignore) {
        }
        return exist;
    }

    private static String findUniqueName(Context ctx, String fileName) {
        Set<String> exist = existingNames(ctx);
        if (!exist.contains(fileName)) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        int i = 1;
        while (true) {
            String cand = base + "(" + i + ")" + ext;
            if (!exist.contains(cand)) return cand;
            i++;
        }
    }

    @SuppressWarnings("deprecation")
    private static String saveLegacy(File src, String fileName, String mime, boolean unique) throws Exception {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("mkdirs failed: " + dir);
        }
        File dest = unique ? uniqueFile(dir, fileName) : new File(dir, fileName);
        try (FileInputStream fis = new FileInputStream(src);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
            fos.flush();
        }
        return dest.getName();
    }

    @SuppressWarnings("deprecation")
    public static void deleteLegacy(String fileName) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM_DIR);
            File f = new File(dir, fileName);
            if (f.exists()) f.delete();
        } catch (Throwable ignore) {
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (true) {
            File cand = new File(dir, base + "_" + i + ext);
            if (!cand.exists()) return cand;
            i++;
        }
    }
}
