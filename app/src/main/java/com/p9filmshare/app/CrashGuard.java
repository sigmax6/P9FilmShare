package com.p9filmshare.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简崩溃记录器。
 *
 * 卓易通容器里没有方便的 logcat，App 一崩溃就“闪退回桌面”，无从判断原因。
 * 这里安装一个全局未捕获异常处理器，把堆栈落到 files/last_crash.txt，
 * 下次进入主界面时展示出来，便于定位（也让“闪退”不再是无信息的黑盒）。
 */
public class CrashGuard {

    private static final String FILE_NAME = "last_crash.txt";

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String text = "time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + "\nthread=" + t.getName() + "\n" + sw;
                FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), FILE_NAME));
                fos.write(text.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignore) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    /** 读取并清除上次崩溃记录；没有则返回 null。 */
    public static String takeLast(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.isFile()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return bos.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }
}
