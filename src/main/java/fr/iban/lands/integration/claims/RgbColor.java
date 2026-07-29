package fr.iban.lands.integration.claims;

public record RgbColor(int red, int green, int blue) {

    public static RgbColor parse(String value, RgbColor fallback) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
            return fallback;
        }

        return new RgbColor(
                Integer.parseInt(value.substring(1, 3), 16),
                Integer.parseInt(value.substring(3, 5), 16),
                Integer.parseInt(value.substring(5, 7), 16)
        );
    }
}
