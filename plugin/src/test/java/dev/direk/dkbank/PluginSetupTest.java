package dev.direk.dkbank;

import dev.direk.dkbank.api.DkBankAPI;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the build setup that every release depends on.
 */
class PluginSetupTest {

    /** Java 21 class files. Anything newer won't load on 1.21.x servers. */
    private static final int JAVA_21 = 65;

    private static YamlConfiguration pluginYml() throws IOException {
        try (InputStream in = PluginSetupTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml is missing");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void pluginYmlIsFilledIn() throws IOException {
        YamlConfiguration yml = pluginYml();
        assertEquals("dkBank", yml.getString("name"));
        assertEquals(System.getProperty("dkbank.version"), yml.getString("version"), "version wasn't filled in by the build");
        assertEquals("1.21.4", yml.getString("api-version"), "api-version must stay at the oldest supported version");
    }

    @Test
    void mainClassIsThePlugin() throws Exception {
        Class<?> main = Class.forName(pluginYml().getString("main"));
        assertTrue(JavaPlugin.class.isAssignableFrom(main), "main class must extend JavaPlugin");
        assertTrue(DkBankAPI.class.isAssignableFrom(main), "main class must provide the API");
    }

    @Test
    void compiledForJava21() throws IOException {
        for (Class<?> type : new Class<?>[]{DkBankPlugin.class, DkBankAPI.class}) {
            String path = type.getName().replace('.', '/') + ".class";
            try (DataInputStream in = new DataInputStream(type.getClassLoader().getResourceAsStream(path))) {
                in.readInt();              // magic number
                in.readUnsignedShort();    // minor version
                int major = in.readUnsignedShort();
                assertEquals(JAVA_21, major, type.getSimpleName() + " must be compiled for Java 21 to run on 1.21.x servers");
            }
        }
    }
}
