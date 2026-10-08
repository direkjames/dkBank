package dev.direk.dkbank.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations as people write them: {@code 30s}, {@code 15m}, {@code 1h}, {@code 7d}, {@code 1d12h}.
 */
public final class TimeText {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final Pattern FULL = Pattern.compile("(\\s*\\d+\\s*[dhms]\\s*)+");

    private TimeText() {
    }

    /** @throws IllegalArgumentException if the text isn't a duration like 30s, 15m, 1h, 7d */
    public static Duration parse(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (s.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(s));
        if (!FULL.matcher(s).matches()) {
            throw new IllegalArgumentException("'" + text + "' isn't a duration. Use e.g. 30s, 15m, 1h or 7d");
        }
        Duration total = Duration.ZERO;
        Matcher m = PART.matcher(s);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total = switch (m.group(2)) {
                case "d" -> total.plusDays(n);
                case "h" -> total.plusHours(n);
                case "m" -> total.plusMinutes(n);
                default -> total.plusSeconds(n);
            };
        }
        return total;
    }

    /** {@code 3d 4h}, {@code 2h 5m}, {@code 45m}, {@code 30s}: the two largest units, no zeros. */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long[] values = {seconds / 86_400, seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < values.length && shown < 2; i++) {
            if (values[i] == 0) {
                if (shown > 0) break; // "1d 0h" reads worse than "1d"
                continue;
            }
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(values[i]).append(units[i]);
            shown++;
        }
        return sb.isEmpty() ? "0s" : sb.toString();
    }
}
