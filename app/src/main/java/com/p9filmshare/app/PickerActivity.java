package com.p9filmshare.app;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P9 发送端用的批量照片选择界面。
 *
 * v0.9.0 起：网格每格呈现“胶片黑框 + 内嵌照片”的印相接触 sheet 观感；选中角标用主题强调色；
 * 整体胶片照片风格（暖色调 + 颗粒背景 + 等宽字体）。
 *
 * 增量传输：
 *  1) 读取每张照片的“字节大小”，与文件名拼成 key（name|size），与接收端 TransferLog 对齐；
 *  2) 进入时收到接收端已收照片的 key 列表，把“已传过”的照片打上「已传」标记；
 *  3) 「全选」默认只选“未传过”的照片（增量：不重复传）；已传照片仍可单独点选，
 *     再次上传时接收端会弹“覆盖 / 复制 / 跳过”让用户决定。
 *  4) 选中标记仍是覆盖在缩略图右上角的角标，点格子任意位置都能切换选中。
 */
public class PickerActivity extends Activity {

    public static final String EXTRA_URIS = "uris";

    private static final int REQ_STORAGE = 300;
    /** 最多读取多少张（避免老机型内存/扫描压力）。 */
    private static final int MAX_ITEMS = 1200;
    private static final String ALL_BUCKET = "全部相册";

    private AppTheme theme;

    /** 全量照片。 */
    private final ArrayList<ImageItem> all = new ArrayList<>();
    /** 当前相册下展示的照片。 */
    private final ArrayList<ImageItem> shown = new ArrayList<>();
    /** 已选照片的 ID（跨相册保留）。 */
    private final Set<Long> selected = new LinkedHashSet<>();
    /** bucket 名称列表：index 0 恒为“全部相册”。 */
    private final ArrayList<String> bucketNames = new ArrayList<>();
    /** 接收端已收照片的 key 集合（name|size）。 */
    private final Set<String> receivedKeys = new HashSet<>();
    /** 正在后台解码的缩略图，避免重复排队。 */
    private final Set<Long> loadingThumbs =
            Collections.synchronizedSet(new HashSet<Long>());

    private final LruCache<Long, Bitmap> thumbCache = new LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return Math.max(1, value.getByteCount());
        }
    };

    private ExecutorService pool;
    private GridView grid;
    private ImageAdapter adapter;
    private TextView tvCount;
    private Button btnSend;
    private Spinner spAlbum;
    private boolean fillingSpinner;

    static class ImageItem {
        long id;
        String name;
        String bucket;
        long size;       // 字节数
        String key;      // name|size，与接收端 TransferLog 对齐
        Uri uri;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pool = Executors.newFixedThreadPool(4);
        theme = AppTheme.of(this);

        // 接收端已收照片列表（增量标记用）
        try {
            ArrayList<String> recv = getIntent().getStringArrayListExtra("received");
            if (recv != null) receivedKeys.addAll(recv);
        } catch (Throwable ignore) {
        }

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundDrawable(theme.background(this));

        // 顶部胶片条
        View strip = theme.filmStripView(this, 14);
        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        ((LinearLayout.LayoutParams) strip.getLayoutParams()).bottomMargin = dp(12);

        TextView title = new TextView(this);
        title.setText("选择要传输的照片");
        title.setTextSize(18);
        title.setTypeface(theme.mono());
        title.setTextColor(theme.text);
        title.setPadding(0, 0, 0, dp(6));

        // ---- 相册（文件夹）筛选 ----
        LinearLayout albumRow = new LinearLayout(this);
        albumRow.setOrientation(LinearLayout.HORIZONTAL);
        albumRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView labAlbum = new TextView(this);
        labAlbum.setText("相册：");
        labAlbum.setTextSize(13);
        labAlbum.setTypeface(theme.mono());
        labAlbum.setTextColor(theme.muted);
        spAlbum = new Spinner(this);
        spAlbum.setMinimumWidth(0);
        theme.styleSpinner(spAlbum);
        albumRow.addView(labAlbum);
        albumRow.addView(spAlbum, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        spAlbum.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (fillingSpinner) return;
                applyFilter(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        tvCount = new TextView(this);
        tvCount.setTextSize(13);
        tvCount.setTextColor(theme.muted);
        tvCount.setText("正在加载照片…");
        tvCount.setPadding(0, dp(4), 0, dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnAll = theme.button(this, "全选(未传)", false);
        btnAll.setOnClickListener(v -> {
            // 增量：全选只选“未传过”的
            for (ImageItem it : shown) {
                if (!receivedKeys.contains(it.key)) selected.add(it.id);
            }
            adapter.notifyDataSetChanged();
            updateCount();
        });
        Button btnNone = theme.button(this, "清空", false);
        btnNone.setOnClickListener(v -> {
            selected.clear();
            adapter.notifyDataSetChanged();
            updateCount();
        });
        btnSend = theme.button(this, "开始传输", true);
        btnSend.setOnClickListener(v -> startTransfer());
        Button btnCancel = theme.button(this, "取消", false);
        btnCancel.setOnClickListener(v -> finish());

        // 四等分权重：无论屏宽多窄（P9 等小屏），四个按钮都各占 1/4，绝不挤出屏幕。
        // 缩小内边距，按钮间留 6dp 间隙；字号在布局完成后按可用宽度自适应收缩。
        Button[] barBtns = new Button[]{btnAll, btnNone, btnSend, btnCancel};
        for (int i = 0; i < barBtns.length; i++) {
            Button b = barBtns[i];
            b.setMaxLines(1);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinEms(0);
            b.setPadding(dp(4), dp(10), dp(4), dp(10));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) blp.setMarginStart(dp(6));
            bar.addView(b, blp);
            b.addOnLayoutChangeListener(
                    (v, l, t, rr, bb, ol, ot, orr, obb) -> fitButtonText((Button) v, 15, 9));
        }

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setBackgroundColor(theme.surface);
        adapter = new ImageAdapter();
        grid.setAdapter(adapter);

        root.addView(title);
        root.addView(albumRow);
        root.addView(tvCount);
        root.addView(bar);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        ensureStorage();
        new Thread(this::loadImages, "picker-load").start();
    }

    @Override
    protected void onDestroy() {
        try {
            if (pool != null) pool.shutdownNow();
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    private void ensureStorage() {
        // 图库读取在 Android 6 需要 READ_EXTERNAL_STORAGE；直接触发系统授权弹窗
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grant) {
        if (requestCode == REQ_STORAGE) {
            new Thread(this::loadImages, "picker-load").start();
        }
    }

    // ------------------------------------------------------------------
    // 读取图库 + 相册分组
    // ------------------------------------------------------------------

    private void loadImages() {
        final ArrayList<ImageItem> list = readFromMediaStore();
        ui(() -> {
            all.clear();
            all.addAll(list);
            rebuildAlbums();
            updateCount();
        });
    }

    /**
     * 读 MediaStore。三级降级：
     *  1) 带 bucket_display_name + SIZE（相册名 + 大小，推荐）；
     *  2) 带 _data（老系统可用，从路径推导文件夹名）；
     *  3) 只有基础列，全部归到“其他”。
     */
    private ArrayList<ImageItem> readFromMediaStore() {
        ArrayList<ImageItem> list = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        String[][] projections = new String[][]{
                {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.MIME_TYPE, "bucket_display_name",
                        MediaStore.Images.Media.SIZE},
                {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.MIME_TYPE, "_data",
                        MediaStore.Images.Media.SIZE},
                {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.MIME_TYPE}
        };
        for (String[] proj : projections) {
            Cursor c = null;
            try {
                c = getContentResolver().query(base, proj, null, null, sort);
            } catch (Throwable t) {
                c = null;
            }
            if (c == null) continue;
            try {
                int idIdx = c.getColumnIndex(MediaStore.Images.Media._ID);
                int nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                int bucketIdx = c.getColumnIndex("bucket_display_name");
                int dataIdx = c.getColumnIndex("_data");
                int sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE);
                while (c.moveToNext() && list.size() < MAX_ITEMS) {
                    ImageItem it = new ImageItem();
                    long id = idIdx >= 0 ? c.getLong(idIdx) : -1;
                    if (id < 0) continue;
                    it.id = id;
                    it.name = nameIdx >= 0 ? c.getString(nameIdx) : null;
                    if (it.name == null || it.name.isEmpty()) it.name = "IMG_" + id + ".jpg";
                    it.uri = ContentUris.withAppendedId(base, id);
                    String bucket = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                    if (bucket == null || bucket.trim().isEmpty()) {
                        if (dataIdx >= 0) bucket = parentName(c.getString(dataIdx));
                    }
                    if (bucket == null || bucket.trim().isEmpty()) bucket = "其他";
                    it.bucket = bucket.trim();
                    long size = -1;
                    if (sizeIdx >= 0) {
                        try {
                            size = c.getLong(sizeIdx);
                        } catch (Throwable ignore) {
                        }
                    }
                    it.size = size;
                    it.key = it.name + "|" + size;
                    list.add(it);
                }
                return list;
            } catch (Throwable t) {
                list.clear();
            } finally {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return list;
    }

    private static String parentName(String path) {
        if (path == null) return null;
        int end = path.lastIndexOf('/');
        if (end <= 0) return null;
        int start = path.lastIndexOf('/', end - 1);
        if (start < 0) return null;
        return path.substring(start + 1, end);
    }

    /** 重新构建相册下拉列表（含每个相册的照片数）。 */
    private void rebuildAlbums() {
        LinkedHashMap<String, Integer> count = new LinkedHashMap<>();
        for (ImageItem it : all) {
            Integer n = count.get(it.bucket);
            count.put(it.bucket, n == null ? 1 : n + 1);
        }
        bucketNames.clear();
        ArrayList<String> labels = new ArrayList<>();
        bucketNames.add(ALL_BUCKET);
        labels.add(ALL_BUCKET + "（" + all.size() + "）");
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            bucketNames.add(e.getKey());
            labels.add(e.getKey() + "（" + e.getValue() + "）");
        }
        fillingSpinner = true;
        ArrayAdapter<String> ad = theme.spinnerAdapter(this, labels);
        spAlbum.setAdapter(ad);
        spAlbum.setSelection(0);
        fillingSpinner = false;
        applyFilter(0);
    }

    private void applyFilter(int position) {
        shown.clear();
        if (position <= 0 || position >= bucketNames.size()) {
            shown.addAll(all);
        } else {
            String want = bucketNames.get(position);
            for (ImageItem it : all) {
                if (want.equals(it.bucket)) shown.add(it);
            }
        }
        adapter.notifyDataSetChanged();
        updateCount();
        try {
            grid.setSelection(0);
        } catch (Throwable ignore) {
        }
    }

    private void updateCount() {
        String album = ALL_BUCKET;
        try {
            int pos = spAlbum.getSelectedItemPosition();
            if (pos > 0 && pos < bucketNames.size()) album = bucketNames.get(pos);
        } catch (Throwable ignore) {
        }
        int recvCount = 0;
        for (ImageItem it : shown) if (receivedKeys.contains(it.key)) recvCount++;
        if (all.isEmpty()) {
            tvCount.setText("没有找到照片（请确认已授予存储权限）");
        } else {
            tvCount.setText("相册：" + album + "　共 " + shown.size() + " 张（已传 " + recvCount
                    + "）　已选 " + selected.size() + " 张");
        }
        btnSend.setText(selected.size() > 0 ? ("开始传输(" + selected.size() + ")") : "开始传输");
        if (btnSend.getWidth() > 0) fitButtonText(btnSend, 15, 9);
    }

    private void toggle(long id) {
        if (selected.contains(id)) selected.remove(id);
        else selected.add(id);
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void startTransfer() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一张照片", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (ImageItem it : all) {
            if (selected.contains(it.id)) {
                uris.add(it.uri);
                names.add(it.name);
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "选择已失效，请重新勾选", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent r = new Intent();
        r.putParcelableArrayListExtra(EXTRA_URIS, uris);
        r.putStringArrayListExtra("names", names);
        setResult(RESULT_OK, r);
        finish();
    }

    // ------------------------------------------------------------------
    // 缩略图（后台解码 + 缓存）
    // ------------------------------------------------------------------

    private Bitmap decodeThumb(ImageItem it) {
        Bitmap b = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                b = getContentResolver().loadThumbnail(it.uri,
                        new android.util.Size(240, 240), null);
            } else {
                b = MediaStore.Images.Thumbnails.getThumbnail(
                        getContentResolver(), it.id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
        } catch (Throwable ignore) {
        }
        if (b == null) {
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(it.uri);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 5;
                b = BitmapFactory.decodeStream(in, null, o);
            } catch (Throwable ignore) {
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return b;
    }

    private void requestThumb(final ImageItem it, final ImageView iv) {
        Bitmap cached = thumbCache.get(it.id);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageResource(android.R.drawable.ic_menu_gallery);
        if (!loadingThumbs.add(it.id)) return;
        pool.execute(() -> {
            Bitmap b = null;
            try {
                b = decodeThumb(it);
            } catch (Throwable ignore) {
            }
            if (b != null) thumbCache.put(it.id, b);
            loadingThumbs.remove(it.id);
            final Bitmap fb = b;
            if (fb == null) return;
            ui(() -> {
                try {
                    View v = grid.findViewWithTag(it.id);
                    if (v instanceof ImageView) ((ImageView) v).setImageBitmap(fb);
                } catch (Throwable ignore) {
                }
            });
        });
    }

    private void ui(Runnable r) {
        try {
            runOnUiThread(r);
        } catch (Throwable ignore) {
        }
    }

    private int cellSize() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return (w - dp(24 + 8 + 8)) / 3;
    }

    private GradientDrawable badgeBackground(boolean sel) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (sel) {
            g.setColor(theme.accent);
            g.setStroke(dp(2), theme.accent);
        } else {
            g.setColor(theme.dark ? 0x66FFFFFF : 0xCCFFFFFF);
            g.setStroke(dp(2), theme.dark ? 0x99FFFFFF : 0xFF888888);
        }
        return g;
    }

    private class ImageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageItem it = shown.get(position);
            FrameLayout cell;
            ImageView iv;
            TextView badge;
            TextView tag;
            if (convertView instanceof FrameLayout && ((FrameLayout) convertView).getChildCount() >= 3) {
                cell = (FrameLayout) convertView;
                iv = (ImageView) cell.getChildAt(0);
                badge = (TextView) cell.getChildAt(1);
                tag = (TextView) cell.getChildAt(2);
            } else {
                cell = new FrameLayout(PickerActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellSize()));
                // 胶片黑框：cell 背景为胶片黑，图片内缩几 dp，形成“照片印在胶片格”的观感
                cell.setBackgroundColor(theme.filmBlack);

                iv = new ImageView(PickerActivity.this);
                FrameLayout.LayoutParams ivlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                ivlp.setMargins(dp(3), dp(3), dp(3), dp(3));
                iv.setLayoutParams(ivlp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(theme.dark ? 0xFF2B241C : 0xFFD8CCB4);
                // 关键：子控件不接收点击/焦点，触摸事件落到整个格子 → 点哪都能选中
                iv.setClickable(false);
                iv.setFocusable(false);

                badge = new TextView(PickerActivity.this);
                int bs = dp(26);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs);
                blp.gravity = Gravity.TOP | Gravity.END;
                blp.setMargins(0, dp(4), dp(4), 0);
                badge.setLayoutParams(blp);
                badge.setGravity(Gravity.CENTER);
                badge.setTextSize(15);
                badge.setClickable(false);
                badge.setFocusable(false);

                tag = new TextView(PickerActivity.this);
                int ts = dp(15);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ts);
                tlp.gravity = Gravity.BOTTOM | Gravity.START;
                tlp.setMargins(dp(4), 0, 0, dp(4));
                tag.setLayoutParams(tlp);
                tag.setGravity(Gravity.CENTER);
                tag.setTextSize(11);
                tag.setTextColor(0xFFFFFFFF);
                // 已传标记底色：半透明强调色，在胶片黑框上清晰可读
                tag.setBackgroundColor(theme.dark ? 0xB3E08A3C : 0xB3C2702E);
                tag.setClickable(false);
                tag.setFocusable(false);

                cell.addView(iv);
                cell.addView(badge);
                cell.addView(tag);
                cell.setClickable(true);
                cell.setFocusable(false);
            }

            // 每次绑定都重设，避免 convertView 复用时点到上一张照片
            cell.setOnClickListener(v -> toggle(it.id));

            // 用 tag 记住这一格当前对应哪张照片，异步解码回来后精确回填
            iv.setTag(it.id);
            requestThumb(it, iv);

            boolean sel = selected.contains(it.id);
            boolean received = receivedKeys.contains(it.key);
            badge.setText(sel ? "✓" : "");
            badge.setTextColor(sel ? 0xFFFFFFFF : 0x00000000);
            badge.setBackgroundDrawable(badgeBackground(sel));
            iv.setAlpha(sel ? 1f : 0.92f);

            // 已传过的照片：在左下角提示「已传」（仍可被单独点选再次传输）
            if (received && !sel) {
                tag.setVisibility(View.VISIBLE);
                tag.setText("已传");
            } else {
                tag.setVisibility(View.GONE);
                tag.setText("");
            }
            return cell;
        }
    }

    /**
     * 按按钮实际可用宽度自适应字号：从 maxSp 逐档缩到 minSp，直到文字放得下；
     * 单行不换行、不省略，保证小屏窄按钮也不会溢出/被截断。
     */
    private void fitButtonText(Button b, int maxSp, int minSp) {
        try {
            int avail = b.getWidth() - b.getPaddingLeft() - b.getPaddingRight();
            if (avail <= 0) return;
            String s = b.getText() == null ? "" : b.getText().toString();
            float sp = maxSp;
            while (sp > minSp) {
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                float need = b.getPaint().measureText(s);
                if (need <= avail) break;
                sp -= 0.5f;
            }
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
