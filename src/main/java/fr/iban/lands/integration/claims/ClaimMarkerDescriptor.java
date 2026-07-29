package fr.iban.lands.integration.claims;

public record ClaimMarkerDescriptor(
        String id,
        String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String label,
        String detail,
        ClaimMarkerStyle style
) {
}
