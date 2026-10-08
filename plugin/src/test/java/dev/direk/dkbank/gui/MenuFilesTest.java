package dev.direk.dkbank.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every built-in menu file loads without a single warning. */
class MenuFilesTest {

    @Test
    void builtInMenusLoadCleanly() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        for (String name : MenuManager.FILES) {
            String path = "menus/" + name + ".yml";
            try (InputStream in = MenuFilesTest.class.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, path + " is missing");
                YamlConfiguration file = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                MenuLayout layout = new MenuLayout(name, file, log);
                assertFalse(layout.items().isEmpty(), path + " has no items");
                assertTrue(layout.fill() != null, path + " has no fill");
            }
        }
        assertTrue(warnings.isEmpty(), "warnings: " + warnings);
    }
}
