package com.watlis.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.watlis.app.data.SyncDocument;
import com.watlis.app.data.SyncEngine;
import com.watlis.app.data.WatlisDatabase;
import com.watlis.app.data.WatlisRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Foreground-only sync. Leaving this screen cancels the connection, not already committed data. */
public final class BluetoothSyncActivity extends AppCompatActivity implements BluetoothSyncSession.Review {
    private static final int BG = Color.rgb(16, 20, 18), CARD = Color.rgb(27, 33, 29), BORDER = Color.rgb(48, 57, 50),
            TEXT = Color.rgb(243, 246, 241), MUTED = Color.rgb(166, 176, 168), ACCENT = Color.rgb(199, 237, 154);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SyncEngine engine;
    private BluetoothAdapter adapter;
    private LinearLayout page, devices;
    private TextView status, cancel, refresh;
    private volatile BluetoothSyncSession session;
    private volatile CompletableFuture<Integer> decision;
    private Future<?> task;
    private boolean busy;
    private androidx.appcompat.app.AlertDialog activeDialog;
    private ActivityResultLauncher<String> permission;
    private ActivityResultLauncher<Intent> enable;
    private ActivityResultLauncher<String> recoveryExport;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        WatlisDatabase db = WatlisDatabase.get(this);
        WatlisRepository repo = new WatlisRepository(db, CoverStore.get(this));
        engine = new SyncEngine(db, repo, getNoBackupFilesDir(), getFilesDir());
        BluetoothManager manager = getSystemService(BluetoothManager.class); adapter = manager == null ? null : manager.getAdapter();
        permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) refreshDevices(); else status.setText(R.string.sync_permission_denied);
        });
        enable = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> refreshDevices());
        recoveryExport = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            if (uri == null) return;
            worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null || !engine.recoveryFile.isFile()) throw new IOException("No pre-sync backup is available yet.");
                    Files.copy(engine.recoveryFile.toPath(), out); status("Pre-sync backup exported. Use Import data to restore it if needed.");
                } catch (Exception e) { status("Could not export recovery backup: " + message(e)); }
            });
        });
        page = column(); page.setPadding(dp(20), dp(16), dp(20), dp(24)); page.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); scroll.setBackgroundColor(BG);
        // Insets belong outside the scroll viewport, otherwise ScrollView can leave its last action clipped.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this); frame.setBackgroundColor(BG);
        frame.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1)); setContentView(frame);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        add(page, button("Back to settings", () -> { if (busy) confirmLeave(); else finish(); }), 12);
        add(page, text("Bluetooth sync", 26, TEXT, true), 8);
        add(page, text("Your collection, on both devices", 15, ACCENT, false), 20);
        LinearLayout instructions = card();
        add(instructions, text("Pair once. Review before applying.", 17, TEXT, true), 10);
        add(instructions, text("1. Pair your phone and tablet in Android Bluetooth settings.\n2. Open this screen in Watlis on both devices.\n3. Select each other. Tap Wait on one device, then Connect & sync on the other.", 14, MUTED, false), 12);
        add(instructions, text("Competing edits and possible duplicates need your choice. Both devices approve the result. Keep this screen open until sync finishes.", 14, MUTED, false), 0);
        add(page, instructions, 16);
        add(page, button("Open Bluetooth settings", () -> {
            if (busy) return;
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (android.content.ActivityNotFoundException e) { status.setText(R.string.sync_settings_unavailable); }
        }), 10);
        refresh = button("Show paired devices", this::requestDevices); add(page, refresh, 16);
        status = text(state == null ? "Ready when you are. No background scanning or internet upload." : "Previous connection closed. Reconnect safely to continue syncing.", 14, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, status, 16);
        cancel = button("Cancel sync", () -> cancelSession("Sync stopped. If either device already applied changes, reconnect to finish safely."));
        cancel.setVisibility(View.GONE); add(page, cancel, 16);
        devices = column(); add(page, devices, 12);
        add(page, text("What travels", 17, TEXT, true), 8);
        add(page, text("Titles, decimal progress and its history, notes, characters, genres, media types and saved thumbnails. Original local photos and importer settings stay on their device. Existing high-quality originals are kept.", 14, MUTED, false), 16);
        add(page, text("A pre-sync recovery backup is saved privately before collection changes. Export it here if you need the previous version. Sync does not combine two competing note/history versions; you choose one complete title version.", 14, MUTED, false), 12);
        add(page, button("Export pre-sync recovery backup", () -> {
            if (busy) { status.setText(R.string.sync_export_busy); return; }
            if (!engine.recoveryFile.isFile()) { status.setText(R.string.sync_no_recovery); return; }
            recoveryExport.launch("watlis-before-sync.json");
        }), 8);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (busy) confirmLeave(); else finish(); }
        });
    }
    private void requestDevices() {
        if (busy) return;
        if (adapter == null) { status.setText(R.string.sync_unsupported); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT);
        else refreshDevices();
    }
    @SuppressLint("MissingPermission")
    private void refreshDevices() {
        if (busy || adapter == null || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        devices.removeAllViews();
        try {
            if (!adapter.isEnabled()) {
                status.setText(R.string.sync_enable);
                add(devices, button("Turn on Bluetooth", () -> enable.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))), 12); return;
            }
            List<BluetoothDevice> bonded = new ArrayList<>(adapter.getBondedDevices());
            bonded.sort(Comparator.comparing(d -> d.getName() == null ? d.getAddress() : d.getName()));
            status.setText(bonded.isEmpty() ? "No paired devices. Pair the other device in Android settings, then tap Show paired devices." : "Choose the phone or tablet running Watlis.");
            for (BluetoothDevice device : bonded) {
                String name = device.getName() == null ? "Paired device" : device.getName();
                LinearLayout card = card(); add(card, text(name, 17, TEXT, true), 4);
                add(card, text(device.getAddress(), 12, MUTED, false), 14);
                add(card, button("Wait for " + name, () -> start(device, true)), 10);
                add(card, button("Connect & sync with " + name, () -> start(device, false)), 0); add(devices, card, 14);
            }
        } catch (SecurityException e) { status.setText(R.string.sync_permission_changed); }
    }
    private void start(BluetoothDevice peer, boolean waiting) {
        if (busy) return;
        busy = true; refresh.setEnabled(false); devices.setVisibility(View.GONE); cancel.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothSyncSession current = new BluetoothSyncSession(adapter, peer, engine, this); session = current;
        task = worker.submit(() -> {
            try { current.run(waiting); }
            catch (Exception error) {
                if (session == current) status("Sync did not finish: " + message(error) + "\n\nNo partial records are saved. If one device already applied the result, reconnect to finish safely.");
            } finally {
                current.close(); runOnUiThread(() -> { if (session == current) { session = null; idle(); } });
            }
        });
    }
    @Override public SyncDocument resolve(SyncDocument local, SyncDocument remote) throws Exception {
        SyncDocument.Plan plan = new SyncDocument.Plan(local, remote, engine.actor());
        while (!plan.conflicts.isEmpty()) {
            SyncDocument.Conflict conflict = plan.conflicts.get(0);
            int choice = ask(conflict.title(), conflict.here.label + "\n\nChoose the complete version to keep on BOTH devices. Notes, characters, progress and matching history travel together. The other version remains in that device's pre-sync recovery backup.\n\nTHIS DEVICE\n" + describe(conflict.here) + "\n\nOTHER DEVICE\n" + describe(conflict.there),
                    "Keep this device", "Keep other device", null);
            if (choice < 0) throw new IOException("Conflict review cancelled."); plan.choose(conflict, choice);
        }
        Set<String> separate = new HashSet<>(); SyncDocument.Conflict duplicate;
        while ((duplicate = plan.nextDuplicate(separate)) != null) {
            int choice = ask("Possible duplicate: " + duplicate.here.label,
                    "These titles were added independently. Same title and type do not prove they are the same item.\n\nMerge keeps ONE complete version and one identity. Keep both is for genuinely different editions.\n\nTHIS DEVICE\n" + describe(duplicate.here) + "\n\nOTHER DEVICE\n" + describe(duplicate.there),
                    "Merge · use this device", "Merge · use other device", "Keep both titles");
            if (choice < 0) throw new IOException("Duplicate review cancelled.");
            if (choice == 2) { separate.add(duplicate.here.key + duplicate.there.key); separate.add(duplicate.there.key + duplicate.here.key); }
            plan.choose(duplicate, choice);
        }
        SyncDocument merged = plan.finish();
        if (!plan.notices.isEmpty() && ask("Keep required categories", String.join("\n", plan.notices), "Continue", null, null) < 0)
            throw new IOException("Sync review cancelled.");
        return merged;
    }
    @Override public boolean approve(SyncDocument local, SyncDocument remote, SyncDocument merged, boolean receiving) throws Exception {
        int additions = 0, updates = 0, deletions = 0;
        List<SyncDocument.Record> changes = new ArrayList<>();
        for (SyncDocument.Record r : merged.records.values()) {
            SyncDocument.Record before = local.records.get(r.key);
            if (before == null ? r.deleted() : before.hash().equals(r.hash())) continue;
            if (r.key.startsWith("m:")) { if (r.deleted()) deletions++; else if (before == null || before.deleted()) additions++; else updates++; }
            changes.add(r);
        }
        String summary = additions + " titles added · " + updates + " updated · " + deletions + " deleted on this device.\n\n"
                + (receiving ? "The other device reviewed the conflicts. Check its choices below before allowing changes." : "These choices will be sent to the other device for approval.")
                + "\n\nA private recovery backup protects this device's previous collection. Original photo files are not deleted.";
        if (changes.isEmpty()) summary += "\n\nNo collection changes on this device. Sync identities will be reconciled.";
        int pages = Math.max(1, (changes.size() + 11) / 12), page = 0;
        while (page < pages) {
            StringBuilder details = new StringBuilder();
            for (int i = page * 12; i < Math.min(changes.size(), (page + 1) * 12); i++) {
                SyncDocument.Record r = changes.get(i), before = local.records.get(r.key);
                details.append("\n\n").append(r.key.startsWith("m:") ? "TITLE: " : "CATEGORY: ").append(r.label)
                        .append("\nBefore on this device\n").append(before == null ? "Not in this collection" : describe(before))
                        .append("\nAfter sync\n").append(describe(r));
            }
            int choice = ask(pages == 1 ? "Review sync" : "Review sync · " + (page + 1) + " / " + pages, summary + details,
                    page + 1 < pages ? "Next changes" : receiving ? "Approve & sync" : "Send for approval", page > 0 ? "Previous changes" : null, null);
            if (choice < 0) return false;
            page += choice == 1 ? -1 : 1;
        }
        return true;
    }
    private int ask(String title, String message, String first, String second, String third) throws Exception {
        CompletableFuture<Integer> answer = new CompletableFuture<>(); decision = answer;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || !busy || decision != answer || answer.isDone()) { answer.complete(-1); return; }
            LinearLayout body = column(); body.setPadding(dp(20), dp(8), dp(20), dp(20));
            TextView explanation = text(message, 14, TEXT, false); explanation.setTextIsSelectable(true); add(body, explanation, 20);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this).setTitle(title);
            ScrollView scroll = new ScrollView(this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int limit = Math.min(dp(480), (int) (getResources().getDisplayMetrics().heightPixels * .60f));
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.min(limit, MeasureSpec.getSize(heightSpec)), MeasureSpec.AT_MOST));
                }
            };
            scroll.addView(body); builder.setView(scroll).setNegativeButton("Cancel", null);
            activeDialog = builder.create(); androidx.appcompat.app.AlertDialog dialog = activeDialog;
            int[] selection = {-1};
            String[] labels = { first, second, third };
            for (int i = 0; i < labels.length; i++) if (labels[i] != null) {
                final int choice = i; add(body, button(labels[i], () -> { selection[0] = choice; dialog.dismiss(); }), 10);
            }
            dialog.setOnCancelListener(d -> answer.complete(-1));
            dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; answer.complete(selection[0]); });
            dialog.show(); if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(background(CARD));
        });
        try { return answer.get(10, TimeUnit.MINUTES); }
        finally { if (decision == answer) decision = null; }
    }
    static String describe(SyncDocument.Record r) {
        if (r.deleted()) return "Deleted";
        JSONObject value = r.data;
        if (!r.key.startsWith("m:")) return value.optString("name") + (r.key.startsWith("g:") ? " · " + value.optString("color") : value.optBoolean("usesEpisodes") ? " · Episodes" : " · Chapters");
        JSONObject p = value.optJSONObject("progress");
        StringBuilder text = new StringBuilder(value.optString("title")).append("\nType: ").append(value.optString("type").replaceFirst("^t:", ""));
        if (p != null) text.append("\nProgress: ").append(p.opt("currentProgress")).append(" · ").append(p.optString("trackingStatus").replace('_', ' '))
                .append("\nRating: ").append(p.has("rating") ? p.opt("rating") : "Unrated").append("\nNotes: ").append(p.optString("notes", "—"));
        text.append("\nRelease: ").append(value.optString("releaseStatus")).append("\nFavorite: ").append(value.optBoolean("isFavorite") ? "Yes" : "No")
                .append("\nGenres: ").append(value.optJSONArray("genreIds"))
                .append("\nCover: ").append(value.has("coverImage") ? "Included reference" : "None")
                .append(" · position ").append(value.opt("coverPositionX")).append(", ").append(value.opt("coverPositionY")).append(" · zoom ").append(value.opt("coverZoom"));
        JSONObject story = value.optJSONObject("storyMemory");
        if (story != null) {
            String[] keys = { "mainCharacterName", "storySummary", "lastStoryPoint", "importantNotes" };
            String[] labels = { "Main character", "Story reminder", "Where I left off", "Important notes" };
            for (int i = 0; i < keys.length; i++) if (story.has(keys[i])) text.append('\n').append(labels[i]).append(": ").append(story.optString(keys[i]));
        }
        JSONArray chars = value.optJSONArray("characters");
        if (chars != null) for (int i = 0; i < chars.length(); i++) {
            JSONObject c = chars.optJSONObject(i); if (c != null) text.append("\nCharacter: ").append(c.optString("name")).append(" · ").append(c.optString("role"))
                    .append("\n").append(c.optString("description")).append(c.has("image") ? " [image]" : "");
        }
        JSONArray history = value.optJSONArray("progressHistory");
        text.append("\nHistory entries: ").append(history == null ? 0 : history.length());
        if (history != null && history.length() > 0) {
            JSONObject last = history.optJSONObject(history.length() - 1);
            text.append("\nLatest: ").append(last.optString("kind")).append(' ').append(last.opt("from")).append(" → ").append(last.opt("to"));
        }
        return text.toString();
    }
    @Override public void status(String message) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); }); }
    private void confirmLeave() {
        new MaterialAlertDialogBuilder(this).setTitle("Stop Bluetooth sync?")
                .setMessage("Changes already committed on a device stay saved. You can reconnect safely to finish.")
                .setNegativeButton("Keep syncing", null).setPositiveButton("Stop & leave", (d,w) -> { cancelSession("Sync stopped."); finish(); }).show();
    }
    private void cancelSession(String message) {
        BluetoothSyncSession old = session; session = null; if (old != null) old.close();
        CompletableFuture<Integer> pending = decision; if (pending != null) pending.complete(-1);
        if (task != null) task.cancel(true);
        if (activeDialog != null) activeDialog.dismiss(); idle(); status.setText(message);
    }
    private void idle() {
        busy = false; refresh.setEnabled(true); cancel.setVisibility(View.GONE); devices.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onStop() {
        super.onStop(); if (busy) cancelSession("Connection stopped because this screen was left. Reconnect safely to continue.");
    }
    @Override protected void onDestroy() {
        if (session != null) cancelSession("Connection stopped."); worker.shutdownNow(); super.onDestroy();
    }
    private static String message(Throwable error) { return error.getMessage() == null ? "Connection closed or timed out. Check Bluetooth on both devices." : error.getMessage(); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card() { LinearLayout view = column(); view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackground(background(CARD)); return view; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private TextView button(String label, Runnable action) {
        TextView view = text(label, 14, ACCENT, true); view.setGravity(Gravity.CENTER); view.setMinHeight(dp(48));
        view.setPadding(dp(14), dp(12), dp(14), dp(12)); view.setBackground(background(CARD)); view.setFocusable(true);
        view.setOnClickListener(v -> action.run()); view.setContentDescription(label); return view;
    }
    private GradientDrawable background(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16)); d.setStroke(dp(1), BORDER); return d; }
    private void add(LinearLayout parent, View view, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(bottom); parent.addView(view, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
