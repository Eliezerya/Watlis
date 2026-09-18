package com.watlis.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import com.watlis.app.data.SyncDocument;
import com.watlis.app.data.SyncEngine;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Secure RFCOMM between two explicitly selected, already paired devices. No scanning/background service. */
@SuppressLint("MissingPermission") // Activity checks/grants BLUETOOTH_CONNECT before constructing a session.
final class BluetoothSyncSession implements AutoCloseable {
    private static final UUID SERVICE = UUID.fromString("918a29f4-e686-40e2-9815-767461746c69");
    interface Review {
        SyncDocument resolve(SyncDocument local, SyncDocument remote) throws Exception;
        boolean approve(SyncDocument local, SyncDocument remote, SyncDocument merged, boolean receiving) throws Exception;
        void status(String message);
    }
    private final BluetoothAdapter adapter;
    private final BluetoothDevice peer;
    private final SyncEngine engine;
    private final Review review;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeout;
    private volatile BluetoothSocket socket;
    private volatile BluetoothServerSocket server;
    private volatile boolean closed;
    BluetoothSyncSession(BluetoothAdapter adapter, BluetoothDevice peer, SyncEngine engine, Review review) {
        this.adapter = adapter; this.peer = peer; this.engine = engine; this.review = review;
    }
    void run(boolean waiting) throws Exception {
        try {
            deadline(60);
            if (waiting) {
                review.status("Waiting for " + peer.getName() + "…\nOn the other device, choose Connect & sync.");
                BluetoothServerSocket listening = adapter.listenUsingRfcommWithServiceRecord("Watlis sync", SERVICE);
                synchronized (this) { if (closed) { listening.close(); throw new IOException("Sync cancelled."); } server = listening; }
                BluetoothSocket accepted = listening.accept(60000);
                synchronized (this) { if (closed) { accepted.close(); throw new IOException("Sync cancelled."); } socket = accepted; }
                listening.close(); server = null;
                if (!peer.getAddress().equals(accepted.getRemoteDevice().getAddress()) || accepted.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED)
                    throw new IOException("A different device connected. Only your selected paired device is allowed.");
            } else {
                review.status("Connecting to " + peer.getName() + "…");
                BluetoothSocket connecting = peer.createRfcommSocketToServiceRecord(SERVICE);
                synchronized (this) { if (closed) { connecting.close(); throw new IOException("Sync cancelled."); } socket = connecting; }
                connecting.connect();
            }
            exchange(new SyncWire(socket.getInputStream(), socket.getOutputStream()), engine, review, waiting, () -> deadline(180), () -> deadline(600), () -> {
                if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Sync cancelled.");
            });
        } finally { close(); }
    }
    interface Check { void run() throws Exception; }
    /** Also exercised with duplex streams in tests; uses the same ordering as the real Bluetooth transport. */
    static void exchange(SyncWire wire, SyncEngine engine, Review review, boolean waiting,
                         Runnable transferDeadline, Runnable reviewDeadline, Check active) throws Exception {
        transferDeadline.run(); active.run(); review.status("Preparing collection and saved thumbnails…");
        SyncDocument local = engine.capture(), remote;
        if (waiting) { remote = SyncDocument.parse(wire.receive("snapshot")); wire.send("snapshot", local.json()); }
        else { wire.send("snapshot", local.json()); remote = SyncDocument.parse(wire.receive("snapshot")); }
        reviewDeadline.run();
        if (!waiting) {
            SyncDocument merged = review.resolve(local, remote); active.run();
            SyncEngine.validateProposal(local, remote, merged);
            if (!review.approve(local, remote, merged, false)) throw new IOException("Sync cancelled before applying changes.");
            active.run(); engine.assertCurrent(local);
            review.status("Waiting for approval on the other device…");
            wire.send("proposal", merged.json());
            JSONObject ready = wire.receive("ready");
            if (!merged.fingerprint().equals(ready.getString("fingerprint"))) throw new IOException("The devices reviewed different data.");
            transferDeadline.run(); active.run();
            review.status("Applying changes on this device…"); engine.apply(local, merged);
            wire.send("commit", new JSONObject().put("fingerprint", merged.fingerprint()));
            wire.receive("done");
        } else {
            review.status("Connected. Review conflicts on the device that started sync…");
            SyncDocument merged = SyncDocument.parse(wire.receive("proposal"));
            SyncEngine.validateProposal(local, remote, merged);
            if (!review.approve(local, remote, merged, true)) throw new IOException("Sync cancelled before applying changes.");
            active.run(); engine.assertCurrent(local);
            wire.send("ready", new JSONObject().put("fingerprint", merged.fingerprint()));
            JSONObject commit = wire.receive("commit");
            if (!merged.fingerprint().equals(commit.getString("fingerprint"))) throw new IOException("The sync confirmation did not match.");
            transferDeadline.run(); active.run(); review.status("Applying changes on this device…"); engine.apply(local, merged);
            wire.send("done", new JSONObject());
        }
        review.status("Sync complete. Both devices have the reviewed collection.");
    }
    private synchronized void deadline(int seconds) {
        if (closed) return;
        if (timeout != null) timeout.cancel(false);
        timeout = timer.schedule(this::close, seconds, TimeUnit.SECONDS);
    }
    @Override public synchronized void close() {
        closed = true; if (timeout != null) timeout.cancel(false); timer.shutdownNow();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }
}
