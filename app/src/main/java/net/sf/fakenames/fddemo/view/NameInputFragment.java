package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.support.v4.view.LayoutInflaterCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatDialog;
import android.view.LayoutInflater;
import android.view.View;

import net.sf.fakenames.fddemo.R;

import butterknife.BindView;
import butterknife.ButterKnife;

public final class NameInputFragment extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String ARG_TITLE = "fileTypeName";
    private static final String ARG_TYPE = "fileTypeId";

    public NameInputFragment() {}

    @SuppressLint("ValidFragment")
    public NameInputFragment(String fileTypeTitle, int type) {
        final Bundle bundle = new Bundle();
        bundle.putString(ARG_TITLE, fileTypeTitle);
        bundle.putInt(ARG_TYPE, type);
        setArguments(bundle);
    }

    private String fileTypeTitle;
    private int type;

    @BindView(R.id.dlg_input)
    TextInputEditText editText;

    @BindView(R.id.dlg_input_layout)
    TextInputLayout editLayout;

    @Override
    @SuppressWarnings("deprecation")
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        final Bundle bundle = getArguments();
        fileTypeTitle = bundle.getString(ARG_TITLE);
        type = bundle.getInt(ARG_TYPE);
    }

    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getActivity()).inflate(R.layout.name_input, null, false);

        ButterKnife.bind(this, view);

        editLayout.setHint(getString(R.string.enter_name, fileTypeTitle));

        return new AlertDialog.Builder(getActivity())
                .setCancelable(true)
                .setView(view)
                .setPositiveButton(android.R.string.ok, this)
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final String name = editText.getText().toString();

        ((FileNameReceiver) getActivity()).onNameChosen(name, type);
    }

    public interface FileNameReceiver {
        void onNameChosen(String name, int type);
    }
}
