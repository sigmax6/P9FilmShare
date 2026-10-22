package com.p9filmshare.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.ViewGroup;

import java.util.Random;

/**
 * 胶片照片风格的统一主题系统。
 *
 * 设计语言：暖色调“胶片/相纸”观感——米色相纸底、琥珀色强调、35mm 齿孔胶片条、
 * 细颗粒噪点、等宽字体（胶片盒标签感）。同时支持浅色 / 深色 / 跟随系统三档。
 *
 * 用法：在 Activity.onCreate 顶部取 {@code AppTheme t = AppTheme.of(this);}，
 * 之后所有颜色、背景、按钮都用 t 提供的方法，避免散落的硬编码色值。
 */
public class AppTheme {

    // 主题模式（与 ConnStore 中存储值保持一致）
    public static final int MODE_LIGHT = ConnStore.THEME_LIGHT;
    public static final int MODE_DARK = ConnStore.THEME_DARK;
    public static final int MODE_SYSTEM = ConnStore.THEME_SYSTEM;

    public final boolean dark;

    // 调色板
    public final int bg;          // 页面背景（相纸/深底）
    public final int surface;     // 卡片/控件表面
    public final int surfaceAlt;  // 输入框等次级表面
    public final int text;        // 主文字
    public final int muted;       // 次要/说明文字
    public final int accent;      // 强调色（琥珀）
    public final int accentPressed;
    public final int border;      // 描边
    public final int filmBlack;   // 35mm 胶片条黑
    public final int sprocket;    // 齿孔颜色（胶片条上的“孔”）

    private static Bitmap grainBmp; // 颗粒噪点（全局缓存，只生成一次）

    public static AppTheme of(Context ctx) {
        int mode = ConnStore.theme(ctx);
        boolean dark;
        if (mode == MODE_DARK) dark = true;
        else if (mode == MODE_LIGHT) dark = false;
        else dark = isSystemDark(ctx);
        return new AppTheme(dark);
    }

    public static boolean isSystemDark(Context ctx) {
        try {
            int ui = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable e) {
            return false;
        }
    }

    private AppTheme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF171310;
            surface = 0xFF221C16;
            surfaceAlt = 0xFF2B241C;
            text = 0xFFEFE6D6;
            muted = 0xFF9C8C76;
            accent = 0xFFE08A3C;
            accentPressed = 0xFFC9742E;
            border = 0xFF3A3128;
            filmBlack = 0xFF0C0A08;
            sprocket = 0xFFEFE6D6;
        } else {
            bg = 0xFFECE3D2;
            surface = 0xFFF7F1E3;
            surfaceAlt = 0xFFEEE4D1;
            text = 0xFF2E2820;
            muted = 0xFF8A7B66;
            accent = 0xFFC2702E;
            accentPressed = 0xFFA85C20;
            border = 0xFFD8CCB4;
            filmBlack = 0xFF1C1712;
            sprocket = 0xFFF7F1E3;
        }
    }

    // ---------------- 背景（grain 颗粒 + 底色） ----------------

    public Drawable background(Context ctx) {
        Bitmap g = grain(ctx);
        BitmapDrawable grain = new BitmapDrawable(ctx.getResources(), g);
        grain.setTileModeX(Shader.TileMode.REPEAT);
        grain.setTileModeY(Shader.TileMode.REPEAT);
        ColorDrawable base = new ColorDrawable(bg);
        return new LayerDrawable(new Drawable[]{base, grain});
    }

    private static synchronized Bitmap grain(Context ctx) {
        if (grainBmp != null) return grainBmp;
        int s = 96;
        grainBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        int[] px = new int[s * s];
        Random r = new Random(20261020L);
        for (int i = 0; i < px.length; i++) {
            int a = r.nextInt(22);                       // 0..21 低透明度颗粒
            int col = r.nextBoolean() ? 0xFFFFFF : 0x000000;
            px[i] = (a << 24) | (col & 0xFFFFFF);
        }
        grainBmp.setPixels(px, 0, s, 0, 0, s, s);
        return grainBmp;
    }

    // ---------------- 35mm 齿孔胶片条 ----------------

    /** 生成一条横向平铺的齿孔胶片条 drawable（默认高 18dp）。 */
    public Drawable filmStrip(Context ctx) {
        return filmStrip(ctx, 18);
    }

    public Drawable filmStrip(Context ctx, int heightDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int cell = (int) (22 * d);
        int h = (int) (heightDp * d);
        Bitmap b = Bitmap.createBitmap(cell, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(filmBlack);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(sprocket);
        float holeW = (int) (11 * d);
        float holeH = (int) (8 * d);
        float left = (cell - holeW) / 2f;
        float top = (h - holeH) / 2f;
        float rad = (int) (3 * d);
        c.drawRoundRect(new RectF(left, top, left + holeW, top + holeH), rad, rad, p);
        BitmapDrawable bd = new BitmapDrawable(ctx.getResources(), b);
        bd.setTileModeX(Shader.TileMode.REPEAT);
        bd.setTileModeY(Shader.TileMode.CLAMP);
        return bd;
    }

    /** 便捷：返回一个铺满胶片条背景的 View（高 heightDp）。 */
    public View filmStripView(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setBackgroundDrawable(filmStrip(ctx, heightDp));
        return v;
    }

    // ---------------- 字体 ----------------

    public Typeface mono() {
        return Typeface.MONOSPACE;
    }

    // ---------------- 控件工厂 ----------------

    public Button button(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        styleButton(b, primary);
        return b;
    }

    public void styleButton(View v, boolean primary) {
        if (!(v instanceof Button)) return;
        Button b = (Button) v;
        float d = b.getContext().getResources().getDisplayMetrics().density;
        b.setAllCaps(false);
        b.setTypeface(mono());
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(8 * d);
        if (primary) {
            normal.setColor(accent);
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(accentPressed);
            pressed.setCornerRadius(8 * d);
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{}, normal);
            b.setBackgroundDrawable(sld);
            b.setTextColor(dark ? 0xFF1A1208 : 0xFFFFFFFF);
        } else {
            normal.setColor(surface);
            normal.setStroke((int) (1.5f * d), border);
            b.setBackgroundDrawable(normal);
            b.setTextColor(text);
        }
    }

    public void styleInput(EditText et) {
        float d = et.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(8 * d);
        gd.setColor(surfaceAlt);
        gd.setStroke((int) (1.2f * d), border);
        et.setBackgroundDrawable(gd);
        et.setTextColor(text);
        et.setHintTextColor(muted);
        et.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
    }

    public void styleSpinner(Spinner s) {
        float d = s.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(8 * d);
        gd.setColor(surfaceAlt);
        gd.setStroke((int) (1.2f * d), border);
        s.setBackgroundDrawable(gd);
        s.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        // 下拉弹窗背景也跟着主题走（否则深色模式下弹窗是系统浅色、文字又看不清）
        if (Build.VERSION.SDK_INT >= 16) {
            GradientDrawable pop = new GradientDrawable();
            pop.setColor(surface);
            pop.setCornerRadius(8 * d);
            pop.setStroke((int) (1.2f * d), border);
            s.setPopupBackgroundDrawable(pop);
        }
    }

    /**
     * 主题化的下拉适配器：选中态与下拉项都用 {@link #text} 颜色，
     * 解决深色模式下系统 simple_spinner_item 默认深色文字看不清的问题。
     */
    public ArrayAdapter<String> spinnerAdapter(Context ctx, java.util.List<String> items) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        return new ArrayAdapter<String>(ctx, 0, items) {
            private TextView make() {
                TextView tv = new TextView(ctx);
                tv.setTypeface(mono());
                tv.setTextSize(13);
                tv.setTextColor(text);
                return tv;
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (convertView instanceof TextView) ? (TextView) convertView : make();
                tv.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
                tv.setText(getItem(position));
                tv.setTextColor(text);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (convertView instanceof TextView) ? (TextView) convertView : make();
                tv.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
                tv.setText(getItem(position));
                tv.setTextColor(text);
                tv.setBackgroundColor(surface);
                return tv;
            }
        };
    }

    public int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
