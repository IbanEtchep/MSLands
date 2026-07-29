package fr.iban.lands.integration.claims;

import fr.iban.lands.enums.LandType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapSettingsTest {

    @Test
    void usesDocumentedDefaultsWhenValuesAreMissing() {
        BlueMapSettings settings = BlueMapSettings.load(Map.<String, Object>of()::get, warning -> {});

        assertTrue(settings.enabled());
        assertEquals("Territoires", settings.markerSetLabel());
        assertFalse(settings.defaultHidden());
        assertEquals(2, settings.lineWidth());
        assertEquals(new RgbColor(0x34, 0x98, 0xDB), settings.style(LandType.PLAYER).lineColor());
        assertEquals(0.25F, settings.style(LandType.PLAYER).fillOpacity());
    }

    @Test
    void invalidValuesWarnAndFallBackWhileOpacityIsClamped() {
        Map<String, Object> values = Map.of(
                "bluemap.line-width", 0,
                "bluemap.styles.player.line-color", "blue",
                "bluemap.styles.player.fill-opacity", 2.0
        );
        List<String> warnings = new ArrayList<>();

        BlueMapSettings settings = BlueMapSettings.load(values::get, warnings::add);

        assertEquals(2, settings.lineWidth());
        assertEquals(new RgbColor(0x34, 0x98, 0xDB), settings.style(LandType.PLAYER).lineColor());
        assertEquals(1.0F, settings.style(LandType.PLAYER).fillOpacity());
        assertEquals(2, warnings.size());
    }

    @Test
    void parsesOnlySixDigitHashPrefixedRgbColors() {
        RgbColor fallback = new RgbColor(1, 2, 3);

        assertEquals(new RgbColor(0x34, 0x98, 0xDB), RgbColor.parse("#3498DB", fallback));
        assertEquals(fallback, RgbColor.parse("3498DB", fallback));
        assertEquals(fallback, RgbColor.parse("#3498DB00", fallback));
    }

    @Test
    void hasNoMarkerStyleForSublands() {
        BlueMapSettings settings = BlueMapSettings.load(Map.<String, Object>of()::get, warning -> {});

        assertNull(settings.style(LandType.SUBLAND));
    }
}
