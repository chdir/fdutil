package net.sf.fakenames.fddemo.view;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.View;

public final class DirRecyclerView extends RecyclerView {
    public DirRecyclerView(Context context) {
        super(context);
    }

    public DirRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DirRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    private ContextMenu.ContextMenuInfo lastMenuInfo;

    @Override
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() {
        return lastMenuInfo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean showContextMenuForChild(View originalView) {
        final DirAdapter adapter = (DirAdapter) getAdapter();
        if (adapter == null) {
            return false;
        }

        final DirItemHolder holder = (DirItemHolder) getChildViewHolder(originalView);
        if (holder == null) {
            return false;
        }

        lastMenuInfo = new FileMenuInfo(holder.getDirInfo(), adapter.getFd(), holder.getAdapterPosition());

        return super.showContextMenuForChild(this);
    }
}
