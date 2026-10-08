package dev.direk.dkbank.gui;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Reads head textures in the forms people copy from head websites. See {@link Heads}. */
public final class HeadTexture {

    private static final Pattern TEXTURE_ID = Pattern.compile("[0-9a-fA-F]{40,80}");
    private static final String TEXTURE_URL = "http://textures.minecraft.net/texture/";

    private HeadTexture() {
    }

    /**
     * Turns a base64 "Value", a textures.minecraft.net URL or a bare texture id into the base64 value a
     * head needs. @return null if it's none of those
     */
    public static @Nullable String toValue(String texture) {
        if (texture.isEmpty()) return null;
        String id = null;
        if (texture.startsWith("http://") || texture.startsWith("https://")) {
            id = texture.substring(texture.lastIndexOf('/') + 1);
        } else if (TEXTURE_ID.matcher(texture).matches()) {
            id = texture;
        }
        if (id != null) {
            if (!TEXTURE_ID.matcher(id).matches()) return null;
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + TEXTURE_URL + id + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        try {
            String json = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            return json.contains("\"textures\"") ? texture : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
