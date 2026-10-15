package com.enthusia.donors.entitlement;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import java.net.*;
import static org.mockito.Mockito.*;

class OptionalLuckPermsTest {
    @Test void disabledAdapterStartsAndStopsWithoutLuckPermsClasses() throws Exception {
        var plugin = mock(JavaPlugin.class); when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        URL classes = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().resolve("../classes/").toURL();
        try (var loader = new URLClassLoader(new URL[]{classes}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.luckperms.")) throw new ClassNotFoundException("LuckPerms absent");
                if (name.startsWith("com.enthusia.donors.")) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) result = findClass(name);
                    if (resolve) resolveClass(result); return result;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> type = Class.forName("com.enthusia.donors.infrastructure.entitlement.ReconciliationLifecycle", true, loader);
            Object lifecycle = type.getConstructor(JavaPlugin.class).newInstance(plugin);
            type.getMethod("start").invoke(lifecycle); type.getMethod("close").invoke(lifecycle);
        }
    }
}
