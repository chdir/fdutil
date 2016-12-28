package net.sf.fakenames.fddemo.view;

import android.content.Context;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.RecyclerView;

import net.sf.fakenames.fddemo.MainActivity;

public final class DirLayoutManager extends LinearLayoutManager {
    private final OrientationHelper orientationHelper;

    private OnLayoutCallback callback;

    public DirLayoutManager(Context context) {
        super(context);

        this.orientationHelper = OrientationHelper.createVerticalHelper(this);

        setSmoothScrollbarEnabled(true);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        super.onLayoutChildren(recycler, state);

        if (!state.isPreLayout()) {
            orientationHelper.onLayoutComplete();
        }

        callback.onLaidOut(this);
    }

    @Override
    protected int getExtraLayoutSpace(RecyclerView.State state) {
        return orientationHelper.getTotalSpace();
    }

    public void setCallback(OnLayoutCallback callback) {
        this.callback = callback;
    }

    public interface OnLayoutCallback {
        void onLaidOut(RecyclerView.LayoutManager dirLayoutManager);
    }
}
