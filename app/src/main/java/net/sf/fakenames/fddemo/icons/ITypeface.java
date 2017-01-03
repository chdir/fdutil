package net.sf.fakenames.fddemo.icons;

import android.content.Context;
import android.graphics.Typeface;

import java.util.Collection;
import java.util.HashMap;

public interface ITypeface {
    IIcon getIcon(String key);

    String getFontName();

    int getIconCount();

    Typeface getTypeface(Context ctx);
}