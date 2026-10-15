package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.*;
import com.enthusia.donors.entitlement.Reconciliation;
import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Small restart-safe observation file and fsynced mutation journal; no payment PII. */
public final class ReconciliationFiles implements Reconciliation.Store {
    private final Path directory;
    private final boolean enforce;
    public ReconciliationFiles(Path directory, boolean enforce) throws Exception {
        this.directory = directory; this.enforce = enforce; Files.createDirectories(directory);
    }
    public Map<UUID, Pending> load() throws Exception {
        Path file = directory.resolve("pending.json");
        if (!Files.exists(file)) return new HashMap<>();
        var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (root.get("version").getAsInt() != 1) throw new IllegalStateException("Unknown reconciliation state version");
        if (root.get("enforce").getAsBoolean() != enforce) return new HashMap<>();
        var result = new HashMap<UUID, Pending>();
        for (var item : root.getAsJsonObject("pending").entrySet()) {
            var value = item.getValue().getAsJsonObject();
            result.put(UUID.fromString(item.getKey()), new Pending(value.get("fingerprint").getAsString(),
                    Instant.parse(value.get("first").getAsString()), Instant.parse(value.get("last").getAsString()), value.get("checks").getAsInt()));
        }
        return result;
    }
    public void save(Map<UUID, Pending> pending) throws Exception {
        var root = new JsonObject(); root.addProperty("version", 1); root.addProperty("enforce", enforce);
        var entries = new JsonObject();
        for (var item : pending.entrySet()) {
            var value = new JsonObject(); value.addProperty("fingerprint", item.getValue().fingerprint());
            value.addProperty("first", item.getValue().first().toString()); value.addProperty("last", item.getValue().last().toString());
            value.addProperty("checks", item.getValue().checks()); entries.add(item.getKey().toString(), value);
        }
        root.add("pending", entries);
        Path temp = directory.resolve("pending.json.tmp");
        writeForced(temp, root.toString(), false);
        Files.move(temp, directory.resolve("pending.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public void audit(Candidate candidate, Instant now, String outcome) throws Exception {
        var entry = new JsonObject(); entry.addProperty("at", now.toString()); entry.addProperty("uuid", candidate.uuid().toString());
        entry.addProperty("outcome", outcome); entry.addProperty("enforce", enforce);
        entry.addProperty("previous_primary_group", candidate.primaryGroup());
        writeForced(directory.resolve("audit.jsonl"), entry + "\n", true);
    }
    private static void writeForced(Path path, String content, boolean append) throws Exception {
        try (var channel = FileChannel.open(path, CREATE, WRITE, append ? APPEND : TRUNCATE_EXISTING)) {
            var bytes = ByteBuffer.wrap(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
