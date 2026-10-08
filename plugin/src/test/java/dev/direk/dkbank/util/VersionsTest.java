package dev.direk.dkbank.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void comparesVersions() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"));
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"));
        assertTrue(Versions.isNewer("2.0", "1.9.9"));
        assertTrue(Versions.isNewer("v1.1.0", "1.0.0"));
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"));
        assertFalse(Versions.isNewer("1.0", "1.0.0"));
        assertFalse(Versions.isNewer("0.9.0", "1.0.0"));
        assertFalse(Versions.isNewer("1.0.0-beta", "1.0.0"));
        assertFalse(Versions.isNewer("not a version", "1.0.0"));
    }
}
