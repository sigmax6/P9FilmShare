package com.p9filmshare.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.p9filmshare.app.client.LanProbe;
import com.p9filmshare.app.client.P9Client;
import com.p9filmshare.app.client.WifiJoiner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发送模式（P9 / EMUI 4 / Android 6）。
 *
 * 三种进入方式，最终都汇到「连上 → 选图 → 上传」：
 *   1) 扫码：扫鸿蒙设备上的二维码（含 SSID/密码/候选IP/端口/Token）；
 *   2) 历史连接：用上次成功的连接信息一键重连（不必再扫码）；
 *   3) 手动输入：极端情况下直接填 IP/端口/Token。
 *
 * 地址解析策略（宁可多花 1 秒，也不要把 P9 从正在用的 Wi-Fi 上踢下来）：
 *   a) 先探测「上次的地址 + 当前 Wi-Fi 网关」——如果 P9 已经在该热点上，网关就是鸿蒙设备；
 *   b) 再 UDP 广播搜索——双方连同一个 Wi-Fi 时靠这一步找回来；
 *   c) 都不通且当前不在该热点上，才真正去连那个热点，连上后再重复 a/b。
 * 每一步都用免鉴权的 /api/hello 握手，同时取回接收端最新 Token，避免历史 Token 过期。
 */
public class SenderActivity extends Activity {

    private static final int REQ_SCAN = 11;
    private static final int REQ_PICK = 12;

    private AppTheme theme;
    private TextView tvStatus;
    private Button btnScan;
    private Button btnManual;
    private Button btnHistory;
    private Button btnForget;

    private String hostIp;
    private int port = ShareService.PORT;
    private String token;
    private boolean busy;
    /** 接收端已收照片的 key 集合（name|size），用于增量传输：选图时标记“已传”。 */
    private Set<String> receivedKeys = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        theme = AppTheme.of(this);

        int pad = dp(20);
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
        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        ((LinearLayout.LayoutParams) strip.getLayoutParams()).bottomMargin = dp(14);

        Button btnBack = theme.button(this, "‹ 返回", false);
        btnBack.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("发送端（P9）");
        title.setTextSize(20);
        title.setTypeface(theme.mono());
        title.setTextColor(theme.text);
        title.setPadding(0, 0, 0, dp(4));

        TextView steps = new TextView(this);
        steps.setTextSize(13);
        steps.setTextColor(theme.muted);
        steps.setGravity(Gravity.START);
        steps.setText("在 鸿蒙设备 上打开本 App → 选「我要接收」，它会开启热点并给出二维码。\n"
                + "首次用下面的扫码按钮连一次；之后只要热点开着（或两台设备连同一个 Wi-Fi），"
                + "直接点「历史连接」就能自动接上并开始选照片。");
        steps.setPadding(0, 0, 0, dp(16));

        // 历史连接（有记录时才显示）
        btnHistory = theme.button(this, "🕘 历史连接", false);
        btnHistory.setTextSize(15);
        btnHistory.setOnClickListener(v -> goHistory());

        btnScan = theme.button(this, "📷 扫码连接并传照片", true);
        btnScan.setTextSize(16);
        btnScan.setOnClickListener(v -> startScan());

        btnManual = theme.button(this, "⌨ 手动输入（已知 IP / 端口 / Token）", false);
        btnManual.setTextSize(13);
        btnManual.setOnClickListener(v -> openManual());

        btnForget = theme.button(this, "清除历史连接", false);
        btnForget.setTextSize(12);
        btnForget.setOnClickListener(v -> {
            ConnStore.clearHistory(this);
            refreshHistoryButton();
            setStatus("已清除历史连接信息。");
        });

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(theme.text);
        tvStatus.setGravity(Gravity.START);
        tvStatus.setPadding(0, dp(16), 0, dp(8));
        tvStatus.setText("点「扫码连接并传照片」开始。");

        root.addView(btnBack);
        root.addView(title);
        root.addView(steps);
        root.addView(btnHistory);
        root.addView(btnScan);
        root.addView(btnManual);
        root.addView(btnForget);
        root.addView(tvStatus);
        setContentView(scroll);

        refreshHistoryButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHistoryButton();
    }

    /** 有历史记录时显示按钮并写明上次连的是谁。 */
    private void refreshHistoryButton() {
        try {
            ConnStore.History h = ConnStore.history(this);
            boolean has = h != null;
            btnHistory.setVisibility(has ? View.VISIBLE : View.GONE);
            btnForget.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                btnHistory.setText("🕘 历史连接：" + ConnStore.describe(h) + "\n（点这里一键重连）");
            }
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 扫码 ----------------

    private void startScan() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                Toast.makeText(this, "首次需授予相机权限", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Throwable e) {
            setStatus("打开扫码页失败：" + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == REQ_SCAN && resultCode == RESULT_OK && data != null) {
                String text = data.getStringExtra("text");
                QrUtil.Payload p = QrUtil.parsePayload(text);
                if (p == null) {
                    setStatus("这不是 P9FilmShare 的分享码。请对准 鸿蒙设备 上「接收」页下方那个二维码。");
                    return;
                }
                if (p.ip == null || p.ip.trim().isEmpty()) {
                    setStatus("二维码信息不全，请在 鸿蒙设备 上重新生成二维码再扫。");
                    return;
                }
                setStatus("已识别 鸿蒙设备：\n  SSID：" + p.ssid + "\n  地址：" + p.ip
                        + "  端口：" + p.port + "\n正在自动连接…");
                connectAndPick(p.ssid, p.pass, p.ip, p.port, p.token);
            } else if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
                ArrayList<Uri> uris = data.getParcelableArrayListExtra(PickerActivity.EXTRA_URIS);
                ArrayList<String> names = data.getStringArrayListExtra("names");
                if (uris == null || uris.isEmpty()) {
                    setStatus("没有选择任何照片。");
                    return;
                }
                uploadAll(uris, names);
            }
        } catch (Throwable e) {
            setStatus("处理出错：" + e);
        }
    }

    // ---------------- 历史连接 ----------------

    private void goHistory() {
        final ConnStore.History h = ConnStore.history(this);
        if (h == null) {
            Toast.makeText(this, "还没有历史连接，请先扫码连一次", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        setStatus("正在用历史连接重连…\n  " + ConnStore.describe(h));
        new Thread(() -> {
            Probe p;
            try {
                p = resolveAuto(h.ip, h.port, h.token, h.ssid, h.pass);
            } catch (Throwable e) {
                p = null;
            }
            final Probe fp = p;
            final Set<String> rcv = new HashSet<>();
            if (fp != null) {
                try {
                    List<String> lst = new P9Client("http://" + fp.ip + ":" + fp.port, fp.token, 3000)
                            .fetchReceived();
                    rcv.addAll(lst);
                } catch (Throwable ignore) {
                }
            }
            final Set<String> frcv = rcv;
            ui(() -> {
                setBusy(false);
                if (fp == null) {
                    setStatus("历史重连失败。请检查：\n"
                            + "· 鸿蒙设备的「接收」页是否还开着（个人热点/接收服务要运行）；\n"
                            + "· P9 是否已连上它的热点，或两台设备是否连了同一个 Wi-Fi；\n"
                            + "· 也可以点「📷 扫码连接」重新扫一次。");
                    return;
                }
                hostIp = fp.ip;
                port = fp.port;
                token = fp.token;
                receivedKeys = frcv;
                ConnStore.saveHistory(this, h.ssid, h.pass, fp.ip, fp.port, fp.token);
                refreshHistoryButton();
                setStatus("✅ 历史重连成功：" + fp.ip + ":" + fp.port
                        + "\n连接信息已更新。请在相册里勾选要传的照片…");
                openPicker();
            });
        }, "p9-history").start();
    }

    // ---------------- 扫码 / 手动 进入 ----------------

    private void connectAndPick(final String ssid, final String pass, final String ipCsv,
                                final int portHint, final String tokenHint) {
        setBusy(true);
        new Thread(() -> {
            Probe p;
            try {
                p = resolveAuto(ipCsv, portHint, tokenHint, ssid, pass);
            } catch (Throwable e) {
                p = null;
            }
            final Probe fp = p;
            final Set<String> rcv = new HashSet<>();
            if (fp != null) {
                try {
                    List<String> lst = new P9Client("http://" + fp.ip + ":" + fp.port, fp.token, 3000)
                            .fetchReceived();
                    rcv.addAll(lst);
                } catch (Throwable ignore) {
                }
            }
            final Set<String> frcv = rcv;
            ui(() -> {
                setBusy(false);
                if (fp == null) {
                    setStatus("连不上接收端。请确认：\n"
                            + "· 鸿蒙设备已开「接收」并打开了个人热点；\n"
                            + "· 或两台设备连在同一个 Wi-Fi 下；\n"
                            + "· 仍不行就用「⌨ 手动输入」填地址。");
                    return;
                }
                hostIp = fp.ip;
                port = fp.port;
                token = fp.token;
                receivedKeys = frcv;
                ConnStore.saveHistory(this, ssid, pass, fp.ip, fp.port, fp.token);
                refreshHistoryButton();
                setStatus("✅ 已连上接收端 " + fp.ip + ":" + fp.port
                        + "\n连接信息已保存，下次直接点「历史连接」。\n请在相册里勾选照片…");
                openPicker();
            });
        }, "p9-connect").start();
    }

    // ---------------- 地址解析（三级兜底） ----------------

    /**
     * 解析出一个真正可用的接收端地址。
     *
     * @param ipCsv     候选地址（逗号分隔，来自二维码或历史）
     * @param portHint  端口
     * @param tokenHint 旧 Token（可能已过期，握手成功会被最新 Token 覆盖）
     * @param ssid      热点 SSID（仅在必要时才真的去连它）
     * @param pass      热点密码
     */
    private Probe resolveAuto(String ipCsv, int portHint, String tokenHint,
                              String ssid, String pass) {
        final int port0 = portHint > 0 ? portHint : ShareService.PORT;

        // a) 直接探测已知地址 + 当前 Wi-Fi 网关（连热点时网关就是鸿蒙设备）
        Probe p = probeCandidates(ipCsv, port0, tokenHint);
        if (p != null) return p;

        // b) UDP 广播（双方连同一个 Wi-Fi 时靠这个找回）
        p = probeByBeacon(tokenHint);
        if (p != null) return p;

        // c) 最后才尝试切到该热点（避免把 P9 从正在用的 Wi-Fi 上踢下来）
        if (ssid != null && !ssid.isEmpty() && pass != null && pass.length() >= 8) {
            String cur = WifiJoiner.currentSsid(this);
            if (!ssid.equals(cur)) {
                setStatus("正在连接热点「" + ssid + "」…");
                WifiJoiner.connect(this, ssid, pass);
                for (int i = 0; i < 24; i++) {
                    if (WifiJoiner.isConnectedTo(this, ssid)) break;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignore) {
                    }
                }
                p = probeCandidates(ipCsv, port0, tokenHint);
                if (p == null) p = probeByBeacon(tokenHint);
            }
        }
        return p;
    }

    private Probe probeCandidates(String ipCsv, int port, String tokenHint) {
        ArrayList<String> cands = new ArrayList<>();
        for (String s : splitCsv(ipCsv)) {
            if (!cands.contains(s)) cands.add(s);
        }
        try {
            String gw = WifiJoiner.gatewayIp(this);
            if (gw != null && !cands.contains(gw)) cands.add(gw);
        } catch (Throwable ignore) {
        }

        int tried = 0;
        for (String ip : cands) {
            if (tried >= 5) break;
            tried++;
            setStatus("正在探测接收端 " + ip + ":" + port + " …");
            try {
                String t = new P9Client("http://" + ip + ":" + port, tokenHint, 1500).helloToken();
                Probe r = new Probe();
                r.ip = ip;
                r.port = port;
                r.token = (t == null || t.isEmpty()) ? tokenHint : t;
                return r;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private Probe probeByBeacon(String tokenHint) {
        setStatus("正在同一局域网内广播搜索接收端…");
        LanProbe.Found f = LanProbe.discover(2500);
        if (f == null) return null;
        QrUtil.Payload q = QrUtil.parsePayload(f.payload);
        if (q == null || q.ip == null) return null;

        ArrayList<String> more = new ArrayList<>();
        if (f.srcIp != null && !more.contains(f.srcIp)) more.add(f.srcIp);
        for (String s : splitCsv(q.ip)) {
            if (!more.contains(s)) more.add(s);
        }
        for (String ip : more) {
            try {
                String t = new P9Client("http://" + ip + ":" + q.port, q.token, 2500).helloToken();
                Probe r = new Probe();
                r.ip = ip;
                r.port = q.port;
                r.token = (t == null || t.isEmpty()) ? q.token : t;
                return r;
            } catch (Throwable ignore) {
            }
        }
        // 广播有应答说明对端在线；hello 可能被容器拦，退回该地址直接试上传
        Probe r = new Probe();
        r.ip = f.srcIp != null ? f.srcIp : q.ip;
        r.port = q.port;
        r.token = (q.token == null || q.token.isEmpty()) ? tokenHint : q.token;
        return r;
    }

    private static class Probe {
        String ip;
        int port;
        String token;
    }

    private static ArrayList<String> splitCsv(String csv) {
        ArrayList<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split("[,;\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    // ---------------- 批量选择 ----------------

    private void openPicker() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
                Toast.makeText(this, "需要存储权限来选择照片", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent it = new Intent(this, PickerActivity.class);
            it.putStringArrayListExtra("received", new ArrayList<>(receivedKeys));
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable e) {
            setStatus("打开相册失败：" + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grant) {
        if (requestCode == 1 && grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else if (requestCode == 2 && grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED) {
            openPicker();
        }
    }

    // ---------------- 上传 ----------------

    private void uploadAll(ArrayList<Uri> uris, ArrayList<String> names) {
        if (hostIp == null || token == null) {
            setStatus("缺少接收端信息，请重新扫码或点「历史连接」。");
            return;
        }
        setBusy(true);
        final int total = uris.size();
        final String ipSnapshot = hostIp;
        final int portSnapshot = port;
        final String tokenSnapshot = token;
        new Thread(() -> {
            P9Client client = new P9Client("http://" + ipSnapshot + ":" + portSnapshot, tokenSnapshot);
            int ok = 0;
            StringBuilder failed = new StringBuilder();
            for (int i = 0; i < total; i++) {
                final int idx = i;
                Uri uri = uris.get(i);
                String name = (names != null && i < names.size()) ? names.get(i) : ("photo_" + i + ".jpg");
                try {
                    ui(() -> setStatus("上传中 " + (idx + 1) + "/" + total + "  " + name + " …"));
                    client.upload(this, uri, name, (got, len) -> {
                    });
                    ok++;
                } catch (Throwable e) {
                    if (failed.length() > 0) failed.append("、");
                    failed.append(name);
                }
            }
            final int okCount = ok;
            final String failText = failed.toString();
            ui(() -> {
                setBusy(false);
                String msg = "✅ 完成：已上传 " + okCount + "/" + total + " 张到 鸿蒙设备。";
                if (!failText.isEmpty()) msg += "\n失败：" + failText;
                if (okCount > 0) msg += "\n到 鸿蒙设备 上看图库「华为分享」相册即可。";
                setStatus(msg);
                if (okCount > 0) {
                    ConnStore.touchHistory(this, ipSnapshot, portSnapshot, tokenSnapshot);
                    refreshHistoryButton();
                }
            });
        }, "p9-upload").start();
    }

    // ---------------- 手动模式 ----------------

    private void openManual() {
        final ConnStore.History h = ConnStore.history(this);
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText etIp = new EditText(this);
        etIp.setHint("接收端 IP，如 192.168.43.1");
        etIp.setSingleLine(true);
        EditText etPort = new EditText(this);
        etPort.setHint("端口，如 8421");
        etPort.setSingleLine(true);
        EditText etToken = new EditText(this);
        etToken.setHint("Token（可留空，会自动获取）");
        etToken.setSingleLine(true);
        if (h != null) {
            etIp.setText(h.ip);
            etPort.setText(String.valueOf(h.port));
            etToken.setText(h.token);
        } else {
            etPort.setText(String.valueOf(ShareService.PORT));
        }
        theme.styleInput(etIp);
        theme.styleInput(etPort);
        theme.styleInput(etToken);
        lay.addView(etIp);
        lay.addView(etPort);
        lay.addView(etToken);
        new AlertDialog.Builder(this)
                .setTitle("手动连接")
                .setMessage("适用于你已知接收端地址，或已在系统里手动连上热点的情况。")
                .setView(lay)
                .setPositiveButton("确定", (d, w) -> {
                    String ip = etIp.getText().toString().trim();
                    String p = etPort.getText().toString().trim();
                    String tk = etToken.getText().toString().trim();
                    if (ip.isEmpty()) {
                        Toast.makeText(this, "请填写 IP", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int pt = ShareService.PORT;
                    try {
                        if (!p.isEmpty()) pt = Integer.parseInt(p);
                    } catch (Exception ignore) {
                    }
                    final String ssid = (h == null) ? null : h.ssid;
                    final String pass = (h == null) ? null : h.pass;
                    setStatus("正在连接 " + ip + ":" + pt + " …");
                    connectAndPick(ssid, pass, ip, pt, tk);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 杂项 ----------------

    private void setBusy(boolean b) {
        busy = b;
        btnScan.setEnabled(!b);
        btnManual.setEnabled(!b);
        btnHistory.setEnabled(!b);
        btnForget.setEnabled(!b);
    }

    private void setStatus(String s) {
        ui(() -> tvStatus.setText(s));
    }

    private void ui(Runnable r) {
        try {
            runOnUiThread(r);
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
