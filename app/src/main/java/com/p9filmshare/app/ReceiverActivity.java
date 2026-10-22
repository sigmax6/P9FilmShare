package com.p9filmshare.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * 接收模式（鸿蒙设备的卓易通里运行）。本端是“热点主机 + 上传接收端”。
 *
 * 流程：
 *   1) 进入后加载**上次保存的**热点名称/密码（首次为随机密码），用户可随时修改；
 *   2) 点「开启热点并生成二维码」→ 保存配置 + 尽量自动开热点 + 启动前台上传服务；
 *   3) 二维码里含 热点SSID / 密码 / 本机IP(可能多个) / 端口 / Token；
 *   4) P9 扫码（或按历史连接）连上后批量上传，本端落盘到图库「华为分享」。
 *
 * v0.9.0 起：胶片照片风格界面（暖色胶片调 + 35mm 齿孔胶片条 + 二维码胶片相框）。
 */
public class ReceiverActivity extends Activity {

    /** 重复文件处理策略：0=询问 1=覆盖 2=复制 3=跳过 */
    private static final int DUP_ASK = 0;
    private static final int DUP_OVERWRITE = 1;
    private static final int DUP_COPY = 2;
    private static final int DUP_SKIP = 3;

    private static volatile ReceiverActivity current;

    private AppTheme theme;
    private TextView tvStatus;
    private TextView tvLog;
    private ImageView ivQr;
    private EditText etSsid;
    private EditText etPass;
    private Button btnEye;
    private boolean showPass = false;

    private String ssid;
    private String pass;
    private String token;

    private int dupPolicy;
    private int batchDecision = -1; // 批量决策：-1 未定，否则沿用该值直到本次接收结束
    private final Object dupLock = new Object();

    private Button[] dupBtns;
    private static final String[] DUP_LABELS = {"询问", "覆盖", "复制", "跳过"};

    private static final AsteriskTransformation ASTERISK = new AsteriskTransformation();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        theme = AppTheme.of(this);

        // 读取上次保存的配置：SSID 默认 P9FilmShare_HMOS，密码没有就随机生成一个
        ssid = ConnStore.rxSsid(this);
        pass = ConnStore.rxPass(this);
        if (pass == null || pass.length() < 8) {
            pass = HotspotHelper.randomPass();
            ConnStore.saveRxConfig(this, ssid, pass);
        }
        dupPolicy = ConnStore.rxDup(this);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.START);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundDrawable(theme.background(this));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部胶片条
        View strip = theme.filmStripView(this, 14);
        root.addView(strip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        ((LinearLayout.LayoutParams) strip.getLayoutParams()).bottomMargin = dp(14);

        Button btnBack = theme.button(this, "‹ 返回", false);
        btnBack.setOnClickListener(v -> {
            stopAll();
            finish();
        });

        TextView title = new TextView(this);
        title.setText("接收端（鸿蒙设备 · 热点主机）");
        title.setTextSize(18);
        title.setTypeface(theme.mono());
        title.setTextColor(theme.text);
        title.setPadding(0, dp(8), 0, dp(4));

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(theme.muted);
        hint.setGravity(Gravity.START);
        hint.setText("本机将开启个人热点，P9 扫码后自动连上并传照片。\n"
                + "热点名称与密码可自行修改，修改后点「开启热点并生成二维码」，配置会被记住。");
        hint.setPadding(0, 0, 0, dp(12));

        // ---- 热点名称(SSID) 输入 ----
        TextView labSsid = new TextView(this);
        labSsid.setText("热点名称(SSID)");
        labSsid.setTextSize(13);
        labSsid.setTypeface(theme.mono());
        labSsid.setTextColor(theme.muted);
        labSsid.setPadding(0, dp(4), 0, dp(2));
        etSsid = new EditText(this);
        etSsid.setSingleLine(true);
        etSsid.setText(ssid);
        etSsid.setHint("如 P9FilmShare_HMOS");
        theme.styleInput(etSsid);

        // ---- 热点密码 输入（星号隐藏 + 眼睛切换） ----
        TextView labPass = new TextView(this);
        labPass.setText("热点密码（WPA2，至少 8 位）");
        labPass.setTextSize(13);
        labPass.setTypeface(theme.mono());
        labPass.setTextColor(theme.muted);
        labPass.setPadding(0, dp(8), 0, dp(2));

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        etPass = new EditText(this);
        etPass.setSingleLine(true);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etPass.setText(pass);
        etPass.setHint("至少 8 位");
        etPass.setTransformationMethod(ASTERISK); // 默认以 * 号隐藏
        theme.styleInput(etPass);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        passRow.addView(etPass, etLp);

        btnEye = new Button(this);
        btnEye.setText("👁 显示");
        btnEye.setMinWidth(dp(64));
        theme.styleButton(btnEye, false);
        btnEye.setOnClickListener(v -> togglePassVisible());
        passRow.addView(btnEye);

        TextView saveHint = new TextView(this);
        saveHint.setText("上次的热点名称/密码已自动带出并会保存，不会再每次重置。\n"
                + "密码默认以 * 号隐藏，点眼睛按钮可查看。");
        saveHint.setTextSize(11);
        saveHint.setTextColor(theme.muted);
        saveHint.setGravity(Gravity.START);
        saveHint.setPadding(0, dp(2), 0, dp(12));

        Button btnStart = theme.button(this, "开启热点并生成二维码", true);

        // ---- 重复照片处理策略 ----
        TextView labDup = new TextView(this);
        labDup.setText("重复照片处理（再次收到同一张时）：");
        labDup.setTextSize(13);
        labDup.setTypeface(theme.mono());
        labDup.setTextColor(theme.muted);
        labDup.setPadding(0, dp(6), 0, dp(2));
        LinearLayout dupRow = new LinearLayout(this);
        dupRow.setOrientation(LinearLayout.HORIZONTAL);
        dupRow.setPadding(0, 0, 0, dp(12));
        dupBtns = new Button[DUP_LABELS.length];
        for (int i = 0; i < DUP_LABELS.length; i++) {
            final int vi = i;
            Button b = new Button(this);
            b.setText(DUP_LABELS[i]);
            b.setTextSize(13);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(6), dp(8), dp(6), dp(8));
            b.setOnClickListener(v -> {
                dupPolicy = vi;
                batchDecision = -1;
                ConnStore.saveRxDup(ReceiverActivity.this, vi);
                refreshDupButtons();
                note("重复处理策略 → " + DUP_LABELS[vi] + (vi == DUP_ASK ? "（每次询问）" : ""));
            });
            dupBtns[i] = b;
            dupRow.addView(b, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i < DUP_LABELS.length - 1) {
                View gap = new View(this);
                dupRow.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
            }
        }
        refreshDupButtons();

        // ---- 二维码（胶片相框） ----
        View qrStrip = theme.filmStripView(this, 12);
        FrameLayout qrFrame = new FrameLayout(this);
        qrFrame.setBackgroundColor(theme.filmBlack);
        qrFrame.setPadding(dp(12), dp(12), dp(12), dp(12));
        ivQr = new ImageView(this);
        ivQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int qrSide = qrSidePx();
        ivQr.setLayoutParams(new FrameLayout.LayoutParams(qrSide, qrSide, Gravity.CENTER));
        qrFrame.addView(ivQr);
        // 等宽说明标签，强化“胶片盒”观感
        TextView qrTag = new TextView(this);
        qrTag.setText("· SHARE CODE ·");
        qrTag.setTextSize(11);
        qrTag.setTypeface(theme.mono());
        qrTag.setTextColor(theme.muted);
        qrTag.setGravity(Gravity.CENTER);
        qrTag.setPadding(0, dp(8), 0, 0);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(theme.text);
        tvStatus.setGravity(Gravity.START);
        tvStatus.setPadding(0, dp(12), 0, dp(4));

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(theme.muted);
        tvLog.setGravity(Gravity.START);
        tvLog.setPadding(0, dp(4), 0, dp(8));

        Button btnRefresh = theme.button(this, "重新生成二维码", false);
        btnRefresh.setOnClickListener(v -> render());

        Button btnAuth = theme.button(this, "授权自动开热点（WRITE_SETTINGS）", false);
        btnAuth.setTextSize(12);
        btnAuth.setOnClickListener(v -> requestWriteSettings());

        Button btnDiag = theme.button(this, "ℹ 网络诊断（传不过去时看这个）", false);
        btnDiag.setTextSize(12);
        btnDiag.setOnClickListener(v -> showDiag());

        Button btnForget = theme.button(this, "清除已保存的接收端配置", false);
        btnForget.setTextSize(12);
        btnForget.setOnClickListener(v -> forgetSaved());

        Button btnStop = theme.button(this, "停止并关闭热点", false);
        btnStop.setOnClickListener(v -> {
            stopAll();
            finish();
        });

        root.addView(btnBack);
        root.addView(title);
        root.addView(hint);
        root.addView(labSsid);
        root.addView(etSsid);
        root.addView(labPass);
        root.addView(passRow);
        root.addView(saveHint);
        root.addView(btnStart);
        btnStart.setOnClickListener(v -> onStartClicked());
        root.addView(labDup);
        root.addView(dupRow);
        root.addView(qrStrip);
        root.addView(qrFrame);
        root.addView(qrTag);
        root.addView(tvStatus);
        root.addView(tvLog);
        root.addView(btnRefresh);
        root.addView(btnAuth);
        root.addView(btnDiag);
        root.addView(btnForget);
        root.addView(btnStop);
        setContentView(scroll);

        maybeRequestStorage();
        ensureToken();
        startServiceOnly();
        LanBeacon.start();   // UDP 应答器：让 P9 在同 Wi-Fi 时也能自动找回来
        render();
        ivQr.postDelayed(this::render, 1500);
    }

    private void refreshDupButtons() {
        if (dupBtns == null) return;
        for (int i = 0; i < dupBtns.length; i++) {
            boolean sel = (i == dupPolicy);
            dupBtns[i].setBackgroundColor(sel ? theme.accent : theme.surface);
            dupBtns[i].setTextColor(sel ? (theme.dark ? 0xFF1A1208 : Color.WHITE) : theme.text);
            dupBtns[i].setTypeface(theme.mono());
        }
    }

    private void togglePassVisible() {
        showPass = !showPass;
        if (showPass) {
            etPass.setTransformationMethod(null);
            btnEye.setText("🙈 隐藏");
        } else {
            etPass.setTransformationMethod(ASTERISK);
            btnEye.setText("👁 显示");
        }
        // 保持光标在末尾
        try {
            etPass.setSelection(etPass.getText().length());
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
    }

    @Override
    protected void onPause() {
        persistInputs();
        if (current == this) current = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        persistInputs();
        if (current == this) current = null;
        stopAll();
        LanBeacon.stop();
        super.onDestroy();
    }

    private void persistInputs() {
        try {
            String s = etSsid == null ? null : etSsid.getText().toString().trim();
            String p = etPass == null ? null : etPass.getText().toString();
            if (s != null && !s.isEmpty()) ssid = s;
            if (p != null && p.length() >= 8) pass = p;
            ConnStore.saveRxConfig(this, ssid, pass);
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 启动接收（起服务 + 出二维码） ----------------

    private void ensureToken() {
        // 优先用上次保存的 Token：这样历史连接里的 Token 长期有效（/api/hello 也兜底）
        String saved = ConnStore.rxToken(this);
        if (saved != null && !saved.isEmpty()) {
            ShareService.TOKEN = saved;
        } else if (ShareService.TOKEN == null) {
            ShareService.TOKEN = randomToken();
            ConnStore.saveRxToken(this, ShareService.TOKEN);
        }
        token = ShareService.TOKEN;
    }

    private void startServiceOnly() {
        try {
            Intent svc = new Intent(this, ShareService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception e) {
            Toast.makeText(this, "启动接收服务失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 用户点「开启热点并生成二维码」：读取输入值并保存，尽量开热点，刷新二维码。 */
    private void onStartClicked() {
        String s = etSsid.getText().toString().trim();
        String p = etPass.getText().toString();
        if (s.isEmpty()) {
            Toast.makeText(this, "热点名称(SSID)不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (p.length() < 8) {
            Toast.makeText(this, "热点密码至少 8 位（WPA2 要求）", Toast.LENGTH_SHORT).show();
            return;
        }
        ssid = s;
        pass = p;
        ConnStore.saveRxConfig(this, ssid, pass);   // 记住这次配置
        batchDecision = -1; // 每次开启都重新开始判断批量决策
        boolean called = HotspotHelper.enable(this, ssid, pass);
        if (!called) {
            Toast.makeText(this, "已保存配置；若系统未自动开热点，请到系统“个人热点”按此 SSID/密码手动开。",
                    Toast.LENGTH_LONG).show();
        }
        render();
        ivQr.postDelayed(this::render, 1500);
    }

    private void render() {
        boolean enabled = HotspotHelper.isEnabled(this);
        List<String> ips = HotspotHelper.localIps();
        if (ips.isEmpty()) ips.add("192.168.43.1");

        String ipField = joinCsv(ips, 3);
        String payload = QrUtil.buildPayload(ssid, pass, ipField, ShareService.PORT, token);
        // 同一份载荷给 UDP 应答器，P9 广播探测时能直接拿到
        LanBeacon.setPayload(payload);
        try {
            Bitmap qr = QrUtil.encode(payload, qrSidePx(), ErrorCorrectionLevel.L);
            ivQr.setImageBitmap(qr);
        } catch (Exception e) {
            Toast.makeText(this, "二维码生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        StringBuilder sb = new StringBuilder();
        if (enabled) {
            sb.append("✅ 个人热点已自动开启。\n\n");
        } else {
            sb.append("⚠ 未能自动开启热点（系统权限限制）。请手动开启：\n")
                    .append("  设置 → 移动网络共享 → 个人热点\n")
                    .append("  把「热点名称」设为下方 SSID、「密码」设为下方密码，然后打开热点。\n\n");
        }
        sb.append("热点名称(SSID)：").append(ssid).append("\n")
                .append("热点密码：").append(maskOf(pass)).append("（已隐藏，点上方 👁 可查看）\n")
                .append("本机地址：").append(joinCsv(ips, 3)).append("    端口：").append(ShareService.PORT).append("\n")
                .append("Token：").append(token).append("\n\n")
                .append("✅ 本次配置已保存，下次进入自动带出。\n")
                .append("· P9 首次使用：扫上面的二维码；\n")
                .append("· 之后：P9 点「历史连接」即可，热点开着或双方连同一个 Wi-Fi 都能自动连上；\n")
                .append("· 重复照片默认按「").append(DUP_LABELS[dupPolicy]).append("」处理；\n")
                .append("· 收到的照片会存进图库「华为分享」相册。");
        tvStatus.setText(sb.toString());
    }

    // ---------------- 权限 ----------------

    private void requestWriteSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                Toast.makeText(this, "授权后点「开启热点并生成二维码」再试自动开热点", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已具备 WRITE_SETTINGS 权限，点「开启热点并生成二维码」即可自动开热点", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable e) {
            Toast.makeText(this, "跳转授权失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    private void maybeRequestStorage() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
            }
        } catch (Throwable ignore) {
        }
    }

    private void forgetSaved() {
        try {
            ConnStore.saveRxConfig(this, HotspotHelper.DEFAULT_SSID, HotspotHelper.randomPass());
            ssid = ConnStore.rxSsid(this);
            pass = ConnStore.rxPass(this);
            etSsid.setText(ssid);
            etPass.setText(pass);
            render();
            Toast.makeText(this, "已恢复默认 SSID 与随机密码", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 停止 ----------------

    private void stopAll() {
        try {
            stopService(new Intent(this, ShareService.class));
        } catch (Exception ignore) {
        }
        try {
            HotspotHelper.disable(this);
        } catch (Exception ignore) {
        }
    }

    // ---------------- 重复文件决策（供 ShareService 回调） ----------------

    /**
     * 收到重复文件时由 ShareService 调用：弹对话框让用户决定覆盖/复制/跳过。
     * 策略为“询问”且界面在前台时弹窗；否则按已设策略或退化为“复制”。
     */
    public int resolveDuplicate(final String name, final String key) throws InterruptedException {
        synchronized (dupLock) {
            if (dupPolicy != DUP_ASK) {
                int r = toDupConst(dupPolicy);
                note(name + " → " + labelOf(r) + "（默认）");
                return r;
            }
            if (batchDecision >= 0) {
                return batchDecision;
            }
            if (current == null) {
                return HttpFileServer.DUP_COPY; // 界面不可见时，安全退化为复制
            }
            final int[] res = new int[1];
            final CountDownLatch latch = new CountDownLatch(1);
            current.runOnUiThread(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(current);
                b.setTitle("重复照片：" + name);
                b.setMessage("这张照片之前已经传过，本次怎么处理？");
                b.setPositiveButton("覆盖", (d, w) -> {
                    res[0] = HttpFileServer.DUP_OVERWRITE;
                    latch.countDown();
                });
                b.setNeutralButton("复制为新文件", (d, w) -> {
                    res[0] = HttpFileServer.DUP_COPY;
                    latch.countDown();
                });
                b.setNegativeButton("跳过", (d, w) -> {
                    res[0] = HttpFileServer.DUP_SKIP;
                    latch.countDown();
                });
                b.setItems(new CharSequence[]{"全部按“覆盖”", "全部按“复制”", "全部按“跳过”"}, (d, which) -> {
                    int v = (which == 0) ? HttpFileServer.DUP_OVERWRITE
                            : (which == 1) ? HttpFileServer.DUP_COPY : HttpFileServer.DUP_SKIP;
                    batchDecision = v;
                    res[0] = v;
                    latch.countDown();
                });
                b.setOnCancelListener(dlg -> {
                    res[0] = HttpFileServer.DUP_COPY;
                    latch.countDown();
                });
                try {
                    b.show();
                } catch (Throwable ignore) {
                    res[0] = HttpFileServer.DUP_COPY;
                    latch.countDown();
                }
            });
            latch.await();
            int r = res[0] < 0 ? HttpFileServer.DUP_COPY : res[0];
            note(name + " → " + labelOf(r));
            return r;
        }
    }

    /** 供 ShareService（同进程后台线程）调用的静态入口。 */
    public static int resolveStatic(String name, String key) throws InterruptedException {
        if (current == null) return HttpFileServer.DUP_COPY;
        return current.resolveDuplicate(name, key);
    }

    private static int toDupConst(int p) {
        switch (p) {
            case DUP_OVERWRITE: return HttpFileServer.DUP_OVERWRITE;
            case DUP_COPY: return HttpFileServer.DUP_COPY;
            case DUP_SKIP: return HttpFileServer.DUP_SKIP;
            default: return HttpFileServer.DUP_COPY;
        }
    }

    private static String labelOf(int c) {
        switch (c) {
            case HttpFileServer.DUP_OVERWRITE: return "覆盖";
            case HttpFileServer.DUP_COPY: return "复制";
            case HttpFileServer.DUP_SKIP: return "跳过";
            default: return "复制";
        }
    }

    /** 由 ShareService 在落盘后调用，回显到界面日志。 */
    public static void note(String msg) {
        ReceiverActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> a.appendLog(msg));
    }

    private void appendLog(String msg) {
        try {
            String cur = tvLog.getText().toString();
            tvLog.setText(msg + "\n" + cur);
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 网络诊断 ----------------

    private void showDiag() {
        StringBuilder sb = new StringBuilder("App 视角的网卡地址：\n");
        try {
            for (java.net.NetworkInterface ni :
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) {
                        sb.append("  ").append(ni.getName()).append("   ")
                                .append(a.getHostAddress()).append("\n");
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("  读取失败：").append(t).append("\n");
        }
        sb.append("\n判断方法：\n")
                .append("• 若上面有 192.168.43.x / 44.x 这类地址，说明热点接口已起来，P9 扫码能连；\n")
                .append("• 若只有 172.17 / 10.x 这类容器地址而看不到 192.168.x.x，\n")
                .append("  说明卓易通是 NAT 网络。P9 侧已会自动改试「网关地址」，一般仍能连通；\n")
                .append("  若仍不行，可在 P9 的「手动输入」里直接填 192.168.43.1。\n")
                .append("• 双方连同一个 Wi-Fi 时，P9 点「历史连接」会自动广播搜索本机。");
        try {
            new AlertDialog.Builder(this)
                    .setTitle("网络诊断")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 工具 ----------------

    /** 密码掩码：只保留位数，全部显示为 *。 */
    static String maskOf(String p) {
        if (p == null || p.isEmpty()) return "（未设置）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) sb.append('*');
        return sb.toString();
    }

    private static String joinCsv(List<String> list, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : list) {
            if (s == null || s.isEmpty()) continue;
            if (n >= max) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
            n++;
        }
        return sb.length() == 0 ? "192.168.43.1" : sb.toString();
    }

    /** 密码以星号(*)显示，而非系统默认的实心圆点。 */
    private static class AsteriskTransformation extends PasswordTransformationMethod {
        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            return new AsteriskCharSequence(source);
        }

        private static class AsteriskCharSequence implements CharSequence {
            private final CharSequence source;

            AsteriskCharSequence(CharSequence source) {
                this.source = source;
            }

            @Override
            public int length() {
                return source.length();
            }

            @Override
            public char charAt(int index) {
                return '*';
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return new AsteriskCharSequence(source.subSequence(start, end));
            }
        }
    }

    private int qrSidePx() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int side = (int) (w * 0.82);
        return Math.min(side, 640);
    }

    private static String randomToken() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 16; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
