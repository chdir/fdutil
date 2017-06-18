package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import net.sf.fakenames.fddemo.AppPrefs;
import net.sf.fakenames.fddemo.FdDemoApp;
import net.sf.fakenames.fddemo.R;
import net.sf.fakenames.fddemo.util.Utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

public final class ErrorReportDialog extends DialogFragment {
    private static final String U = "http://xfd.sourceforge.net/cgi-bin/create";

    private View progress;

    private View message;

    @NonNull
    @Override
    @SuppressLint("InflateParams")
    public Dialog onCreateDialog(final Bundle savedInstanceState)
    {
        final View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.act_error_report, null, false);

        progress = dialogView.findViewById(R.id.act_report_progress);

        message = dialogView.findViewById(R.id.act_report_tv_message);

        final AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .setTitle(R.string.app_has_crashed)
                .setPositiveButton(R.string.send, (dialog, which) -> {})
                .setNegativeButton(R.string.cancel, (dialog, which) -> {});

        final AlertDialog alert = alertBuilder.create();

        alert.setOnShowListener(dialog -> {
            final Button button = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);

            button.setOnClickListener(view -> sendReport());
        });

        final Window w = alert.getWindow();

        assert w != null;

        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        w.setWindowAnimations(0);

        alert.setCanceledOnTouchOutside(false);

        TextView textView = (TextView) alert.getWindow().getDecorView().findViewById(android.R.id.message);
        if (textView != null) {
            textView.setTextIsSelectable(true);
        }

        return alert;
    }

    private void tryFinish() {
        final Activity a = getActivity();

        if (a != null) {
            a.finish();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);

        tryFinish();
    }

    private void sendReport() {
        final Intent intent = getActivity().getIntent();

        intent.setExtrasClassLoader(ErrorReportDialog.class.getClassLoader());

        final byte[] hash = intent.getByteArrayExtra(FdDemoApp.EXTRA_HASH);
        final byte[] trace = intent.getByteArrayExtra(FdDemoApp.EXTRA_TRACE);

        new ReportSender(getContext(), hash, trace).execute();
    }

    private void showProgress() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.INVISIBLE);
    }

    private void showText() {
        progress.setVisibility(View.INVISIBLE);
        message.setVisibility(View.VISIBLE);
    }

    private final class ReportSender extends AsyncTask<Void, Void, Throwable> {
        private final byte[] hash;
        private final byte[] trace;
        private final Context context;

        ReportSender(Context context, byte[] hash, byte[] trace) {
            this.context = context;
            this.hash = hash;
            this.trace = trace;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            showProgress();
        }

        @Override
        protected Throwable doInBackground(Void... params) {
            final String INSTALL_ID = AppPrefs.getUserId(context);

            final HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) new URL(U + "?" + encodeHex(hash)).openConnection();

                try {
                    conn.setRequestMethod("PUT");
                    conn.setRequestProperty("User-Agent", INSTALL_ID);
                    conn.setRequestProperty("Authorization", "Basic cGxhY2Vob2xkZXI6MTEx");
                    conn.setRequestProperty("Content-Type", "application/octet-stream");
                    conn.setRequestProperty("Transfer-Encoding", "identity");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setRequestProperty("Expect", "100-continue");
                    conn.setRequestProperty("Connection", "close");
                    conn.setUseCaches(false);
                    conn.setDoOutput(true);

                    conn.setFixedLengthStreamingMode(trace.length);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(trace);
                        os.flush();

                        final int response = conn.getResponseCode();

                        System.out.println(conn.getResponseMessage());

                        InputStream is = response >= 400 ? conn.getErrorStream() : conn.getInputStream();

                        if (is != null) {
                            Utils.logStreamContents(is);
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Throwable t) {
                Utils.printTraceCautiously(t);

                return t;
            }

            return null;
        }

        @Override
        protected void onPostExecute(Throwable throwable) {
            final String message = throwable == null ? "Report sent!" : "Failed to send error report: " + throwable;

            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();

            if (throwable == null) {
                dismiss();
            } else {
                showText();
            }
        }
    }

    private static final char[] HEX_DIGITS =
            {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    public static String encodeHex(final byte[] data) {
        final int l = data.length;
        final char[] out = new char[l<<1];
        for( int i=0,j=0; i<l; i++ ){
            out[j++] = HEX_DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = HEX_DIGITS[0x0F & data[i]];
        }
        return new String(out);
    }
}
