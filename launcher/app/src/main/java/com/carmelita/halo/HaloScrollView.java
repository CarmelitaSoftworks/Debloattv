package com.carmelita.halo;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** ScrollView que no se desplaza sola al cambiar el foco: MainActivity centra cada fila a mano. */
public class HaloScrollView extends ScrollView {

    public HaloScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}
