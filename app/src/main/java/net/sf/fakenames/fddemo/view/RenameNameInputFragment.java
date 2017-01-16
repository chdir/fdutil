package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import net.sf.fakenames.fddemo.R;

import butterknife.BindView;
import butterknife.ButterKnife;

public final class RenameNameInputFragment extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String ARG_TITLE = "fileTypeName";

    public RenameNameInputFragment() {}

    @SuppressLint("ValidFragment")
    public RenameNameInputFragment(String fileName) {
        final Bundle bundle = new Bundle();
        bundle.putString(ARG_TITLE, fileName);
        setArguments(bundle);
    }

    private String fileName;

    @BindView(R.id.dlg_input)
    TextInputEditText editText;

    @BindView(R.id.dlg_input_layout)
    TextInputLayout editLayout;

    @Override
    @SuppressWarnings("deprecation")
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        final Bundle bundle = getArguments();
        fileName = bundle.getString(ARG_TITLE);
    }

    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getActivity()).inflate(R.layout.name_input, null, false);

        ButterKnife.bind(this, view);

        editLayout.setHint(getString(R.string.enter_name, "new"));

        final EditText editText = editLayout.getEditText();

        assert editText != null;

        editText.setText(fileName);

        return new AlertDialog.Builder(getActivity())
                .setCancelable(true)
                .setView(view)
                .setPositiveButton(android.R.string.ok, this)
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final String name = editText.getText().toString();

        ((FileNameReceiver) getActivity()).onNewNameChosen(fileName, name);
    }

    public interface FileNameReceiver {
        void onNewNameChosen(String name, String newName);
    }
}
