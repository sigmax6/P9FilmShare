package com.p9filmshare.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 角色选择入口。同一个 APK：
 *  - 在华为 P9(EMUI 4 / Android 6) 上选“我要发送”：扫码/历史连接连上鸿蒙设备，批量选图上传；
 *  - 在鸿蒙设备的卓易通(安卓兼容容器) 里选“我要接收”：开个人热点并出二维码，接收照片。
 *
 * 传输前提只有一个：两台设备处于同一个局域网（同一 Wi-Fi，或手机开热点让 P9 连上）。
 *
 * v0.9.0 起：胶片照片风格界面 + 浅色/深色/跟随系统 三档主题切换；首页介绍文字左对齐。
 */
public class MainActivity extends Activity {

    private AppTheme theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先装崩溃记录器，避免“闪退且无任何信息”
        try {
            CrashGuard.install(this);
        } catch (Throwable ignore) {
        }
        theme = AppTheme.of(this);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.START);
        root.setPadding(pad, dp(20), pad, pad);
        root.setBackgroundDrawable(theme.background(this));

        // 顶部胶片条装饰
        View topStrip = theme.filmStripView(this, 18);
        root.addView(topStrip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        ((LinearLayout.LayoutParams) topStrip.getLayoutParams()).bottomMargin = dp(14);

        TextView brand = new TextView(this);
        brand.setText("P9FILMSHARE");
        brand.setTextSize(26);
        brand.setTypeface(theme.mono());
        brand.setTextColor(theme.accent);
        brand.setPadding(0, 0, 0, 0);

        TextView tagline = new TextView(this);
        tagline.setText("· FILM TRANSFER · 华为分享桥 ·");
        tagline.setTextSize(11);
        tagline.setTypeface(theme.mono());
        tagline.setTextColor(theme.muted);
        tagline.setPadding(0, dp(2), 0, dp(16));

        TextView sub = new TextView(this);
        sub.setText("同一个 APK：P9 装它当发送端，鸿蒙设备 用卓易通装它当接收端。\n"
                + "鸿蒙设备 当“热点主机”开个人热点并出二维码，P9 扫码连一次即可；\n"
                + "之后两端都会记住这次连接，直接点「历史连接」就能自动接上并传照片。");
        sub.setTextSize(13);
        sub.setTextColor(theme.muted);
        sub.setGravity(Gravity.START);
        sub.setLineSpacing(0, 1.25f);
        sub.setPadding(0, 0, 0, dp(20));

        // 主题切换：浅色 / 深色 / 跟随系统
        TextView labTheme = new TextView(this);
        labTheme.setText("外观主题");
        labTheme.setTextSize(12);
        labTheme.setTypeface(theme.mono());
        labTheme.setTextColor(theme.muted);
        labTheme.setPadding(0, 0, 0, dp(6));

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] labels = {"浅色", "深色", "跟随系统"};
        final int[] modes = {ConnStore.THEME_LIGHT, ConnStore.THEME_DARK, ConnStore.THEME_SYSTEM};
        final Button[] tbtns = new Button[labels.length];
        int cur = ConnStore.theme(this);
        for (int i = 0; i < labels.length; i++) {
            final int vi = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(10), dp(8), dp(10), dp(8));
            b.setOnClickListener(v -> {
                ConnStore.saveTheme(MainActivity.this, modes[vi]);
                recreate();   // 重新创建界面以应用主题
            });
            tbtns[i] = b;
            themeRow.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i < labels.length - 1) {
                View gap = new View(this);
                themeRow.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            }
        }
        refreshThemeButtons(tbtns, cur);

        Button btnSend = theme.button(this, "我要发送（P9 / 扫码或历史连接传照片）", false);
        btnSend.setOnClickListener(v ->
                startActivity(new Intent(this, SenderActivity.class)));

        Button btnRecv = theme.button(this, "我要接收（鸿蒙设备 · 热点主机）", false);
        btnRecv.setOnClickListener(v ->
                startActivity(new Intent(this, ReceiverActivity.class)));

        LinearLayout.LayoutParams wp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.bottomMargin = dp(12);

        root.addView(brand);
        root.addView(tagline);
        root.addView(sub);
        root.addView(labTheme);
        root.addView(themeRow);
        ((LinearLayout.LayoutParams) themeRow.getLayoutParams()).bottomMargin = dp(20);
        root.addView(btnSend, wp);
        root.addView(btnRecv, wp);

        // 若上次发生崩溃，直接把堆栈展示出来（便于反馈问题）
        String crash = null;
        try {
            crash = CrashGuard.takeLast(this);
        } catch (Throwable ignore) {
        }
        if (crash != null && !crash.isEmpty()) {
            TextView btnCrash = new TextView(this);
            btnCrash.setText("⚠ 上次运行发生过崩溃，点此查看原因");
            btnCrash.setTextSize(12);
            btnCrash.setTextColor(Color.parseColor("#C62828"));
            btnCrash.setGravity(Gravity.START);
            btnCrash.setPadding(0, dp(20), 0, dp(8));
            final String detail = crash;
            btnCrash.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("上次崩溃信息")
                    .setMessage(detail.length() > 3000 ? detail.substring(0, 3000) : detail)
                    .setPositiveButton("关闭", null)
                    .show());
            root.addView(btnCrash);
        }

        // 底部胶片条装饰
        View bottomStrip = theme.filmStripView(this, 14);
        root.addView(bottomStrip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));
        ((LinearLayout.LayoutParams) bottomStrip.getLayoutParams()).topMargin = dp(16);

        setContentView(root);
    }

    private void refreshThemeButtons(Button[] btns, int cur) {
        try {
            int[] modes = {ConnStore.THEME_LIGHT, ConnStore.THEME_DARK, ConnStore.THEME_SYSTEM};
            for (int i = 0; i < btns.length; i++) {
                boolean sel = modes[i] == cur;
                btns[i].setBackgroundColor(sel ? theme.accent : theme.surface);
                btns[i].setTextColor(sel ? (theme.dark ? 0xFF1A1208 : 0xFFFFFFFF) : theme.text);
                btns[i].setTypeface(theme.mono());
            }
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
