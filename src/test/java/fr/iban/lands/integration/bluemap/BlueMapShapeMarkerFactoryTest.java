package fr.iban.lands.integration.bluemap;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import fr.iban.lands.integration.claims.ClaimMarkerDescriptor;
import fr.iban.lands.integration.claims.ClaimMarkerStyle;
import fr.iban.lands.integration.claims.RgbColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BlueMapShapeMarkerFactoryTest {

    @Test
    void convertsClaimGeometryAndStyleToShapeMarker() {
        ClaimMarkerDescriptor descriptor = new ClaimMarkerDescriptor(
                "chunk:2:-3",
                "world",
                32,
                -48,
                48,
                -32,
                "Maison",
                "<b>Maison</b>",
                new ClaimMarkerStyle(
                        new RgbColor(52, 152, 219),
                        new RgbColor(46, 204, 113),
                        0.35F
                )
        );
        BlueMapShapeMarkerFactory factory = new BlueMapShapeMarkerFactory(2);

        ShapeMarker marker = factory.create(descriptor);

        assertAll(
                () -> assertEquals("Maison", marker.getLabel()),
                () -> assertEquals(64.0F, marker.getShapeY()),
                () -> assertEquals(new Vector2d(32, -48), marker.getShape().getMin()),
                () -> assertEquals(new Vector2d(48, -32), marker.getShape().getMax()),
                () -> assertEquals(2, marker.getLineWidth()),
                () -> assertFalse(marker.isDepthTestEnabled()),
                () -> assertEquals("<b>Maison</b>", marker.getDetail()),
                () -> assertEquals(new Color(52, 152, 219, 1.0F), marker.getLineColor()),
                () -> assertEquals(new Color(46, 204, 113, 0.35F), marker.getFillColor())
        );
    }
}
