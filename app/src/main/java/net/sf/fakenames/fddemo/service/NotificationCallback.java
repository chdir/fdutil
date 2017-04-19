package net.sf.fakenames.fddemo.service;

public interface NotificationCallback {
    void onStatusUpdate(String message, String subtext);

    void onProgressUpdate(String message);

    void onProgressUpdate(int precentage);

    void onDismiss();
}
