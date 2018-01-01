package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.StringRes;
import android.support.v7.app.AlertDialog;

import java.io.FileOutputStream;

public final class ConfirmationDialog extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String ARG_TITLE = "title";
    private static final String ARG_MESSAGE = "message";
    private static final String ARG_AFFIRMATION = "affirm";
    private static final String ARG_FILENAME = "fileName";

    @Deprecated
    public ConfirmationDialog() {}

    @SuppressLint("ValidFragment")
    public ConfirmationDialog(CharSequence fileName, @StringRes int title, @StringRes int message, @StringRes int affirm) {
        final Bundle bundle = new Bundle();
        bundle.putCharSequence(ARG_FILENAME, fileName);
        bundle.putInt(ARG_TITLE, title);
        bundle.putInt(ARG_MESSAGE, message);
        bundle.putInt(ARG_AFFIRMATION, affirm);
        setArguments(bundle);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        final Bundle bundle = getArguments();
        title = bundle.getInt(ARG_TITLE);
        message = bundle.getInt(ARG_MESSAGE);
        affirm = bundle.getInt(ARG_AFFIRMATION);
        fileName = bundle.getCharSequence(ARG_FILENAME);
    }

    private CharSequence fileName;
    private @StringRes int title;
    private @StringRes int message;
    private @StringRes int affirm;

    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new AlertDialog.Builder(getActivity())
                .setCancelable(true)
                .setTitle(title)
                .setMessage(getString(message, fileName))
                .setPositiveButton(affirm, this)
                .setNegativeButton(android.R.string.cancel, this)
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            ((ConfirmationReceiver) getActivity()).onAffirmed(fileName);
        }
    }

    public interface ConfirmationReceiver {
        void onAffirmed(CharSequence filename);
    }
}
