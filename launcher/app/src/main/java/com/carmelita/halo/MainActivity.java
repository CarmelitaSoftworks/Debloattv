package com.carmelita.halo;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int COLUMNS = 5;

    private final List<AppEntry> apps = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AppStore store;
    private AppEntry settings;
    private HaloScrollView scroll;
    private LinearLayout grid;
    private View hero;
    private View clock;
    private TextView heroTitle;
    private LinearLayout heroHints;
    private View shelf;
    private ImageView shelfArt;
    private GradientDrawable backdrop;
    private ValueAnimator backdropAnim;
    private int backdropColor = 0xFF1C1C24;

    private int tileWidth;
    private int tileHeight;
    private int gap;

    private String focusKey;
    private String movingKey;
    private boolean showHidden;
    private boolean resumed;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scroll = findViewById(R.id.scroll);
        grid = findViewById(R.id.grid);
        hero = findViewById(R.id.hero);
        clock = findViewById(R.id.clock);
        heroTitle = findViewById(R.id.hero_title);
        heroHints = findViewById(R.id.hero_hints);
        shelf = findViewById(R.id.shelf);
        shelfArt = findViewById(R.id.shelf_art);

        // Resplandor radial arriba a la derecha, detrás de la portada
        backdrop = new GradientDrawable();
        backdrop.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        backdrop.setGradientCenter(0.72f, 0f);
        backdrop.setColors(backdropColors(backdropColor));
        findViewById(R.id.backdrop).setBackground(backdrop);

        shelfArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        getResources().getDimension(R.dimen.tile_radius));
            }
        });

        store = new AppStore(this);
        settings = store.settingsEntry();
        measure();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packagesChanged, filter);

        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    /** HOME pulsado estando ya en el inicio: volver arriba, como en tvOS. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (resumed) goToTop();
    }

    @Override
    public void onBackPressed() {
        // Un launcher nunca se cierra con "atrás"
        if (movingKey != null) stopMoving();
        else goToTop();
    }

    private void measure() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = getResources().getDimensionPixelSize(R.dimen.side_padding);
        gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);
        tileWidth = (dm.widthPixels - 2 * side - (COLUMNS - 1) * gap) / COLUMNS;
        tileHeight = tileWidth * 9 / 16;
        hero.setMinimumHeight(Math.round(dm.heightPixels * 0.48f));
        backdrop.setGradientRadius(dm.widthPixels * 0.62f);
        // La portada ocupa la parte superior derecha, como el "Top Shelf" de tvOS
        ViewGroup.LayoutParams shelfLp = shelf.getLayoutParams();
        shelfLp.width = Math.round(dm.widthPixels * 0.56f);
        shelfLp.height = Math.round(dm.heightPixels * 0.52f);
        shelf.setLayoutParams(shelfLp);
        // Espacio al final para que la última fila pueda subir hasta arriba
        grid.setPadding(0, 0, 0, dm.heightPixels / 2);
    }

    private void reload() {
        io.execute(() -> {
            final List<AppEntry> loaded = store.load();
            ui.post(() -> {
                if (isDestroyed()) return;
                apps.clear();
                apps.addAll(loaded);
                render();
            });
        });
    }

    private List<AppEntry> visibleApps() {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : apps) {
            if (showHidden || !store.isHidden(e)) out.add(e);
        }
        return out;
    }

    private void render() {
        grid.removeAllViews();
        List<AppEntry> items = visibleApps();
        items.add(settings);

        TileView toFocus = null;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i == 0) {
                    // Dock de cristal detrás de la primera fila, un poco más ancho que la cuadrícula
                    int inset = getResources().getDimensionPixelSize(R.dimen.dock_inset);
                    rowLp.leftMargin = rowLp.rightMargin = -inset;
                    rowLp.bottomMargin = gap;
                    row.setPadding(inset, inset, inset, 0);
                    row.setBackgroundResource(R.drawable.glass);
                }
                grid.addView(row, rowLp);
            }
            AppEntry e = items.get(i);
            TileView tile = addCell(row, e, i);
            if (showHidden && store.isHidden(e)) tile.setAlpha(0.4f);
            if (e.key.equals(movingKey)) tile.setWiggling(true);
            if (toFocus == null && e.key.equals(focusKey)) toFocus = tile;
        }
        if (toFocus == null) toFocus = (TileView) ((ViewGroup) ((ViewGroup) grid.getChildAt(0)).getChildAt(0)).getChildAt(0);
        toFocus.requestFocus();
    }

    private TileView addCell(LinearLayout row, final AppEntry e, int index) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        final TileView tile = new TileView(this, e, index, tileWidth, tileHeight);
        cell.addView(tile, new LinearLayout.LayoutParams(tileWidth, tileHeight));

        TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(heroTitle.getTextColors());
        label.setTextSize(15);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = gap / 3;
        labelLp.bottomMargin = gap;
        cell.addView(label, labelLp);
        tile.setLabel(label);

        tile.setOnClickListener(v -> {
            if (e.isSettings()) showOptions(e); else launch(e);
        });
        tile.setOnLongClickListener(v -> {
            showOptions(e);
            return true;
        });
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) onTileFocused(tile);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = index % COLUMNS == 0 ? 0 : gap;
        row.addView(cell, lp);
        return tile;
    }

    private void onTileFocused(TileView tile) {
        AppEntry e = tile.entry;
        focusKey = e.key;
        heroTitle.setText(e.label);
        setHints(movingKey != null);
        animateBackdrop(e.color);
        showOnShelf(e);

        // Primera fila: se ve la cabecera. Resto: la fila enfocada sube cerca del borde superior.
        final int rowIndex = tile.index / COLUMNS;
        final View row = grid.getChildAt(rowIndex);
        scroll.post(() -> {
            int target = rowIndex == 0 ? 0 : grid.getTop() + row.getTop() - Math.round(tileHeight * 0.6f);
            scroll.smoothScrollTo(0, Math.max(0, target));
        });
        // Cabecera y reloj solo con la primera fila, como en tvOS
        float topAlpha = rowIndex == 0 ? 1f : 0f;
        hero.animate().alpha(topAlpha).setDuration(200).start();
        shelf.animate().alpha(topAlpha).setDuration(200).start();
        clock.animate().alpha(topAlpha).setDuration(200).start();
    }

    private void animateBackdrop(int target) {
        if (backdropAnim != null) backdropAnim.cancel();
        backdropAnim = ValueAnimator.ofObject(new ArgbEvaluator(), backdropColor, target);
        backdropAnim.setDuration(400);
        backdropAnim.addUpdateListener(a -> {
            backdropColor = (int) a.getAnimatedValue();
            backdrop.setColors(backdropColors(backdropColor));
        });
        backdropAnim.start();
    }

    private static int[] backdropColors(int color) {
        int rgb = color & 0x00FFFFFF;
        return new int[]{0x80000000 | rgb, 0x2E000000 | rgb, 0x00000000};
    }

    /** Portada: el banner de la app como un póster, o su icono en grande si no tiene banner. */
    private void showOnShelf(AppEntry e) {
        int w = shelf.getLayoutParams().width;
        int h = shelf.getLayoutParams().height;
        boolean banner = e.banner != null;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) shelfArt.getLayoutParams();
        lp.width = banner ? Math.round(w * 0.5f) : Math.round(h * 0.36f);
        lp.height = banner ? lp.width * 9 / 16 : lp.width;
        lp.leftMargin = Math.round(w * 0.12f);
        shelfArt.setLayoutParams(lp);
        shelfArt.setClipToOutline(banner);
        shelfArt.setElevation(banner ? getResources().getDisplayMetrics().density * 16 : 0);
        shelfArt.setImageDrawable(banner ? e.banner : e.icon);
        shelfArt.setAlpha(0f);
        shelfArt.animate().alpha(1f).setDuration(220).start();
    }

    /** Atajos con aspecto de tecla bajo el título: [OK] Abrir  [Mantener OK] Opciones. */
    private void setHints(boolean moving) {
        heroHints.removeAllViews();
        if (moving) {
            addHint(R.string.key_arrows, R.string.hint_arrows_move);
            addHint(R.string.key_ok, R.string.hint_ok_done);
        } else {
            addHint(R.string.key_ok, R.string.hint_ok_open);
            addHint(R.string.key_hold_ok, R.string.hint_hold_ok);
        }
    }

    private void addHint(int key, int label) {
        float d = getResources().getDisplayMetrics().density;
        TextView cap = new TextView(this);
        cap.setText(key);
        cap.setTextSize(10);
        cap.setTextColor(getColor(R.color.text_primary));
        cap.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        cap.setBackgroundResource(R.drawable.keycap);
        cap.setPadding(Math.round(6 * d), Math.round(2 * d), Math.round(6 * d), Math.round(2 * d));
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.leftMargin = heroHints.getChildCount() == 0 ? 0 : Math.round(12 * d);
        heroHints.addView(cap, capLp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = Math.round(5 * d);
        heroHints.addView(text, textLp);
    }

    private void goToTop() {
        if (grid.getChildCount() == 0) return;
        focusKey = null;
        View first = ((ViewGroup) ((ViewGroup) grid.getChildAt(0)).getChildAt(0)).getChildAt(0);
        first.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    private void launch(AppEntry e) {
        if (movingKey != null) return;
        try {
            startActivity(e.launchIntent());
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, getString(R.string.cannot_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, getString(R.string.cannot_open, ""), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Menú de opciones -------------------------------------------------------------------

    private void showOptions(final AppEntry e) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (e.isSettings()) {
            add(labels, actions, R.string.opt_clean, () -> startActivity(new Intent(this, CleanActivity.class)));
            add(labels, actions, R.string.opt_system_settings, () -> launch(e));
            add(labels, actions, R.string.opt_home_button, () -> CleanActivity.openAccessibilitySettings(this));
            add(labels, actions, R.string.opt_reset_order, () -> {
                store.clearOrder();
                reload();
            });
        } else {
            final boolean hidden = store.isHidden(e);
            add(labels, actions, R.string.opt_open, () -> launch(e));
            add(labels, actions, R.string.opt_move, () -> startMoving(e));
            add(labels, actions, hidden ? R.string.opt_unhide : R.string.opt_hide, () -> {
                store.setHidden(e, !hidden);
                render();
            });
            add(labels, actions, R.string.opt_info, () -> open(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.key))));
            add(labels, actions, R.string.opt_uninstall, () -> open(new Intent(
                    Intent.ACTION_DELETE, Uri.parse("package:" + e.key))));
        }
        add(labels, actions, showHidden ? R.string.opt_hide_hidden : R.string.opt_show_hidden, () -> {
            showHidden = !showHidden;
            render();
        });

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void add(List<String> labels, List<Runnable> actions, int label, Runnable action) {
        labels.add(getString(label));
        actions.add(action);
    }

    // ---- Modo mover ---------------------------------------------------------------------------

    private void startMoving(AppEntry e) {
        movingKey = e.key;
        focusKey = e.key;
        render();
    }

    private void stopMoving() {
        movingKey = null;
        render();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (movingKey != null) {
            int delta = 0;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
                case KeyEvent.KEYCODE_DPAD_UP: delta = -COLUMNS; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: delta = COLUMNS; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) stopMoving();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) moveBy(delta);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            View focused = getCurrentFocus();
            if (focused instanceof TileView) {
                showOptions(((TileView) focused).entry);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void moveBy(int delta) {
        List<AppEntry> visible = visibleApps();
        int from = -1;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).key.equals(movingKey)) from = i;
        }
        if (from < 0) return;
        int to = Math.max(0, Math.min(visible.size() - 1, from + delta));
        if (to == from) return;

        // Se mueve dentro de la lista completa para no perder la posición de las apps ocultas
        AppEntry moving = visible.get(from);
        int target = apps.indexOf(visible.get(to));
        apps.remove(moving);
        apps.add(target, moving);
        store.saveOrder(apps);
        render();
    }
}
