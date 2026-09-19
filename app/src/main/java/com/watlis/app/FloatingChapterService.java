package com.watlis.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.room.InvalidationTracker;
import com.watlis.app.data.WatlisDatabase;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** User-started, single-title control. No covers, library snapshots, polling, or wake locks. */
public final class FloatingChapterService extends Service {
    static final String OPEN = "com.watlis.app.OPEN_FLOATING_DETAIL", CLOSE = "com.watlis.app.CLOSE_FLOATING",
            CHANGED = "com.watlis.app.FLOATING_CHANGED", ID = "mediaId", CREATED = "mediaCreatedAt", READY = "ready";
    static final AtomicLong changes = new AtomicLong();
    private static final String CHANNEL = "floating_chapter";
    private static final int NOTIFICATION = 2401;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FloatingChapterStore store;
    private WatlisDatabase db;
    private WindowManager windows;
    private Context windowContext;
    private WindowManager.LayoutParams layout;
    private FloatingChapterView view;
    private FloatingChapterStore.State state;
    private ResultReceiver ready;
    private long id, createdAt;
    private int generation, pending;
    private double shown;
    private boolean attached, destroyed, closing;
    private final Runnable reload = this::refresh;
    private final InvalidationTracker.Observer observer = new InvalidationTracker.Observer(
            "media", "user_progress", "media_types", "genres", "media_genres") {
        @Override public void onInvalidated(@NonNull Set<String> tables) {
            main.post(() -> { if (!destroyed && !closing) { main.removeCallbacks(reload); main.postDelayed(reload, 80); } });
        }
    };
    @Override public void onCreate() {
        super.onCreate(); db = WatlisDatabase.get(this); store = new FloatingChapterStore(db);
        db.getInvalidationTracker().addObserver(observer);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CLOSE.equals(intent.getAction())) { finish(false); return START_NOT_STICKY; }
        ready = intent.getParcelableExtra(READY, ResultReceiver.class);
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION, notification);
            if (!Settings.canDrawOverlays(this)) throw new SecurityException("Overlay permission required");
            id = intent.getLongExtra(ID, -1); createdAt = intent.getLongExtra(CREATED, -1);
            generation++; pending = 0; closing = false; state = null;
            if (view != null) view.loading();
            refresh();
        } catch (RuntimeException error) { fail(error, R.string.floating_failed); }
        return START_NOT_STICKY;
    }
    private void refresh() {
        if (destroyed || closing) return;
        final int version = generation; final long target = id, created = createdAt;
        worker.execute(() -> {
            try {
                FloatingChapterStore.State loaded = store.read(target, created);
                main.post(() -> {
                    if (destroyed || closing || version != generation) return;
                    if (loaded == null) { fail(null, R.string.floating_missing); return; }
                    state = loaded;
                    if (pending == 0) shown = loaded.progress;
                    try {
                        if (!attached) attach();
                        render();
                        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
                        if (ready != null) { ready.send(1, Bundle.EMPTY); ready = null; }
                    } catch (RuntimeException error) { fail(error, R.string.floating_failed); }
                });
            } catch (RuntimeException error) { main.post(() -> { if (version == generation && !destroyed) fail(error, R.string.floating_failed); }); }
        });
    }
    private void increment(int delta) {
        if (state == null || closing || destroyed || pending >= 64 || delta < 0 && shown <= 0) return;
        final int version = generation; final long target = id, created = createdAt;
        pending++; shown = Math.max(0, shown + delta); render();
        worker.execute(() -> {
            FloatingChapterStore.State saved = null; RuntimeException failure = null;
            try {
                saved = store.increment(target, created, delta);
                if (saved != null) {
                    changes.incrementAndGet();
                    sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
                }
            } catch (RuntimeException error) { failure = error; }
            final FloatingChapterStore.State result = saved; final RuntimeException error = failure;
            main.post(() -> {
                if (destroyed || version != generation) return;
                pending--;
                if (error != null) {
                    android.util.Log.e("FloatingChapter", "Could not save progress", error);
                    toast(R.string.floating_save_failed);
                    if (pending == 0) { view.loading(); refresh(); }
                } else if (result == null) { fail(null, R.string.floating_missing); }
                else {
                    state = result;
                    if (pending == 0) shown = result.progress;
                    render();
                    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
                }
            });
        });
    }
    private void render() { if (view != null && state != null) view.render(state, shown, !closing && pending < 64); }
    @android.annotation.SuppressLint("RtlHardcoded") // Coordinates are absolute physical screen pixels, including in RTL.
    private void attach() {
        Display display = getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("No display available");
        windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windows = windowContext.getSystemService(WindowManager.class);
        view = new FloatingChapterView(windowContext, new FloatingChapterView.Actions() {
            @Override public void increment(int delta) { FloatingChapterService.this.increment(delta); }
            @Override public void open() { finish(true); }
            @Override public void close() { finish(false); }
            @Override public void drag(float dx, float dy, boolean finished) {
                if (!attached) return;
                layout.x += Math.round(dx); layout.y += Math.round(dy); clamp();
                try { windows.updateViewLayout(view, layout); } catch (RuntimeException error) { fail(error, R.string.floating_failed); }
                if (finished) rememberPosition();
            }
        });
        layout = new WindowManager.LayoutParams(dp(248), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.setFitInsetsTypes(0); layout.setTitle(getString(R.string.floating_controls));
        restorePosition(); windows.addView(view, layout); attached = true;
        view.post(() -> { if (attached) { clamp(); windows.updateViewLayout(view, layout); } });
    }
    private Rect safeArea() {
        WindowMetrics metrics = windows.getCurrentWindowMetrics(); Rect bounds = new Rect(metrics.getBounds());
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        bounds.inset(insets.left + dp(8), insets.top + dp(8), insets.right + dp(8), insets.bottom + dp(8)); return bounds;
    }
    private int height() { return view != null && view.getHeight() > 0 ? view.getHeight() : dp(72); }
    private void clamp() {
        Rect area = safeArea(); layout.width = Math.min(dp(248), area.width());
        layout.x = Math.max(area.left, Math.min(layout.x, area.right - layout.width));
        layout.y = Math.max(area.top, Math.min(layout.y, area.bottom - height()));
    }
    private void restorePosition() {
        Rect area = safeArea(); SharedPreferences prefs = getSharedPreferences("floating_controls", MODE_PRIVATE);
        layout.width = Math.min(dp(248), area.width());
        layout.x = area.left + Math.round(Math.max(0, area.width() - layout.width) * prefs.getFloat("x", 1f));
        layout.y = area.top + Math.round(Math.max(0, area.height() - height()) * prefs.getFloat("y", .3f)); clamp();
    }
    private void rememberPosition() {
        Rect area = safeArea();
        getSharedPreferences("floating_controls", MODE_PRIVATE).edit()
                .putFloat("x", (layout.x - area.left) / (float) Math.max(1, area.width() - layout.width))
                .putFloat("y", (layout.y - area.top) / (float) Math.max(1, area.height() - height())).apply();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (attached) { restorePosition(); windows.updateViewLayout(view, layout); }
    }
    /** A queue barrier ensures accepted taps finish before closing or opening Detail. */
    private void finish(boolean open) {
        if (destroyed || closing) return;
        closing = true; render(); final int version = generation;
        worker.execute(() -> main.post(() -> {
            if (destroyed || version != generation) return;
            if (open && state != null) {
                try {
                    // Start while the overlay is still visible; MainActivity closes it after navigation.
                    startActivity(detailIntent(this, state.id, state.createdAt));
                    closing = false; render();
                } catch (RuntimeException error) { closing = false; render(); toast(R.string.floating_open_failed); }
            } else stopSelf();
        }));
    }
    static Intent detailIntent(Context context, long id, long created) {
        return new Intent(context, MainActivity.class).setAction(OPEN).putExtra(ID, id).putExtra(CREATED, created)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private Notification notification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_floating_notification).setOngoing(true).setOnlyAlertOnce(true)
                .setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(state == null ? getString(R.string.floating_controls) : state.title)
                .setContentText(state == null ? getString(R.string.floating_loading)
                        : getString(R.string.floating_notification, state.unit, FloatingChapterView.format(state.progress)))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.floating_notification_help)));
        if (state != null) {
            PendingIntent open = PendingIntent.getActivity(this, 2401, detailIntent(this, state.id, state.createdAt),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(open).addAction(0, getString(R.string.floating_open), open);
        }
        PendingIntent close = PendingIntent.getService(this, 2402, new Intent(this, FloatingChapterService.class).setAction(CLOSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.addAction(0, getString(R.string.floating_close), close).build();
    }
    private void fail(Throwable error, int message) {
        if (destroyed) return;
        if (error != null) android.util.Log.e("FloatingChapter", "Floating control failed", error);
        if (ready != null) { ready.send(0, Bundle.EMPTY); ready = null; }
        toast(message); stopSelf();
    }
    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); db.getInvalidationTracker().removeObserver(observer);
        if (attached) { attached = false; try { windows.removeViewImmediate(view); } catch (IllegalArgumentException ignored) { } }
        worker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
