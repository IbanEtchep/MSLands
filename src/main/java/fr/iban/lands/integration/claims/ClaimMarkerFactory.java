package fr.iban.lands.integration.claims;

import fr.iban.lands.enums.LandType;
import fr.iban.lands.model.SChunk;
import fr.iban.lands.model.land.Land;

import java.util.Objects;
import java.util.Optional;

public final class ClaimMarkerFactory {

    private final BlueMapSettings settings;
    private final ClaimOwnerLabelResolver ownerLabelResolver;

    public ClaimMarkerFactory(BlueMapSettings settings, ClaimOwnerLabelResolver ownerLabelResolver) {
        this.settings = Objects.requireNonNull(settings);
        this.ownerLabelResolver = Objects.requireNonNull(ownerLabelResolver);
    }

    public Optional<ClaimMarkerDescriptor> create(SChunk chunk, Land land) {
        ClaimMarkerStyle style = settings.style(land.getType());
        if (style == null) {
            return Optional.empty();
        }

        int minX = Math.multiplyExact(chunk.getX(), 16);
        int minZ = Math.multiplyExact(chunk.getZ(), 16);
        return Optional.of(new ClaimMarkerDescriptor(
                markerId(chunk),
                chunk.getWorld(),
                minX,
                minZ,
                Math.addExact(minX, 16),
                Math.addExact(minZ, 16),
                land.getName(),
                detail(land),
                style
        ));
    }

    public String markerId(SChunk chunk) {
        return "chunk:" + chunk.getX() + ":" + chunk.getZ();
    }

    private String detail(Land land) {
        String name = escape(land.getName());
        return switch (land.getType()) {
            case PLAYER -> "<b>" + name + "</b><br>Type: Joueur<br>Propriétaire: "
                    + escape(ownerLabelResolver.resolve(land));
            case GUILD -> "<b>" + name + "</b><br>Type: Guilde<br>Guilde: "
                    + escape(ownerLabelResolver.resolve(land));
            case SYSTEM -> "<b>" + name + "</b><br>Type: Système";
            case SUBLAND -> throw new IllegalArgumentException("Sublands do not have claim markers.");
        };
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
