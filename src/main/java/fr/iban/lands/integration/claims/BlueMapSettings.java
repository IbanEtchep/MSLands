package fr.iban.lands.integration.claims;

import fr.iban.lands.enums.LandType;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public record BlueMapSettings(
        boolean enabled,
        String markerSetLabel,
        boolean defaultHidden,
        int lineWidth,
        Map<LandType, ClaimMarkerStyle> styles
) {
    public static final String PREFIX = "bluemap.";

    public BlueMapSettings {
        styles = Map.copyOf(styles);
    }

    public static BlueMapSettings load(
            Function<String, Object> valueAt,
            Consumer<String> warning
    ) {
        boolean enabled = booleanValue(valueAt, "enabled", true, warning);
        String label = stringValue(valueAt, "marker-set.label", "Territoires", warning);
        boolean hidden = booleanValue(valueAt, "marker-set.default-hidden", false, warning);
        int lineWidth = positiveInt(valueAt, "line-width", 2, warning);

        return new BlueMapSettings(enabled, label, hidden, lineWidth, Map.of(
                LandType.PLAYER, style(valueAt, warning, "player", "#3498DB"),
                LandType.GUILD, style(valueAt, warning, "guild", "#2ECC71"),
                LandType.SYSTEM, style(valueAt, warning, "system", "#E74C3C")
        ));
    }

    public ClaimMarkerStyle style(LandType landType) {
        return styles.get(landType);
    }

    private static ClaimMarkerStyle style(
            Function<String, Object> valueAt,
            Consumer<String> warning,
            String name,
            String color
    ) {
        RgbColor fallback = RgbColor.parse(color, null);
        return new ClaimMarkerStyle(
                colorValue(valueAt, "styles." + name + ".line-color", fallback, warning),
                colorValue(valueAt, "styles." + name + ".fill-color", fallback, warning),
                opacity(valueAt, "styles." + name + ".fill-opacity", 0.25F, warning)
        );
    }

    private static boolean booleanValue(
            Function<String, Object> valueAt,
            String path,
            boolean fallback,
            Consumer<String> warning
    ) {
        Object value = valueAt.apply(PREFIX + path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        warn(warning, path, fallback);
        return fallback;
    }

    private static String stringValue(
            Function<String, Object> valueAt,
            String path,
            String fallback,
            Consumer<String> warning
    ) {
        Object value = valueAt.apply(PREFIX + path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }

        warn(warning, path, fallback);
        return fallback;
    }

    private static int positiveInt(
            Function<String, Object> valueAt,
            String path,
            int fallback,
            Consumer<String> warning
    ) {
        Object value = valueAt.apply(PREFIX + path);
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue)
                    && numericValue > 0
                    && numericValue <= Integer.MAX_VALUE
                    && numericValue == Math.rint(numericValue)) {
                return (int) numericValue;
            }
        } else if (value == null) {
            return fallback;
        }

        warn(warning, path, fallback);
        return fallback;
    }

    private static RgbColor colorValue(
            Function<String, Object> valueAt,
            String path,
            RgbColor fallback,
            Consumer<String> warning
    ) {
        Object value = valueAt.apply(PREFIX + path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String colorValue) {
            RgbColor parsed = RgbColor.parse(colorValue, fallback);
            if (parsed != fallback) {
                return parsed;
            }
        }

        warn(warning, path, fallback);
        return fallback;
    }

    private static float opacity(
            Function<String, Object> valueAt,
            String path,
            float fallback,
            Consumer<String> warning
    ) {
        Object value = valueAt.apply(PREFIX + path);
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue)) {
                return (float) Math.clamp(numericValue, 0.0D, 1.0D);
            }
        } else if (value == null) {
            return fallback;
        }

        warn(warning, path, fallback);
        return fallback;
    }

    private static void warn(Consumer<String> warning, String path, Object fallback) {
        warning.accept("Invalid BlueMap setting '" + PREFIX + path + "'; using default '" + fallback + "'.");
    }
}
