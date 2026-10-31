package com.moi.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    // ===== A MODIFIER : les applis du dock, dans l'ordre =====
    // Le nombre d'applis ici = le nombre de colonnes (dock et grille).
    private static final String[] DOCK_PACKAGES = {
            "com.google.android.youtube.tv",   // YouTube
            "com.netflix.ninja",               // Netflix
            "tv.molotov.app",                  // Molotov
            "com.ldf.gulli.view"               // Gulli
    };

    private static final int TILE_W = 168;   // dp
    private static final int TILE_H = 94;    // dp
    private static final int PAD = 8;        // dp, marge du reflet

    private static class Info {
        String pkg;
        String label;
        int color;
    }

    private FrameLayout content;
    private ScrollView scroll;
    private LinearLayout page1;
    private LinearLayout dockBand;
    private GridLayout grid;
    private View highlight;
    private View glow;
    private TextView nameText;
    private TextView greeting;
    private TextView wifiPill;
    private PopupWindow popup;
    private String lastPkg = null;

    private final List<View> pills = new ArrayList<>();
    private final List<View> dockTiles = new ArrayList<>();
    private final List<View> gridTiles = new ArrayList<>();

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------
    // Construction de l'ecran
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        // ----- Barre du haut : salutation, heure, date + boutons -----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(56), dp(28), dp(56), 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(this);
        greeting.setTextColor(0xFF9A9A9E);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        greeting.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(greeting);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        left.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(0xFF9A9A9E);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(date);

        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] pillNames = {"Entrées", "Wi-Fi", "Réglages"};
        for (String n : pillNames) {
            TextView p = makePill(n);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = dp(10);
            pillRow.addView(p, plp);
            pills.add(p);
        }
        wifiPill = (TextView) pills.get(1);

        pills.get(0).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInputs(v);
            }
        });
        pills.get(1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        pills.get(2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        top.addView(pillRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ----- Zone qui defile : page 1 (nom + dock) puis grille -----
        scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        content.addView(sections, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page1 = new LinearLayout(this);
        page1.setOrientation(LinearLayout.VERTICAL);
        page1.setClipChildren(false);
        sections.addView(page1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.75f)));

        FrameLayout nameArea = new FrameLayout(this);
        glow = new View(this);
        nameArea.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nameText = new TextView(this);
        nameText.setTextColor(Color.WHITE);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        nameText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        nameText.setSingleLine(true);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        nlp.leftMargin = dp(64);
        nlp.bottomMargin = dp(24);
        nameArea.addView(nameText, nlp);
        page1.addView(nameArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dockBand = new LinearLayout(this);
        dockBand.setOrientation(LinearLayout.HORIZONTAL);
        dockBand.setClipChildren(false);
        dockBand.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bandBg = new GradientDrawable();
        bandBg.setColor(0xFF1A1A1C);
        bandBg.setCornerRadius(dp(30));
        dockBand.setBackground(bandBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.bottomMargin = dp(40);
        page1.addView(dockBand, dlp);

        grid = new GridLayout(this);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(16);
        glp.bottomMargin = dp(64);
        sections.addView(grid, glp);

        // Reflet "verre" unique qui glisse d'une tuile a l'autre
        highlight = new View(this);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(0x26FFFFFF);
        hbg.setCornerRadius(dp(20));
        hbg.setStroke(dp(2), 0x66FFFFFF);
        highlight.setBackground(hbg);
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setClickable(false);
        content.addView(highlight, new FrameLayout.LayoutParams(
                dp(TILE_W) + 2 * dp(PAD), dp(TILE_H) + 2 * dp(PAD)));

        // La page 1 fait exactement la hauteur visible
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int oldL, int oldT, int oldR, int oldB) {
                int h = b - t;
                ViewGroup.LayoutParams lp = page1.getLayoutParams();
                if (h > 0 && lp.height != h) {
                    lp.height = h;
                    page1.setLayoutParams(lp);
                }
            }
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Bonsoir" : h < 12 ? "Bonjour" : h < 18 ? "Bon après-midi" : "Bonsoir";
        greeting.setText(g);
        wifiPill.setText(wifiName());
        fill();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (popup != null && popup.isShowing()) popup.dismiss();
        lastPkg = null;
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // On reste sur l'accueil.
    }

    // ------------------------------------------------------------------
    // Remplissage : dock + grille
    // ------------------------------------------------------------------
    private void fill() {
        dockBand.removeAllViews();
        grid.removeAllViews();
        dockTiles.clear();
        gridTiles.clear();
        highlight.setAlpha(0f);

        final PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!found.containsKey(pkg)) found.put(pkg, ri);
            }
        }

        List<ResolveInfo> rest = new ArrayList<>(found.values());
        Collections.sort(rest, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });

        List<ResolveInfo> dock = new ArrayList<>();
        for (String p : DOCK_PACKAGES) {
            ResolveInfo ri = found.get(p);
            if (ri != null) dock.add(ri);
        }
        rest.removeAll(dock);
        while (dock.size() < DOCK_PACKAGES.length && !rest.isEmpty()) {
            dock.add(rest.remove(0));
        }

        int cols = Math.max(1, DOCK_PACKAGES.length);
        grid.setColumnCount(cols);

        for (ResolveInfo ri : dock) {
            View tile = makeTile(ri, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            dockBand.addView(tile, lp);
            dockTiles.add(tile);
        }
        for (ResolveInfo ri : rest) {
            View tile = makeTile(ri, true);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            grid.addView(tile, lp);
            gridTiles.add(tile);
        }

        wireFocus(cols);

        content.post(new Runnable() {
            @Override
            public void run() {
                View target = null;
                List<View> all = new ArrayList<>();
                all.addAll(dockTiles);
                all.addAll(gridTiles);
                for (View v : all) {
                    Info info = (Info) v.getTag();
                    if (lastPkg != null && lastPkg.equals(info.pkg)) {
                        target = v;
                        break;
                    }
                }
                if (target == null && !dockTiles.isEmpty()) target = dockTiles.get(0);
                if (target == null && !gridTiles.isEmpty()) target = gridTiles.get(0);
                if (target != null) target.requestFocus();
            }
        });
    }

    // Navigation deterministe a la telecommande
    private void wireFocus(int cols) {
        int nd = dockTiles.size();
        int ng = gridTiles.size();

        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i > 0 ? pills.get(i - 1).getId() : p.getId());
            p.setNextFocusRightId(i < pills.size() - 1 ? pills.get(i + 1).getId() : p.getId());
            p.setNextFocusUpId(p.getId());
            View down = nd > 0 ? dockTiles.get(0) : (ng > 0 ? gridTiles.get(0) : p);
            p.setNextFocusDownId(down.getId());
        }

        for (int i = 0; i < nd; i++) {
            View t = dockTiles.get(i);
            t.setNextFocusLeftId(i > 0 ? dockTiles.get(i - 1).getId() : t.getId());
            t.setNextFocusRightId(i < nd - 1 ? dockTiles.get(i + 1).getId() : t.getId());
            t.setNextFocusUpId(pills.isEmpty() ? t.getId() : pills.get(0).getId());
            t.setNextFocusDownId(ng > 0 ? gridTiles.get(Math.min(i, ng - 1)).getId() : t.getId());
        }

        int lastRow = ng > 0 ? (ng - 1) / cols : 0;
        for (int k = 0; k < ng; k++) {
            View t = gridTiles.get(k);
            int r = k / cols;
            int c = k % cols;
            t.setNextFocusLeftId(c > 0 ? gridTiles.get(k - 1).getId() : t.getId());
            t.setNextFocusRightId((c < cols - 1 && k + 1 < ng) ? gridTiles.get(k + 1).getId() : t.getId());
            if (r > 0) {
                t.setNextFocusUpId(gridTiles.get(k - cols).getId());
            } else {
                t.setNextFocusUpId(nd > 0 ? dockTiles.get(Math.min(c, nd - 1)).getId() : t.getId());
            }
            if (r < lastRow) {
                t.setNextFocusDownId(gridTiles.get(Math.min(k + cols, ng - 1)).getId());
            } else {
                t.setNextFocusDownId(t.getId());
            }
        }
    }

    // ------------------------------------------------------------------
    // Tuiles et boutons
    // ------------------------------------------------------------------
    private View makeTile(final ResolveInfo ri, final boolean showLabel) {
        PackageManager pm = getPackageManager();
        final Info info = new Info();
        info.pkg = ri.activityInfo.packageName;
        info.label = ri.loadLabel(pm).toString();

        Drawable banner = null;
        try {
            banner = ri.activityInfo.loadBanner(pm);
        } catch (Exception ignored) {
        }
        Drawable icon = ri.loadIcon(pm);
        info.color = sampleColor(banner != null ? banner : icon);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setId(View.generateViewId());
        t.setTag(info);

        ImageView iv = new ImageView(this);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(0xFF242426);
        ibg.setCornerRadius(dp(14));
        iv.setBackground(ibg);
        if (banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
        } else {
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(16), dp(16), dp(16), dp(16));
            iv.setImageDrawable(icon);
        }
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        iv.setClipToOutline(true);
        t.addView(iv, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));

        final TextView lv;
        if (showLabel) {
            lv = new TextView(this);
            lv.setText(info.label);
            lv.setTextColor(Color.WHITE);
            lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            lv.setGravity(Gravity.CENTER);
            lv.setSingleLine(true);
            lv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lv.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    dp(TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(10);
            t.addView(lv, llp);
        } else {
            lv = null;
        }

        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.07f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                if (lv != null) lv.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
                if (hasFocus) {
                    lastPkg = info.pkg;
                    moveHighlight(v);
                    if (!showLabel) {
                        setBigName(info);
                        scroll.smoothScrollTo(0, 0);
                    }
                }
            }
        });

        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                startSafe(i);
            }
        });
        return t;
    }

    private TextView makePill(String text) {
        TextView p = new TextView(this);
        p.setText(text);
        p.setTextColor(Color.WHITE);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(18), dp(9), dp(18), dp(9));
        p.setFocusable(true);
        p.setClickable(true);
        p.setId(View.generateViewId());
        p.setBackground(roundBg(0x33FFFFFF, 40));
        p.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.08f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
                v.setBackground(roundBg(hasFocus ? 0x66FFFFFF : 0x33FFFFFF, 40));
                if (hasFocus && highlight != null) highlight.setAlpha(0f);
            }
        });
        return p;
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // ------------------------------------------------------------------
    // Reflet, nom et halo
    // ------------------------------------------------------------------
    private void moveHighlight(final View tile) {
        if (tile.getWidth() == 0) {
            if (tile.isAttachedToWindow()) {
                tile.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tile.hasFocus() || tile.isFocused()) moveHighlight(tile);
                    }
                });
            }
            return;
        }
        int x = 0;
        int y = 0;
        View v = tile;
        while (v != content && v.getParent() instanceof View) {
            x += v.getLeft();
            y += v.getTop();
            v = (View) v.getParent();
        }
        float tx = x - dp(PAD);
        float ty = y - dp(PAD);
        boolean wasHidden = highlight.getAlpha() < 0.1f;
        highlight.animate().cancel();
        if (wasHidden) {
            highlight.setTranslationX(tx);
            highlight.setTranslationY(ty);
            highlight.setAlpha(1f);
        } else {
            highlight.animate().translationX(tx).translationY(ty)
                    .setDuration(230)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        }
    }

    private void setBigName(Info info) {
        nameText.setText(info.label);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(520));
        g.setGradientCenter(0.2f, 0.9f);
        g.setColors(new int[]{(info.color & 0x00FFFFFF) | 0x70000000, 0x00000000});
        glow.setBackground(g);
    }

    private int sampleColor(Drawable d) {
        try {
            Drawable c = d;
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) c = cs.newDrawable().mutate();
            Bitmap bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            c.setBounds(0, 0, 24, 24);
            c.draw(canvas);
            long r = 0, g = 0, b = 0, n = 0;
            for (int yy = 0; yy < 24; yy++) {
                for (int xx = 0; xx < 24; xx++) {
                    int p = bmp.getPixel(xx, yy);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            bmp.recycle();
            if (n == 0) return 0xFF666670;
            int avg = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            float[] hsv = new float[3];
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF666670;
        }
    }

    // ------------------------------------------------------------------
    // Wi-Fi et entrees
    // ------------------------------------------------------------------
    private String wifiName() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    String s = wi.getSSID();
                    if (s != null && !s.contains("unknown") && !s.equals("0x")) {
                        return s.replace("\"", "");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "Wi-Fi";
    }

    private void showInputs(View anchor) {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBg(0xEE2C2C2E, 16));
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        final PopupWindow pw = new PopupWindow(box, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup = pw;

        List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) inputs = tim.getTvInputList();
        } catch (Exception ignored) {
        }

        View first = null;
        for (final TvInputInfo inp : inputs) {
            CharSequence cs = null;
            try {
                cs = inp.loadLabel(this);
            } catch (Exception ignored) {
            }
            String label = cs != null ? cs.toString() : inp.getId();
            TextView item = makeMenuItem(label);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pw.dismiss();
                    openInput(inp);
                }
            });
            box.addView(item);
            if (first == null) first = item;
        }
        if (first == null) {
            TextView none = new TextView(this);
            none.setText("Aucune entrée trouvée");
            none.setTextColor(0xFFAAAAAA);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            none.setPadding(dp(16), dp(10), dp(16), dp(10));
            box.addView(none);
        }

        pw.showAsDropDown(anchor, 0, dp(6));
        if (first != null) first.requestFocus();
    }

    private TextView makeMenuItem(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? roundBg(0x44FFFFFF, 10) : null);
            }
        });
        return t;
    }

    private void openInput(TvInputInfo inp) {
        try {
            Intent i;
            if (inp.isPassthroughInput()) {
                i = new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(inp.getId()));
            } else {
                i = getPackageManager().getLeanbackLaunchIntentForPackage(
                        inp.getServiceInfo().packageName);
            }
            if (i != null) startSafe(i);
        } catch (Exception ignored) {
        }
    }

    private void startSafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
