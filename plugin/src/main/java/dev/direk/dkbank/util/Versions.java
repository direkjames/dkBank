package dev.direk.dkbank.util;

/** Compares version numbers. */
public final class Versions {

    private Versions() {
    }

    /** {@code 1.2.0} is newer than {@code 1.1.9}; anything after a {@code -} (like {@code -beta}) is ignored. */
    public static boolean isNewer(String latest, String current) {
        int[] a = parts(latest);
        int[] b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String core = version.trim().replaceFirst("^[vV]", "").split("[-+ ]", 2)[0];
        String[] pieces = core.split("\\.");
        int[] numbers = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                numbers[i] = Integer.parseInt(pieces[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
