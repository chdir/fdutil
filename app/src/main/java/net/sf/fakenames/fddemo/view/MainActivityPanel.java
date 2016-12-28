package net.sf.fakenames.fddemo.view;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.TouchDelegate;
import android.view.View;
import android.widget.FrameLayout;

import net.sf.fakenames.fddemo.R;

public final class MainActivityPanel extends FrameLayout {
    private final Rect currentThumbPresence = new Rect();
    private final Rect newThumbPresence = new Rect();

    private final int minHitRect;

    private TouchDelegate thumbTouchDelegate;

    private View thumb;

    public MainActivityPanel(Context context) {
        this(context, null);

        onFinishInflate();
    }

    public MainActivityPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MainActivityPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        minHitRect = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        thumb = findViewById(R.id.scroll_handle);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        /*
        thumb.getHitRect(newThumbPresence);

        offsetDescendantRectToMyCoords(thumb, newThumbPresence);

        final int halfMinHitSize = minHitRect / 2;

        int thumbWidth = newThumbPresence.width();

        if (thumbWidth < minHitRect) {
            int thumbCenter = newThumbPresence.left + (int) (thumbWidth / 2f);

            newThumbPresence.left = Math.max(0, thumbCenter - halfMinHitSize);
            newThumbPresence.right = Math.min(getWidth(), thumbCenter + halfMinHitSize);
        }

        int thumbHeight = newThumbPresence.height();

        if (thumbHeight < minHitRect) {
            int thumbCenter = newThumbPresence.top + (int) (thumbHeight / 2f);

            newThumbPresence.top = Math.max(0, thumbCenter - halfMinHitSize);
            newThumbPresence.bottom = Math.min(getHeight(), thumbCenter + halfMinHitSize);
        }

        if (!newThumbPresence.equals(currentThumbPresence) || thumbTouchDelegate == null) {
            currentThumbPresence.set(newThumbPresence);

            // follow material guidelines by giving the thumb minimal effective width of 48dp
            thumbTouchDelegate = new TouchDelegate(currentThumbPresence, thumb);

            setTouchDelegate(thumbTouchDelegate);
        }
        */
    }
}
