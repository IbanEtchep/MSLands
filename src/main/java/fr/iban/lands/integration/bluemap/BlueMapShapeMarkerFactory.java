package fr.iban.lands.integration.bluemap;

import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import fr.iban.lands.integration.claims.ClaimMarkerDescriptor;
import fr.iban.lands.integration.claims.RgbColor;

public final class BlueMapShapeMarkerFactory {

    private final int lineWidth;

    public BlueMapShapeMarkerFactory(int lineWidth) {
        this.lineWidth = lineWidth;
    }

    public ShapeMarker create(ClaimMarkerDescriptor descriptor) {
        return ShapeMarker.builder()
                .label(descriptor.label())
                .detail(descriptor.detail())
                .shape(Shape.createRect(
                        descriptor.minX(),
                        descriptor.minZ(),
                        descriptor.maxX(),
                        descriptor.maxZ()
                ), 64)
                .lineWidth(lineWidth)
                .lineColor(toColor(descriptor.style().lineColor(), 1.0F))
                .fillColor(toColor(
                        descriptor.style().fillColor(),
                        descriptor.style().fillOpacity()
                ))
                .depthTestEnabled(false)
                .build();
    }

    private Color toColor(RgbColor color, float alpha) {
        return new Color(color.red(), color.green(), color.blue(), alpha);
    }
}
