package com.watlis.app;

import android.Manifest;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.watlis.app.data.MediaEntity;

/** Permission is requested only after the reader explicitly chooses Floating chapter. */
final class FloatingChapterLauncher {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<Intent> settings;
    private final ActivityResultLauncher<String> notifications;
    private long id = -1, created;
    FloatingChapterLauncher(AppCompatActivity activity, Bundle saved) {
        this.activity = activity;
        if (saved != null) { id = saved.getLong("floatingPending", -1); created = saved.getLong("floatingCreated"); }
        settings = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (id < 0) return;
            if (Settings.canDrawOverlays(activity)) notificationPermission();
            else { id = -1; toast(R.string.floating_permission_denied); }
        });
        notifications = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> start());
    }
    void save(Bundle out) { out.putLong("floatingPending", id); out.putLong("floatingCreated", created); }
    void launch(MediaEntity media) {
        id = media.id; created = media.createdAt;
        if (Settings.canDrawOverlays(activity)) { notificationPermission(); return; }
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.floating_permission_title)
                .setMessage(R.string.floating_permission_message).setNegativeButton("Cancel", (d, w) -> id = -1)
                .setOnCancelListener(d -> id = -1).setPositiveButton(R.string.floating_permission_settings, (d, w) -> {
                    try { settings.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName()))); }
                    catch (RuntimeException error) { id = -1; toast(R.string.floating_settings_unavailable); }
                }).show();
    }
    private void notificationPermission() {
        SharedPreferences prefs = activity.getSharedPreferences("floating_controls", Context.MODE_PRIVATE);
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else start();
    }
    private void start() {
        if (id < 0 || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = new Intent(activity, FloatingChapterService.class).putExtra(FloatingChapterService.ID, id)
                .putExtra(FloatingChapterService.CREATED, created).putExtra(FloatingChapterService.READY, new ResultReceiver(new Handler(Looper.getMainLooper())) {
                    @Override protected void onReceiveResult(int code, Bundle data) {
                        if (code == 1 && !activity.isFinishing() && !activity.isDestroyed()
                                && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                            activity.moveTaskToBack(true); toast(R.string.floating_started);
                        }
                    }
                });
        id = -1;
        try { ContextCompat.startForegroundService(activity, intent); }
        catch (RuntimeException error) { toast(R.string.floating_failed); }
    }
    private void toast(int text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
