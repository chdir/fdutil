package net.sf.fakenames.fddemo.service;

import android.app.Notification;

import java.util.concurrent.Callable;

public interface FileTask extends Callable<Void> {
    String ACTION_START = "net.sf.fakenames.action.START";
    String ACTION_CANCEL = "net.sf.fakenames.action.CANCEL";

    Notification getNotification();
}
