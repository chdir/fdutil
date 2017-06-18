package net.sf.fakenames.fddemo;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import net.sf.fakenames.fddemo.view.ErrorReportDialog;

import java.io.IOException;
import java.net.URL;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public final class ErrorReportActivity extends AppCompatActivity {
    static {
        //noinspection deprecation
        URL.setURLStreamHandlerFactory(new OkUrlFactory(new OkHttpClient()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView()
                .setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        ((FdDemoApp) getApplication()).resetErrorHandler();

        final ErrorReportDialog error = new ErrorReportDialog();

        error.show(getSupportFragmentManager(), null);
    }

    @Override
    public void finish() {
        super.finish();

        // prevent animations from playing, they don't look good here
        overridePendingTransition(0, 0);
    }
}
