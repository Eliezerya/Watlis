package com.watlis.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** App-owned, uncropped thumbnails. Originals are only used to create a thumbnail or open a preview. */
public final class CoverStore {
    public static final int MAX_EDGE = 768;
    private static volatile CoverStore instance;
    private final Context context;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Map<String, List<Consumer<File>>> pending = new HashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean working;

    private CoverStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "cover_thumbnails");
    }

    public static CoverStore get(Context context) {
        if (instance == null) synchronized (CoverStore.class) {
            if (instance == null) instance = new CoverStore(context);
        }
        return instance;
    }

    public File fileFor(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new File(directory, name + ".jpg");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    /** Calls back on main. Only one source is decoded/compressed at a time, including first-run imports. */
    public void ensure(String source, Consumer<File> ready) {
        if (source == null || source.trim().isEmpty()) { main.post(() -> ready.accept(null)); return; }
        // Existing thumbnails must not wait behind a slow/unavailable original in the creation queue.
        disk.execute(() -> {
            File file = fileFor(source);
            if (file.isFile() && file.length() > 0) main.post(() -> ready.accept(file));
            else enqueue(source, ready);
        });
    }

    private void enqueue(String source, Consumer<File> ready) {
        synchronized (this) {
            List<Consumer<File>> callbacks = pending.get(source);
            if (callbacks != null) { callbacks.add(ready); return; }
            callbacks = new ArrayList<>(); callbacks.add(ready); pending.put(source, callbacks);
            queue.add(source);
            if (working) return;
            working = true;
        }
        next();
    }

    private void next() {
        final String source;
        synchronized (this) {
            source = queue.poll();
            if (source == null) { working = false; return; }
        }
        disk.execute(() -> {
            File file = fileFor(source);
            if (file.isFile() && file.length() > 0) { finish(source, file); return; }
            main.post(() -> {
                java.util.concurrent.atomic.AtomicBoolean complete = new java.util.concurrent.atomic.AtomicBoolean();
                coil.request.Disposable[] loading = new coil.request.Disposable[1];
                Runnable timeout = () -> {
                    if (complete.compareAndSet(false, true)) {
                        if (loading[0] != null) loading[0].dispose();
                        finish(source, null);
                    }
                };
                coil.request.ImageRequest request = new coil.request.ImageRequest.Builder(context).data(source)
                        .size(MAX_EDGE, MAX_EDGE).scale(coil.size.Scale.FIT).precision(coil.size.Precision.EXACT)
                        .allowHardware(false).memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                        .target(new coil.target.Target() {
                            @Override public void onSuccess(Drawable drawable) {
                                if (!complete.compareAndSet(false, true)) return;
                                main.removeCallbacks(timeout);
                                disk.execute(() -> {
                                    File saved = null;
                                    try { saveDrawable(file, drawable); saved = file; }
                                    catch (Exception error) { android.util.Log.w("Watlis", "Unable to save cover thumbnail", error); }
                                    finish(source, saved);
                                });
                            }
                            @Override public void onError(Drawable error) {
                                if (complete.compareAndSet(false, true)) {
                                    main.removeCallbacks(timeout); finish(source, null);
                                }
                            }
                        }).build();
                loading[0] = coil.Coil.imageLoader(context).enqueue(request);
                main.postDelayed(timeout, 15000);
            });
        });
    }

    private synchronized void saveDrawable(File file, Drawable drawable) throws java.io.IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create cover storage");
        int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(width, height));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        AtomicFile output = new AtomicFile(file);
        FileOutputStream stream = null;
        android.graphics.Rect bounds = new android.graphics.Rect(drawable.getBounds());
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(16, 20, 18));
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight()); drawable.draw(canvas);
            stream = output.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 78, stream)) throw new java.io.IOException("Cannot encode cover");
            output.finishWrite(stream); stream = null;
        } finally {
            if (stream != null) output.failWrite(stream);
            drawable.setBounds(bounds); bitmap.recycle();
        }
    }

    private void finish(String source, File file) {
        final List<Consumer<File>> callbacks;
        synchronized (this) { callbacks = pending.remove(source); }
        main.post(() -> { if (callbacks != null) for (Consumer<File> callback : callbacks) callback.accept(file); });
        next();
    }

    /** Backup work runs on the repository worker, never while binding a list row. */
    public synchronized String exportThumbnail(String source) {
        if (source == null || source.isEmpty()) return null;
        File file = fileFor(source);
        if (!file.isFile()) return null;
        try {
            if (file.length() > 1024 * 1024) throw new java.io.IOException("Thumbnail exceeds backup limit");
            return android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(file.toPath()), android.util.Base64.NO_WRAP);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read saved cover for backup", error); }
    }

    public synchronized void importThumbnail(String source, String encoded) {
        if (source == null || source.isEmpty() || encoded == null) return;
        if (encoded.length() > 1400000) throw new IllegalArgumentException("Backup thumbnail is too large");
        byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_EDGE || bounds.outHeight > MAX_EDGE)
            throw new IllegalArgumentException("Invalid backup thumbnail");
        File file = fileFor(source);
        if (file.isFile()) return;
        AtomicFile output = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create cover storage");
            stream = output.startWrite(); stream.write(bytes); output.finishWrite(stream); stream = null;
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot restore saved cover", error); }
        finally { if (stream != null) output.failWrite(stream); }
    }
}
