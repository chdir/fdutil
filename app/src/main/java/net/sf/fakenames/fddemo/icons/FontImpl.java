package net.sf.fakenames.fddemo.icons;

import android.content.Context;
import android.graphics.Typeface;

final class FontImpl implements ITypeface {
    private Typeface typeface;

    @Override
    public String getFontName() {
        return "Custom icon font";
    }

    @Override
    public int getIconCount() {
        return 1;
    }

    @Override
    public IIcon getIcon(String key) {
        return null;
    }

    @Override
    public Typeface getTypeface(Context ctx) {
        if (typeface == null) {
            this.typeface = Typeface.createFromAsset(ctx.getAssets(), "icons.ttf");
        }

        return typeface;
    }
}
