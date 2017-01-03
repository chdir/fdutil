package net.sf.fakenames.fddemo.icons;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.DimenRes;
import android.support.annotation.Dimension;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import static android.support.annotation.Dimension.DP;
import static android.support.annotation.Dimension.PX;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;

/**
 * A custom {@link Drawable} which can display icons from icon fonts.
 */
public final class IconFontDrawable extends Drawable {
    private static final int ANDROID_ACTIONBAR_ICON_SIZE_DP = 24;
    private static final int ANDROID_ACTIONBAR_ICON_SIZE_PADDING_DP = 1;

    private Context context;
    private DisplayMetrics metrics;

    private int sizeX = -1;
    private int sizeY = -1;

    private boolean respectFontBounds = false;

    private int iconColor;
    private Paint iconPaint;
    private int contourColor;
    private @Nullable Paint contourPaint;
    private int backgroundColor;
    private @Nullable Paint backgroundPaint;

    private int mRoundedCornerRx = -1;
    private int mRoundedCornerRy = -1;

    private Rect mPaddingBounds;
    private RectF mPathBounds;

    private Path mPath;

    private int mIconPadding;
    private int mContourWidth;

    private int mIconOffsetX = 0;
    private int mIconOffsetY = 0;

    private int mAlpha = 255;

    private boolean mDrawContour;

    private IIcon mIcon;
    private String mPlainIcon;

    private ColorStateList mTint;
    private PorterDuff.Mode mTintMode = PorterDuff.Mode.SRC_IN;
    private ColorFilter mTintFilter;
    private ColorFilter mColorFilter;

    public IconFontDrawable(Context context, final IIcon icon) {
        this(context, icon.getTypeface(), icon);
    }

    protected IconFontDrawable(Context context, final ITypeface typeface, final IIcon icon) {
        this.context = context.getApplicationContext();
        this.metrics = context.getResources().getDisplayMetrics();

        prepare();
        icon(typeface, icon);
    }

    private void prepare() {
        iconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setTextAlign(Paint.Align.CENTER);
        iconPaint.setUnderlineText(false);
        iconPaint.setAntiAlias(true);

        mPath = new Path();

        mPathBounds = new RectF();
        mPaddingBounds = new Rect();
    }

    private @NonNull Paint bgPaint() {
        if (backgroundPaint == null) {
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        return backgroundPaint;
    }

    private @NonNull Paint contourPaint() {
        if (contourPaint == null) {
            contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            contourPaint.setStyle(Paint.Style.STROKE);
        }

        return contourPaint;
    }

    /**
     * Loads and draws given.
     *
     * @return The current IconExtDrawable for chaining.
     */
    protected IconFontDrawable icon(ITypeface typeface, IIcon icon) {
        mIcon = icon;
        iconPaint.setTypeface(typeface.getTypeface(context));
        invalidateSelf();
        return this;
    }

    /**
     * Set if it should respect the original bounds of the icon. (DEFAULT is false)
     * This will break the "padding" functionality, but keep the padding defined by the font itself
     * Check it out with the oct_arrow_down and oct_arrow_small_down of the Octicons font
     *
     * @param respectBounds set to true if it should respect the original bounds
     */
    public IconFontDrawable respectFontBounds(boolean respectBounds) {
        this.respectFontBounds = respectBounds;
        invalidateSelf();
        return this;
    }

    /**
     * Set the color of the drawable.
     *
     * @param color The color, usually from android.graphics.Color or 0xFF012345.
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable color(@ColorInt int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        iconPaint.setColor(Color.rgb(red, green, blue));
        iconColor = color;
        setAlpha(Color.alpha(color));
        invalidateSelf();
        return this;
    }

    /**
     * Set the color of the drawable.
     *
     * @param colorRes The color resource, from your R file.
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable colorRes(@ColorRes int colorRes) {
        return color(ContextCompat.getColor(context, colorRes));
    }


    /**
     * Returns the icon color
     */
    public int getColor() {
        return iconColor;
    }

    /**
     * Returns the icon contour color
     */
    public int getContourColor() {
        return contourColor;
    }

    /**
     * Returns the icon background color
     */
    public int getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * set the icon offset for X from resource
     *
     * @param iconOffsetXRes
     * @return
     */
    public IconFontDrawable iconOffsetXRes(@DimenRes int iconOffsetXRes) {
        return iconOffsetXPx(context.getResources().getDimensionPixelSize(iconOffsetXRes));
    }

    /**
     * set the icon offset for X as dp
     *
     * @param iconOffsetXDp
     * @return
     */
    public IconFontDrawable iconOffsetXDp(@Dimension(unit = DP) int iconOffsetXDp) {
        return iconOffsetXPx((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, iconOffsetXDp, metrics));
    }

    /**
     * set the icon offset for X
     *
     * @param iconOffsetX
     * @return
     */
    public IconFontDrawable iconOffsetXPx(@Dimension(unit = PX) int iconOffsetX) {
        this.mIconOffsetX = iconOffsetX;
        return this;
    }

    /**
     * set the icon offset for Y from resource
     *
     * @param iconOffsetYRes
     * @return
     */
    public IconFontDrawable iconOffsetYRes(@DimenRes int iconOffsetYRes) {
        return iconOffsetYPx(context.getResources().getDimensionPixelSize(iconOffsetYRes));
    }

    /**
     * set the icon offset for Y as dp
     *
     * @param iconOffsetYDp
     * @return
     */
    public IconFontDrawable iconOffsetYDp(@Dimension(unit = DP) int iconOffsetYDp) {
        return iconOffsetYPx((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, iconOffsetYDp, metrics));
    }

    /**
     * set the icon offset for Y
     *
     * @param iconOffsetY
     * @return
     */
    public IconFontDrawable iconOffsetYPx(@Dimension(unit = PX) int iconOffsetY) {
        this.mIconOffsetY = iconOffsetY;
        return this;
    }

    /**
     * Set the padding of the drawable from res
     *
     * @param dimenRes
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable paddingRes(@DimenRes int dimenRes) {
        return paddingPx(context.getResources().getDimensionPixelSize(dimenRes));
    }


    /**
     * Set the padding in dp for the drawable
     *
     * @param iconPadding
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable paddingDp(@Dimension(unit = DP) int iconPadding) {
        return paddingPx((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, iconPadding, metrics));
    }

    /**
     * Set a padding for the.
     *
     * @param iconPadding
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable paddingPx(@Dimension(unit = PX) int iconPadding) {
        if (mIconPadding != iconPadding) {
            mIconPadding = iconPadding;
            if (mDrawContour) {
                mIconPadding += mContourWidth;
            }

            invalidateSelf();
        }
        return this;
    }

    /**
     * Set the size of this icon to the standard Android ActionBar.
     *
     * @return The current IconExtDrawable for chaining.
     * @deprecated use actionBar() instead
     */
    @Deprecated
    public IconFontDrawable actionBarSize() {
        return sizeDp(ANDROID_ACTIONBAR_ICON_SIZE_DP);
    }

    /**
     * Sets the size and the Padding to the correct values to be used for the actionBar / toolBar
     *
     * @return
     */
    public IconFontDrawable actionBar() {
        sizeDp(ANDROID_ACTIONBAR_ICON_SIZE_DP);
        paddingDp(ANDROID_ACTIONBAR_ICON_SIZE_PADDING_DP);
        return this;
    }

    /**
     * Set the size of the drawable.
     *
     * @param dimenRes The dimension resource.
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeRes(@DimenRes int dimenRes) {
        return sizePx(context.getResources().getDimensionPixelSize(dimenRes));
    }


    /**
     * Set the size of the drawable.
     *
     * @param size The size in density-independent pixels (dp).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeDp(@Dimension(unit = DP) int size) {
        return sizePx((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, size, metrics));
    }

    /**
     * Set the size of the drawable.
     *
     * @param size The size in pixels (px).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizePx(@Dimension(unit = PX) int size) {
        this.sizeX = size;
        this.sizeY = size;
        setBounds(0, 0, size, size);
        invalidateSelf();
        return this;
    }

    /**
     * Set the size of the drawable.
     *
     * @param dimenResX The dimension resource.
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeResX(@DimenRes int dimenResX) {
        return sizePxX(context.getResources().getDimensionPixelSize(dimenResX));
    }


    /**
     * Set the size of the drawable.
     *
     * @param sizeX The size in density-independent pixels (dp).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeDpX(@Dimension(unit = DP) int sizeX) {
        return sizePxX((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, sizeX, metrics));
    }

    /**
     * Set the size of the drawable.
     *
     * @param sizeX The size in pixels (px).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizePxX(@Dimension(unit = PX) int sizeX) {
        this.sizeX = sizeX;
        setBounds(0, 0, this.sizeX, sizeY);
        invalidateSelf();
        return this;
    }

    /**
     * Set the size of the drawable.
     *
     * @param dimenResY The dimension resource.
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeResY(@DimenRes int dimenResY) {
        return sizePxY(context.getResources().getDimensionPixelSize(dimenResY));
    }


    /**
     * Set the size of the drawable.
     *
     * @param sizeY The size in density-independent pixels (dp).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizeDpY(@Dimension(unit = DP) int sizeY) {
        return sizePxY((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, sizeY, metrics));
    }

    /**
     * Set the size of the drawable.
     *
     * @param sizeY The size in pixels (px).
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable sizePxY(@Dimension(unit = PX) int sizeY) {
        this.sizeY = sizeY;
        setBounds(0, 0, sizeX, this.sizeY);
        invalidateSelf();
        return this;
    }


    /**
     * Set contour color for the.
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable contourColor(@ColorInt int contourColor) {
        int red = Color.red(contourColor);
        int green = Color.green(contourColor);
        int blue = Color.blue(contourColor);

        final Paint contour = contourPaint();
        contour.setColor(Color.rgb(red, green, blue));
        contour.setAlpha(Color.alpha(contourColor));

        this.contourColor = contourColor;
        invalidateSelf();
        return this;
    }

    /**
     * Set contour color from color res.
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable contourColorRes(@ColorRes int contourColorRes) {
        return contourColor(ContextCompat.getColor(context, contourColorRes));
    }

    /**
     * set background color
     */
    public IconFontDrawable backgroundColor(@ColorInt int backgroundColor) {
        final Paint bgPaint = bgPaint();
        bgPaint.setColor(backgroundColor);

        this.backgroundColor = backgroundColor;
        this.mRoundedCornerRx = 0;
        this.mRoundedCornerRy = 0;
        return this;
    }

    /**
     * set background color from res
     */
    public IconFontDrawable backgroundColorRes(@ColorRes int backgroundColorRes) {
        return backgroundColor(ContextCompat.getColor(context, backgroundColorRes));
    }

    /**
     * set rounded corner from res
     */
    public IconFontDrawable roundedCornersRxRes(@DimenRes int roundedCornerRxRes) {
        this.mRoundedCornerRx = context.getResources().getDimensionPixelSize(roundedCornerRxRes);
        return this;
    }

    /**
     * set rounded corner from dp
     */
    public IconFontDrawable roundedCornersRxDp(@Dimension(unit = DP) int roundedCornerRxDp) {
        this.mRoundedCornerRx = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, roundedCornerRxDp, metrics);
        return this;
    }

    /**
     * set rounded corner from px
     */
    public IconFontDrawable roundedCornersRxPx(@Dimension(unit = PX) int roundedCornerRxPx) {
        this.mRoundedCornerRx = roundedCornerRxPx;
        return this;
    }

    /**
     * set rounded corner from res
     */
    public IconFontDrawable roundedCornersRyRes(@DimenRes int roundedCornerRyRes) {
        this.mRoundedCornerRy = context.getResources().getDimensionPixelSize(roundedCornerRyRes);
        return this;
    }

    /**
     * set rounded corner from dp
     */
    public IconFontDrawable roundedCornersRyDp(@Dimension(unit = DP) int roundedCornerRyDp) {
        this.mRoundedCornerRy = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, roundedCornerRyDp, metrics);
        return this;
    }

    /**
     * set rounded corner from px
     */
    public IconFontDrawable roundedCornersRyPx(@Dimension(unit = PX) int roundedCornerRyPx) {
        this.mRoundedCornerRy = roundedCornerRyPx;
        return this;
    }

    /**
     * set rounded corner from res
     */
    public IconFontDrawable roundedCornersRes(@DimenRes int roundedCornerRes) {
        this.mRoundedCornerRx = context.getResources().getDimensionPixelSize(roundedCornerRes);
        this.mRoundedCornerRy = this.mRoundedCornerRx;
        return this;
    }

    /**
     * set rounded corner from dp
     */
    public IconFontDrawable roundedCornersDp(@Dimension(unit = DP) int roundedCornerDp) {
        this.mRoundedCornerRx = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, roundedCornerDp, metrics);
        this.mRoundedCornerRy = this.mRoundedCornerRx;
        return this;
    }

    /**
     * set rounded corner from px
     */
    public IconFontDrawable roundedCornersPx(@Dimension(unit = PX) int roundedCornerPx) {
        this.mRoundedCornerRx = roundedCornerPx;
        this.mRoundedCornerRy = this.mRoundedCornerRx;
        return this;
    }

    /**
     * Set contour width from an dimen res for the icon
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable contourWidthRes(@DimenRes int contourWidthRes) {
        return contourWidthPx(context.getResources().getDimensionPixelSize(contourWidthRes));
    }

    /**
     * Set contour width from dp for the icon
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable contourWidthDp(@Dimension(unit = DP) int contourWidthDp) {
        return contourWidthPx((int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, contourWidthDp, metrics));
    }

    /**
     * Set contour width for the icon.
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable contourWidthPx(@Dimension(unit = PX) int contourWidth) {
        mContourWidth = contourWidth;

        final Paint contour = contourPaint();
        contour.setStrokeWidth(mContourWidth);

        drawContour(true);
        invalidateSelf();
        return this;
    }

    /**
     * Enable/disable contour drawing.
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable drawContour(boolean drawContour) {
        if (mDrawContour != drawContour) {
            mDrawContour = drawContour;

            if (mDrawContour) {
                mIconPadding += mContourWidth;
            } else {
                mIconPadding -= mContourWidth;
            }

            invalidateSelf();
        }
        return this;
    }

    /**
     * Set the colorFilter
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable colorFilter(ColorFilter cf) {
        setColorFilter(cf);
        return this;
    }

    /**
     * Sets the opacity
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable alpha(int alpha) {
        setAlpha(alpha);
        return this;
    }

    /**
     * Sets the style
     *
     * @return The current IconExtDrawable for chaining.
     */
    public IconFontDrawable style(Paint.Style style) {
        iconPaint.setStyle(style);
        return this;
    }

    /**
     * sets the typeface of the drawable
     * NOTE THIS WILL OVERWRITE THE ICONFONT!
     */
    public IconFontDrawable typeface(Typeface typeface) {
        iconPaint.setTypeface(typeface);
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mIcon != null || mPlainIcon != null) {
            final Rect viewBounds = getBounds();

            updatePaddingBounds(viewBounds);
            updateTextSize(viewBounds);
            offsetIcon(viewBounds);

            if (mRoundedCornerRy > -1 && mRoundedCornerRx > -1) {
                canvas.drawRoundRect(new RectF(0, 0, viewBounds.width(), viewBounds.height()), mRoundedCornerRx, mRoundedCornerRy, bgPaint());
            }

            mPath.close();

            if (mDrawContour) {
                canvas.drawPath(mPath, contourPaint());
            }

            iconPaint.setAlpha(mAlpha);
            iconPaint.setColorFilter(mColorFilter == null ? mTintFilter : mColorFilter);

            canvas.drawPath(mPath, iconPaint);
        }
    }

    @Override
    public void setTint(int tintColor) {
        setTintList(ColorStateList.valueOf(tintColor));
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mTint = tint;
        mTintFilter = updateTintFilter(tint, mTintMode);
        invalidateSelf();
    }

    @Override
    public void setTintMode(@NonNull PorterDuff.Mode tintMode) {
        mTintMode = tintMode;
        mTintFilter = updateTintFilter(mTint, tintMode);
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        offsetIcon(bounds);
        mPath.close();
        super.onBoundsChange(bounds);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public boolean setState(int[] stateSet) {
        setAlpha(mAlpha);
        return super.setState(stateSet);
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        if (mTint != null && mTintMode != null) {
            mTintFilter = updateTintFilter(mTint, mTintMode);
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    public int getIntrinsicWidth() {
        return sizeX;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizeY;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }


    @Override
    public void setAlpha(int alpha) {
        iconPaint.setAlpha(alpha);
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    /**
     * @return the IIcon which is used inside this IconicsDrawable
     */
    public IIcon getIcon() {
        return mIcon;
    }

    /**
     * @return the PlainIcon which is used inside this IconicsDrawable
     */
    public String getPlainIcon() {
        return mPlainIcon;
    }

    /**
     * just a helper method to get the alpha value
     */
    public int getCompatAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mColorFilter = cf;
        invalidateSelf();
    }

    @Override
    public void clearColorFilter() {
        mColorFilter = null;
        invalidateSelf();
    }

    /**
     * Creates a BitMap to use in Widgets or anywhere else
     *
     * @return bitmap to set
     */
    public Bitmap toBitmap() {
        if (sizeX == -1 || sizeY == -1) {
            this.actionBar();
        }

        final Bitmap bitmap = Bitmap.createBitmap(this.getIntrinsicWidth(), this.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);

        this.style(Paint.Style.FILL);

        final Canvas canvas = new Canvas(bitmap);
        this.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        this.draw(canvas);

        return bitmap;
    }

    //------------------------------------------
    // PRIVATE HELPER METHODS
    //------------------------------------------

    /**
     * Update the Padding Bounds
     *
     * @param viewBounds
     */
    private void updatePaddingBounds(Rect viewBounds) {
        if (mIconPadding >= 0
                && !(mIconPadding * 2 > viewBounds.width())
                && !(mIconPadding * 2 > viewBounds.height())) {
            mPaddingBounds.set(
                    viewBounds.left + mIconPadding,
                    viewBounds.top + mIconPadding,
                    viewBounds.right - mIconPadding,
                    viewBounds.bottom - mIconPadding);
        }
    }

    /**
     * Update the TextSize
     *
     * @param viewBounds
     */
    private void updateTextSize(Rect viewBounds) {
        float textSize = (float) viewBounds.height() * (respectFontBounds ? 1 : 2);
        iconPaint.setTextSize(textSize);

        String textValue = mIcon != null ? String.valueOf(mIcon.getCharacter()) : String.valueOf(mPlainIcon);
        iconPaint.getTextPath(textValue, 0, textValue.length(), 0, viewBounds.height(), mPath);
        mPath.computeBounds(mPathBounds, true);

        if (!respectFontBounds) {
            float deltaWidth = ((float) mPaddingBounds.width() / mPathBounds.width());
            float deltaHeight = ((float) mPaddingBounds.height() / mPathBounds.height());
            float delta = (deltaWidth < deltaHeight) ? deltaWidth : deltaHeight;
            textSize *= delta;

            iconPaint.setTextSize(textSize);

            iconPaint.getTextPath(textValue, 0, textValue.length(), 0, viewBounds.height(), mPath);
            mPath.computeBounds(mPathBounds, true);
        }
    }

    /**
     * Set the icon offset
     *
     * @param viewBounds
     */
    private void offsetIcon(Rect viewBounds) {
        float startX = viewBounds.centerX() - (mPathBounds.width() / 2);
        float offsetX = startX - mPathBounds.left;

        float startY = viewBounds.centerY() - (mPathBounds.height() / 2);
        float offsetY = startY - (mPathBounds.top);

        mPath.offset(offsetX + mIconOffsetX, offsetY + mIconOffsetY);
    }


    /**
     * Ensures the tint filter is consistent with the current tint color and
     * mode.
     */
    private PorterDuffColorFilter updateTintFilter(ColorStateList tint, PorterDuff.Mode tintMode) {
        if (tint == null || tintMode == null) {
            return null;
        }
        // setMode, setColor of PorterDuffColorFilter are not public method in SDK v7. (Thanks @Google still not accessible in API v24)
        // Therefore we create a new one all the time here. Don't expect this is called often.
        final int color = tint.getColorForState(getState(), Color.TRANSPARENT);
        return new PorterDuffColorFilter(color, tintMode);
    }


    /**
     * clones the icon
     */
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public IconFontDrawable clone() {
        return new IconFontDrawable(context, mIcon.getTypeface(), mIcon)
                .paddingPx(mIconPadding)
                .roundedCornersRxPx(mRoundedCornerRx)
                .roundedCornersRyPx(mRoundedCornerRy)
                .sizePxX(sizeX)
                .sizePxY(sizeY)
                .iconOffsetXPx(mIconOffsetX)
                .iconOffsetYPx(mIconOffsetY)
                .contourColor(contourColor)
                .contourWidthPx(mContourWidth)
                .backgroundColor(backgroundColor)
                .color(iconColor)
                .alpha(mAlpha)
                .drawContour(mDrawContour)
                .typeface(iconPaint.getTypeface());
    }
}