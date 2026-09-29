package com.enthusia.donors.sandbox.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/** Sends already-rendered test chat lines through the proxy's BungeeCord-compatible channel. */
public final class ProxyChatBroadcast {
    public static final String CHANNEL = "BungeeCord";
    private ProxyChatBroadcast() { }

    public static byte[] encodeMessageRaw(Component message) throws IOException {
        String json = GsonComponentSerializer.gson().serialize(message);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("MessageRaw");
            out.writeUTF("ALL");
            out.writeUTF(json);
        }
        return bytes.toByteArray();
    }

    public static void send(JavaPlugin plugin, Player carrier, List<Component> lines) throws IOException {
        for (Component line : lines) {
            byte[] payload = encodeMessageRaw(line);
            if (payload.length > Short.MAX_VALUE) throw new IOException("A network chat line exceeds the proxy plugin-message limit.");
            carrier.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }
}
