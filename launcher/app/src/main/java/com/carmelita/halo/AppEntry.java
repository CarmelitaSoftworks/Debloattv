package com.carmelita.halo;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** Una app de la pantalla de inicio (o el acceso a Ajustes). */
final class AppEntry {
    final String key;
    final String label;
    final Drawable banner;
    final Drawable icon;
    final int color;
    private final Intent launchIntent;

    AppEntry(String key, String label, Drawable banner, Drawable icon, Intent launchIntent) {
        this.key = key;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launchIntent = launchIntent;
        this.color = tileColor(banner != null ? banner : icon);
    }

    static AppEntry forActivity(ComponentName component, String category, String label,
                                Drawable banner, Drawable icon) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new AppEntry(component.getPackageName(), label, banner, icon, intent);
    }

    boolean isSettings() {
        return AppStore.SETTINGS_KEY.equals(key);
    }

    Intent launchIntent() {
        return new Intent(launchIntent);
    }

    /** Color medio del icono, oscurecido para que sirva de fondo con texto blanco encima. */
    private static int tileColor(Drawable d) {
        if (d == null) return 0xFF3A3A42;
        Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 16, 16);
        copy.draw(new Canvas(bmp));

        float r = 0, g = 0, b = 0, total = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                float w = 0.15f + hsv[1]; // los píxeles saturados pesan más que el blanco/gris
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                b += Color.blue(p) * w;
                total += w;
            }
        }
        bmp.recycle();
        if (total == 0) return 0xFF3A3A42;

        Color.colorToHSV(Color.rgb((int) (r / total), (int) (g / total), (int) (b / total)), hsv);
        hsv[1] = Math.min(hsv[1], 0.75f);
        hsv[2] = Math.max(0.22f, Math.min(hsv[2], 0.5f));
        return Color.HSVToColor(hsv);
    }
}
