package com.watlis.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded, checksummed frames; an interrupted frame is never passed to the merge engine. */
public final class SyncWire {
    static final int MAX_PLAIN = 16 * 1024 * 1024, MAX_COMPRESSED = 8 * 1024 * 1024;
    private static final int MAGIC = 0x57415431; // WAT1
    private final DataInputStream input;
    private final DataOutputStream output;
    public SyncWire(InputStream input, OutputStream output) { this.input = new DataInputStream(input); this.output = new DataOutputStream(output); }
    public void send(String type, JSONObject value) throws IOException, JSONException {
        byte[] plain = new JSONObject().put("type", type).put("value", value).toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_PLAIN) throw new IOException("This collection exceeds the 16 MB Bluetooth sync limit. Use a JSON backup to transfer it.");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(plain); }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_COMPRESSED) throw new IOException("The compressed sync data exceeds 8 MB. Use a JSON backup to transfer it.");
        output.writeInt(MAGIC); output.writeInt(bytes.length); output.write(hash(bytes)); output.write(bytes); output.flush();
    }
    public JSONObject receive(String expected) throws IOException, JSONException {
        if (input.readInt() != MAGIC) throw new IOException("Not a compatible Watlis sync connection.");
        int size = input.readInt();
        if (size < 1 || size > MAX_COMPRESSED) throw new IOException("Invalid sync frame size.");
        byte[] checksum = new byte[32]; input.readFully(checksum);
        byte[] bytes = new byte[size]; input.readFully(bytes);
        if (!MessageDigest.isEqual(checksum, hash(bytes))) throw new IOException("Transfer checksum failed. Retry sync.");
        ByteArrayOutputStream plain = new ByteArrayOutputStream(); byte[] chunk = new byte[8192];
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            int count;
            while ((count = gzip.read(chunk)) != -1) {
                if (plain.size() > MAX_PLAIN - count) throw new IOException("Sync data exceeds the safe size limit.");
                plain.write(chunk, 0, count);
            }
        }
        JSONObject frame = new JSONObject(plain.toString(StandardCharsets.UTF_8.name()));
        if (!expected.equals(frame.getString("type"))) throw new IOException("Unexpected sync step. Reconnect both devices.");
        return frame.getJSONObject("value");
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
