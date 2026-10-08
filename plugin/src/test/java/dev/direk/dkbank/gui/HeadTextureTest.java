package dev.direk.dkbank.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadTextureTest {

    private static final String ID = "4cb3acdc11ca747bf710e59f4c8e9b3d949fdd364c6869831ca878f0763d1787";

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    @Test
    void acceptsTheValueFromHeadWebsites() {
        String value = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                + ID + "\"}}}").getBytes(StandardCharsets.UTF_8));
        assertEquals(value, HeadTexture.toValue(value));
    }

    @Test
    void acceptsAUrlOrJustTheId() {
        assertTrue(decode(HeadTexture.toValue("http://textures.minecraft.net/texture/" + ID)).contains(ID));
        assertTrue(decode(HeadTexture.toValue("https://textures.minecraft.net/texture/" + ID)).contains(ID));
        assertTrue(decode(HeadTexture.toValue(ID)).contains("\"SKIN\""));
    }

    @Test
    void rejectsAnythingElse() {
        assertEquals(null, HeadTexture.toValue(""));
        assertEquals(null, HeadTexture.toValue("not a texture"));
        assertEquals(null, HeadTexture.toValue("https://example.com/page"));
        assertEquals(null, HeadTexture.toValue(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))));
    }
}
