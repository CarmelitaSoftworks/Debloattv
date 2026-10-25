package com.carmelita.halo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Tarjeta 16:9 con esquinas redondeadas que se eleva al recibir el foco, al estilo tvOS. */
final class TileView extends FrameLayout {
    private static final float FOCUS_SCALE = 1.12f;
    private static final int ANIM_MS = 180;

    final AppEntry entry;
    final int index;
    private final View shine;
    private final float focusLift;
    private final float radius;
    private final Path clip = new Path();
    private TextView label;
    private ObjectAnimator wiggle;

    TileView(Context context, AppEntry entry, int index, int width, int height) {
        super(context);
        this.entry = entry;
        this.index = index;
        float density = getResources().getDisplayMetrics().density;
        radius = getResources().getDimension(R.dimen.tile_radius);
        focusLift = 20 * density;

        setFocusable(true);
        setClickable(true);
        setContentDescription(entry.label);

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(entry.color), entry.color});
        bg.setCornerRadius(radius);
        setBackground(bg);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        setElevation(2 * density);

        ImageView image = new ImageView(context);
        if (entry.banner != null) {
            image.setImageDrawable(entry.banner);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(width, height));
        } else {
            int size = Math.round(height * 0.56f);
            image.setImageDrawable(entry.icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new LayoutParams(size, size, Gravity.CENTER));
        }

        // Reflejo sutil en la parte superior, visible solo con foco
        shine = new View(context);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        shine.setAlpha(0f);
        addView(shine, new LayoutParams(width, height));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
    }

    /** Recorte redondeado también sin aceleración por hardware (clipToOutline solo actúa con ella). */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    /** Nombre que aparece debajo de la tarjeta solo cuando tiene el foco. */
    void setLabel(TextView label) {
        this.label = label;
        label.setAlpha(0f);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        float scale = gainFocus ? FOCUS_SCALE : 1f;
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        animate().scaleX(scale).scaleY(scale)
                .translationZ(gainFocus ? focusLift : 0f)
                .setInterpolator(ease).setDuration(ANIM_MS).start();
        shine.animate().alpha(gainFocus ? 1f : 0f).setDuration(ANIM_MS).start();
        if (label != null) {
            label.animate().alpha(gainFocus ? 1f : 0f)
                    .translationY(gainFocus ? getHeight() * (FOCUS_SCALE - 1f) / 2f : 0f)
                    .setInterpolator(ease).setDuration(ANIM_MS).start();
        }
    }

    /** Tembleque del modo "mover", como al reordenar apps en Apple TV. */
    void setWiggling(boolean on) {
        if (on && wiggle == null) {
            wiggle = ObjectAnimator.ofFloat(this, View.ROTATION, -1.2f, 1.2f);
            wiggle.setDuration(130);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.start();
        } else if (!on && wiggle != null) {
            wiggle.cancel();
            wiggle = null;
            setRotation(0f);
        }
    }

    private static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.35f);
        return Color.HSVToColor(hsv);
    }
}
