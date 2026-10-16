package com.enthusia.donors.infrastructure.heads;

import org.junit.jupiter.api.Test;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.net.URLClassLoader;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class HeadEmojiOptionalTest {
    @Test void disabledFeatureStartsAndStopsWithoutLuckPermsInstalled()throws Exception {
        var plugin=mock(JavaPlugin.class);when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var url=HeadEmojiLifecycle.class.getProtectionDomain().getCodeSource().getLocation();
        try(var loader=new URLClassLoader(new java.net.URL[]{url},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.startsWith("net.luckperms."))throw new ClassNotFoundException("LuckPerms absent");
                if(name.startsWith("com.enthusia.donors.infrastructure.heads.")){
                    synchronized(getClassLoadingLock(name)){
                        var loaded=findLoadedClass(name);if(loaded==null)loaded=findClass(name);if(resolve)resolveClass(loaded);return loaded;
                    }
                }
                return super.loadClass(name,resolve);
            }
        }){
            var type=loader.loadClass(HeadEmojiLifecycle.class.getName());
            Object lifecycle=type.getConstructor(JavaPlugin.class,Supplier.class).newInstance(plugin,(Supplier<Object>)()->null);
            assertDoesNotThrow(()->type.getMethod("start").invoke(lifecycle));
            assertDoesNotThrow(()->type.getMethod("close").invoke(lifecycle));
            verify(plugin,never()).getServer();
        }
    }
}
