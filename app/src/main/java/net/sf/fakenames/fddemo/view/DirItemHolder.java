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

import butterknife.Bind;
import butterknife.BindColor;
import butterknife.ButterKnife;

public final class DirItemHolder extends RecyclerView.ViewHolder {
    @BindColor(R.color.text_color_directory)
    ColorStateList directoryColor;

    @BindColor(R.color.text_color_file)
    ColorStateList miscColor;

    @BindColor(R.color.text_color_symlink)
    ColorStateList symlinkColor;

    @BindColor(R.color.text_color_socket)
    ColorStateList socketColor;

    @BindColor(R.color.text_color_fifo)
    ColorStateList fifoColor;

    @Bind(android.R.id.text1)
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
