package dev.direk.dkbank.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom head textures, e.g. from minecraft-heads.com. Accepts any of:
 * <ul>
 *     <li>the "Value" (a long base64 text starting with {@code eyJ0})</li>
 *     <li>a texture URL: {@code http://textures.minecraft.net/texture/<id>}</li>
 *     <li>just the texture id (64 letters and digits)</li>
 * </ul>
 * No internet lookups: the texture is put straight on the head, so it shows instantly.
 */
public final class Heads {

    private static final Map<String, PlayerProfile> CACHE = new ConcurrentHashMap<>();

    private Heads() {
    }

    /** @return a profile carrying the texture, or null if the text isn't a texture */
    public static @Nullable PlayerProfile profile(String texture) {
        String value = HeadTexture.toValue(texture.trim());
        if (value == null) return null;
        PlayerProfile cached = CACHE.get(value);
        if (cached != null) return cached; // setPlayerProfile copies it, so sharing is safe
        // The same texture always gets the same id, so players' games cache it once.
        UUID id = UUID.nameUUIDFromBytes(("dkbank-head:" + value).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, "dkbank_head");
        profile.setProperty(new ProfileProperty("textures", value));
        CACHE.put(value, profile);
        return profile;
    }
}
