package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import net.sf.fakenames.fddemo.R;
import net.sf.fdlib.Directory;
import net.sf.fdlib.UnreliableIterator;

import java.io.IOException;

import butterknife.BindColor;
import butterknife.BindView;
import butterknife.ButterKnife;

import static android.support.v7.appcompat.R.color.primary_text_default_material_dark;

public final class DirItemHolder extends RecyclerView.ViewHolder {
    @BindColor(R.color.blue_A700)
    ColorStateList directoryColor;

    @BindColor(R.color.black_900)
    ColorStateList miscColor;

    @BindColor(R.color.pink_A700)
    ColorStateList symlinkColor;

    @BindView(android.R.id.text1)
    TextView textView;

    private boolean placeholder;

    private Directory.Entry reusable = new Directory.Entry();

    public DirItemHolder(View itemView) {
        super(itemView);

        ButterKnife.bind(this, itemView);
    }

    public void setFile(UnreliableIterator<Directory.Entry> file) throws IOException {
        file.get(reusable);

        Log.d("TRACE", reusable + " pos " + file.getPosition());

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
}
