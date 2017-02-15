package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.TextView;

import net.sf.fakenames.fddemo.R;
import net.sf.xfd.Directory;
import net.sf.xfd.UnreliableIterator;

import java.io.IOException;

import butterknife.BindColor;
import butterknife.BindView;
import butterknife.ButterKnife;

public final class DirItemHolder extends RecyclerView.ViewHolder {
    @BindColor(R.color.blue_A700)
    ColorStateList directoryColor;

    @BindColor(R.color.black_900)
    ColorStateList miscColor;

    @BindColor(R.color.pink_A700)
    ColorStateList symlinkColor;

    @BindColor(R.color.orange_A700)
    ColorStateList socketColor;

    @BindColor(R.color.purple_A700)
    ColorStateList fifoColor;

    @BindView(android.R.id.text1)
    TextView textView;

    private boolean placeholder;

    private Directory.Entry reusable = new Directory.Entry();

    public DirItemHolder(View itemView) {
        super(itemView);

        if (Build.VERSION.SDK_INT >= 23) {
            itemView.setContextClickable(true);
            itemView.setOnContextClickListener(ContextClickListener.INSTANCE);
        }

        ButterKnife.bind(this, itemView);
    }

    public void setFile(UnreliableIterator<Directory.Entry> file) throws IOException {
        file.get(reusable);

        //Log.d("TRACE", reusable + " pos " + file.getPosition());

        textView.setText(reusable.name);

        ColorStateList textColor;

        if (reusable.type != null) {
            switch (reusable.type) {
                case DIRECTORY:
                    textColor = directoryColor;
                    break;
                case LINK:
                    textColor = symlinkColor;
                    break;
                case DOMAIN_SOCKET:
                    textColor = socketColor;
                    break;
                case NAMED_PIPE:
                    textColor = fifoColor;
                    break;
                default:
                    textColor = miscColor;
            }
        } else {
            textColor = miscColor;
        }

        if (textView.getTextColors() != textColor) {
            textView.setTextColor(textColor);
        }

        placeholder = false;
    }

    @SuppressLint("SetTextI18n")
    public void clearHolder() {
        textView.setText("");

        placeholder = true;
    }

    public Directory.Entry getDirInfo() {
        return reusable;
    }

    public boolean isPlaceholder() {
        return placeholder;
    }

    @Override
    public String toString() {
        return "DirItem[isPlaceholder: " + placeholder + "; item: " + reusable + "]";
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private static final class ContextClickListener implements View.OnContextClickListener {
        private static final ContextClickListener INSTANCE = new ContextClickListener();

        @Override
        public boolean onContextClick(View v) {
            return v.showContextMenu();
        }
    }
}
