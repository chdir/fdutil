package net.sf.fakenames.fddemo.view;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.support.annotation.NonNull;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import net.sf.fakenames.fddemo.R;

import java.io.File;

/**
 * Defines a basic widget that will allow for fast scrolling a RecyclerView using
 * the basic paradigm of a handle and a bar.
 */
public final class DirFastScroller extends ViewGroup {

    private static final int[] STYLEABLE = R.styleable.AbsRecyclerViewFastScroller;

    // The handle that signifies the user's progress in the list
    private View thumb;

    // The long bar along which a handle travels
    private View track;

    private RecyclerView recycler;

    private OnScrollListener onScrollListener;
    private RecyclerView.AdapterDataObserver adapterDataObserver;

    private VerticalScreenPositionCalculator screenPositionCalculator;

    private final ViewDrugHelper drugHelper;
    private final GestureDetectorCompat trackHandler;

    private final OnLayoutChangeListener layoutListener = (v, l, t, r, b, l0, t0, r0, b0) -> onCreateScrollProgressCalculator();

    public DirFastScroller(Context context) {
        this(context, null, 0);
    }

    public DirFastScroller(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DirFastScroller(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray attributes = getContext().getTheme().obtainStyledAttributes(attrs, STYLEABLE, defStyleAttr, R.style.FastScrollerDefault);

        try {
            int layoutResource = attributes.getResourceId(R.styleable.AbsRecyclerViewFastScroller_rfs_fast_scroller_layout, 0);

            if (layoutResource != 0) {
                final Context themed = new ContextThemeWrapper(context, R.style.FastScrollerDefault);

                LayoutInflater.from(themed).inflate(layoutResource, this, true);
            }
        } finally {
            attributes.recycle();
        }

        drugHelper = ViewDrugHelper.create(this, new DragCallback());

        trackHandler = new GestureDetectorCompat(getContext(), new TrackEventHandler());
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);

        switch (child.getId()) {
            case R.id.scroll_bar:
                track = child;
                track.setOnTouchListener((v, event) -> trackHandler.onTouchEvent(event));
                track.addOnLayoutChangeListener(layoutListener);
                break;
            case R.id.scroll_handle:
                thumb = child;
                thumb.setOnTouchListener((v, event) -> thumbTouched(event));
                break;
        }
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);

        switch (child.getId()) {
            case R.id.scroll_bar:
                track = null;
                child.setOnTouchListener(null);
                child.removeOnLayoutChangeListener(layoutListener);
                break;
            case R.id.scroll_handle:
                thumb = null;
                child.setOnTouchListener(null);
                break;
        }
    }

    private boolean thumbTouched(MotionEvent event) {
        switch (MotionEventCompat.getActionMasked(event)) {
            case MotionEventCompat.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                if (recycler != null) {
                    recycler.stopScroll();
                }
        }

        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return drugHelper.shouldInterceptTouchEvent(ev) || super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        drugHelper.processTouchEvent(event);

        return super.onTouchEvent(event);
    }

    @Override
    public boolean isImportantForAccessibility() {
        return false;
    }

    public void setRecyclerView(RecyclerView recycler) {
        this.recycler = recycler;

        checkValidity();
    }

    private int getListPositionFromScrollProgress(float scrollProgress) {
        if (!canComputeFastScroll) {
            return -1;
        }

        int itemCount = recycler.getAdapter().getItemCount();

        int pos = (int) (itemCount * scrollProgress);

        // limit in case scrollProgress is exactly 1
        if (pos >= itemCount) {
            pos = itemCount - 1;
        }

        return pos;
    }

    @NonNull
    public OnScrollListener getOnScrollListener() {
        if (onScrollListener == null) {
            onScrollListener = new RecyclerViewScrollListener();
        }
        return onScrollListener;
    }

    @NonNull
    public RecyclerView.AdapterDataObserver getAdapterDataObserver() {
        if (adapterDataObserver == null) {
            adapterDataObserver = new AdapterObserver();
        }
        return adapterDataObserver;
    }

    // poor-man's smooth animation done android way
    public void moveHandleToPosition() {
        // do not engage in meaningless work here
        if (getVisibility() != VISIBLE) {
            return;
        }

        if (screenPositionCalculator == null) {
            // not laid out yet, postpone the call
            if (ViewCompat.isAttachedToWindow(this) && !ViewCompat.isLaidOut(this)) {
                getHandler().post(this::moveHandleToPosition);
            }
            return;
        }

        if (!canComputeFastScroll) {
            return;
        }

        if (drugHelper.getViewDragState() != ViewDrugHelper.STATE_IDLE) {
            // do not interfere with user-initiated dragging...
            return;
        }

        // ...but quickly abort ongoing animations, if currently settling
        drugHelper.release();

        final int trackLength = recycler.computeVerticalScrollRange();
        final int extra = recycler.computeVerticalScrollExtent();
        final int thumbTop = recycler.computeVerticalScrollOffset();

        if (trackLength <= 0 || thumbTop < 0 || trackLength == thumbTop) {
            canComputeFastScroll = false;

            return;
        }

        float scrollProgress = thumbTop / ((float) trackLength - extra);

        final View child = thumb;

        final FastScrollerLayoutParams position = (FastScrollerLayoutParams) child.getLayoutParams();

        position.ratio = scrollProgress;

        final int newTop = screenPositionCalculator.getYPositionFromScrollProgress(scrollProgress);

        position.y = newTop;

        final int oldTop = child.getTop();
        final int oldBottom = child.getBottom();

        ViewCompat.offsetTopAndBottom(child, newTop - oldTop);

        postInvalidateOnAnimation(0, Math.min(newTop, oldTop),
                getMeasuredWidth(), Math.max(oldBottom, newTop + child.getMeasuredHeight()));
    }

    private int maxWidth;
    private int maxHeight;
    private int childState;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        maxWidth = 0; maxHeight = 0; childState = 0;

        measureChildren(widthMeasureSpec, heightMeasureSpec);

        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        setMeasuredDimension(
                resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec, childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void measureChild(View child, int parentWidthMeasureSpec, int parentHeightMeasureSpec) {
        super.measureChildWithMargins(child, parentWidthMeasureSpec, 0, parentHeightMeasureSpec, 0);

        final FastScrollerLayoutParams lp = (FastScrollerLayoutParams) child.getLayoutParams();

        maxWidth = Math.max(maxWidth, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        maxHeight = Math.max(maxHeight, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        childState = combineMeasuredStates(childState, child.getMeasuredState());
    }

    // unlike code above this _actually_ moves thumb in specific position
    @Override
    @SuppressLint("RtlHardcoded")
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int count = getChildCount();

        final int parentLeft = getPaddingLeft();
        final int parentRight = right - left - getPaddingRight();

        final int parentTop = getPaddingTop();
        final int parentBottom = bottom - top - getPaddingBottom();

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final FastScrollerLayoutParams lp = (FastScrollerLayoutParams) child.getLayoutParams();

                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft;
                int childTop;

                int gravity = lp.gravity;
                if (gravity == -1) {
                    gravity = Gravity.TOP | Gravity.START;
                }

                final int layoutDirection = getLayoutDirection();
                final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
                final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft = parentLeft + (parentRight - parentLeft - width) / 2 +
                                lp.leftMargin - lp.rightMargin;
                        break;
                    case Gravity.RIGHT:
                        childLeft = parentRight - width - lp.rightMargin;
                        break;
                    case Gravity.LEFT:
                    default:
                        childLeft = parentLeft + lp.leftMargin;
                }

                switch (verticalGravity) {
                    case Gravity.TOP:
                        childTop = parentTop + lp.topMargin;
                        break;
                    case Gravity.CENTER_VERTICAL:
                        childTop = parentTop + (parentBottom - parentTop - height) / 2 +
                                lp.topMargin - lp.bottomMargin;
                        break;
                    case Gravity.BOTTOM:
                        childTop = parentBottom - height - lp.bottomMargin;
                        break;
                    default:
                        childTop = parentTop + lp.topMargin;
                }

                if (child == thumb) {
                    checkValidity();

                    if (canComputeFastScroll) {
                        if (lp.isPositionSet()) {
                            childTop = lp.y;
                        }
                    }

                    child.layout(childLeft, childTop, childLeft + width, childTop + height);

                    if (canComputeFastScroll) {
                        if (screenPositionCalculator == null) {
                            onCreateScrollProgressCalculator();
                        }
                    }
                } else {
                    child.layout(childLeft, childTop, childLeft + width, childTop + height);
                }
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!canComputeFastScroll) {
            return;
        }

        super.dispatchDraw(canvas);
    }

    protected void onCreateScrollProgressCalculator() {
        VerticalScrollBoundsProvider boundsProvider = new VerticalScrollBoundsProvider();
        //scrollProgressCalculator = new ScrollProgressCalculator(boundsProvider);
        screenPositionCalculator = new VerticalScreenPositionCalculator(boundsProvider);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();

        if (drugHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    // if you use this with LinearLayoutManager, consider using scrollToPositionWithOffset instead
    protected void scrollListToPosition(int listPosition) {
        recycler.scrollToPosition(listPosition);
    }

    final class TrackEventHandler extends GestureDetector.SimpleOnGestureListener {
        final int[] tempLoc = new int[2];

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (track != null && thumb != null) {
                track.getLocationOnScreen(tempLoc);

                int offset = (int) (tempLoc[1] + e.getRawY());

                drugHelper.release();

                final FastScrollerLayoutParams position = (FastScrollerLayoutParams) thumb.getLayoutParams();

                position.y = offset;

                position.ratio = screenPositionCalculator.getScrollRatio(offset);

                ViewCompat.offsetTopAndBottom(track, offset - thumb.getTop());

                ViewCompat.postInvalidateOnAnimation(DirFastScroller.this);

                return true;
            }

            return false;
        }
    }

    final class DragCallback extends ViewDrugHelper.Callback {
        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            if (child == thumb && recycler != null) {
                final int range = recycler.computeVerticalScrollRange();

                if (range > 0) {
                    final int extent = recycler.computeVerticalScrollExtent();

                    return extent < range;
                }
            }

            return false;
        }

        @Override
        public void onViewCaptured(View capturedChild, int activePointerId) {
            recycler.stopScroll();

            capturedChild.post(() -> capturedChild.setPressed(true));
        }

        @Override
        public void onViewDragStateChanged(int state) {
            if (state != ViewDrugHelper.STATE_DRAGGING) {
                thumb.setPressed(false);
            }
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            if (yvel != 0) {
                drugHelper.flingCapturedView(0, track.getTop(), 0, track.getBottom() - thumb.getHeight());

                ViewCompat.postInvalidateOnAnimation(DirFastScroller.this);
            }
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            return child == thumb && screenPositionCalculator != null ? screenPositionCalculator.getDragRange() : 0;
        }

        @Override
        public int getViewHorizontalDragRange(View child) {
            return 0;
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            return 0;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            return child == thumb && screenPositionCalculator != null ? screenPositionCalculator.clamp(top) : 0;
        }

        @Override
        public void onViewPositionChanged(View child, int left, int top, int dx, int dy) {
            if (drugHelper.getViewDragState() == ViewDrugHelper.STATE_IDLE) {
                // we are being dragged by code (likely from within RecyclerView itself!)
                return;
            }

            final FastScrollerLayoutParams position = (FastScrollerLayoutParams) child.getLayoutParams();

            position.y = top;
            position.ratio = screenPositionCalculator.getScrollRatio(top);

            int listPosition = getListPositionFromScrollProgress(position.ratio);

            if (listPosition != -1) {
                scrollListToPosition(listPosition);
            }
        }
    }

    final class RecyclerViewScrollListener extends OnScrollListener {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            moveHandleToPosition();
        }
    }

    final class VerticalScrollBoundsProvider {
        private final int mMinimumScrollY;
        private final int mMaximumScrollY;
        private final int scrollRange;

        VerticalScrollBoundsProvider() {
            mMinimumScrollY = track.getTop();
            mMaximumScrollY = track.getBottom() - thumb.getHeight();
            scrollRange = track.getBottom() - track.getTop();
        }

        int getMinimumScrollY() {
            return mMinimumScrollY;
        }

        int getMaximumScrollY() {
            return mMaximumScrollY;
        }

        int getScrollRange() {
            return scrollRange;
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p.getClass() == FastScrollerLayoutParams.class;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public FastScrollerLayoutParams generateLayoutParams(AttributeSet attrs) {
        return new FastScrollerLayoutParams(new FrameLayout.LayoutParams(getContext(), attrs));
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return FastScrollerLayoutParams.from(p);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new FastScrollerLayoutParams();
    }

    static final class FastScrollerLayoutParams extends FrameLayout.LayoutParams {
        float ratio = Float.NaN;
        int y = Integer.MIN_VALUE;

        FastScrollerLayoutParams() {
            //noinspection ResourceType
            super(WRAP_CONTENT, WRAP_CONTENT);
        }

        FastScrollerLayoutParams(FrameLayout.LayoutParams p) {
            super(p);
        }

        FastScrollerLayoutParams(MarginLayoutParams p) {
            super(p);
        }

        FastScrollerLayoutParams(ViewGroup.LayoutParams p) {
            super(p);
        }

        static FastScrollerLayoutParams from(ViewGroup.LayoutParams p) {
            if (p.getClass() == FrameLayout.LayoutParams.class || p instanceof FrameLayout.LayoutParams) {
                return new FastScrollerLayoutParams((FrameLayout.LayoutParams) p);
            }

            if (p instanceof MarginLayoutParams) {
                return new FastScrollerLayoutParams((MarginLayoutParams) p);
            }

            return new FastScrollerLayoutParams(p);
        }

        boolean isPositionSet() {
            return y != Integer.MIN_VALUE;
        }
    }

    static final class VerticalScreenPositionCalculator {
        private final VerticalScrollBoundsProvider mVerticalScrollBoundsProvider;

        VerticalScreenPositionCalculator(VerticalScrollBoundsProvider scrollBoundsProvider) {
            mVerticalScrollBoundsProvider = scrollBoundsProvider;
        }

        int getYPositionFromScrollProgress(float scrollProgress) {
            int absScroll = mVerticalScrollBoundsProvider.getMinimumScrollY() +
                    (int) (scrollProgress * mVerticalScrollBoundsProvider.getScrollRange());

            return Math.max(
                    mVerticalScrollBoundsProvider.getMinimumScrollY(),
                    Math.min(mVerticalScrollBoundsProvider.getMaximumScrollY(), absScroll)
            );
        }

        float getScrollRatio(int y) {
            if (y == mVerticalScrollBoundsProvider.getMinimumScrollY()) {
                return 0f;
            } else if (y == mVerticalScrollBoundsProvider.getMaximumScrollY()) {
                return 1f;
            } else {
                return y / (float) mVerticalScrollBoundsProvider.getScrollRange();
            }
        }

        int getDragRange() {
            return mVerticalScrollBoundsProvider.getMaximumScrollY() - mVerticalScrollBoundsProvider.getMinimumScrollY();
        }

        int clamp(int newTop) {
            final int bottomLimit = mVerticalScrollBoundsProvider.getMaximumScrollY();

            if (newTop >= bottomLimit) {
                return bottomLimit;
            }

            final int topLimit = mVerticalScrollBoundsProvider.getMinimumScrollY();

            if (newTop <= mVerticalScrollBoundsProvider.getMinimumScrollY()) {
                return topLimit;
            }

            return newTop;
        }
    }

    final class AdapterObserver extends RecyclerView.AdapterDataObserver {
        @Override
        public void onChanged() {
            checkValidity();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            checkValidity();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            checkValidity();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
        }
    }

    private boolean canComputeFastScroll;

    private void checkValidity() {
        if (thumb == null || track == null) {
            canComputeFastScroll = false;
            return;
        }

        if (recycler == null || !ViewCompat.isLaidOut(recycler)) {
            canComputeFastScroll = false;
            return;
        }

        final RecyclerView.Adapter<?> adapter = recycler.getAdapter();

        if (adapter == null) {
            canComputeFastScroll = false;
            return;
        }

        final int itemCount = adapter.getItemCount();

        if (itemCount <= 0 || itemCount == Integer.MAX_VALUE) {
            canComputeFastScroll = false;
            return;
        }

        if (!canComputeFastScroll) {
            canComputeFastScroll = true;

            if (!ViewCompat.isInLayout(this)) {
                requestLayout();
            }
        }
    }
}