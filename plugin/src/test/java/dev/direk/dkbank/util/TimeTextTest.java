package dev.direk.dkbank.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeTextTest {

    @Test
    void parses() {
        assertEquals(Duration.ofSeconds(30), TimeText.parse("30s"));
        assertEquals(Duration.ofMinutes(15), TimeText.parse("15m"));
        assertEquals(Duration.ofHours(1), TimeText.parse("1h"));
        assertEquals(Duration.ofDays(7), TimeText.parse("7D"));
        assertEquals(Duration.ofHours(36), TimeText.parse("1d 12h"));
        assertEquals(Duration.ofSeconds(90), TimeText.parse("90"));
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> TimeText.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> TimeText.parse("5x"));
        assertThrows(IllegalArgumentException.class, () -> TimeText.parse(""));
    }

    @Test
    void formatsTheTwoLargestUnits() {
        assertEquals("3d 4h", TimeText.format(Duration.ofHours(76).plusMinutes(30)));
        assertEquals("2h 5m", TimeText.format(Duration.ofMinutes(125)));
        assertEquals("45m", TimeText.format(Duration.ofMinutes(45)));
        assertEquals("1d", TimeText.format(Duration.ofDays(1).plusSeconds(20)));
        assertEquals("30s", TimeText.format(Duration.ofSeconds(30)));
        assertEquals("0s", TimeText.format(Duration.ZERO));
    }
}
