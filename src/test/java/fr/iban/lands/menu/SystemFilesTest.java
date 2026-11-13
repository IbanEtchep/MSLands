package fr.iban.lands.menu;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped menus: each listed file is in the jar, and each menu, dialog, data source and action they name exists. */
class SystemFilesTest {

    private static final Pattern MENU_ID = Pattern.compile("menu_id: (\\w+)");
    private static final Pattern DIALOG_ID = Pattern.compile("dialog_id: (\\w+)");
    private static final Pattern SOURCE = Pattern.compile("ref: \"(lands:\\w+)\"");
    private static final Pattern ACTION = Pattern.compile("- (lands:\\w+):");

    @Test
    void everyFileIsShippedAndEveryReferenceResolves() throws IOException {
        Set<String> menus = new HashSet<>();
        Set<String> dialogs = new HashSet<>();
        for (String file : LandsModule.SYSTEM_FILES) {
            String id = file.substring(file.indexOf('/') + 1, file.lastIndexOf('.'));
            (file.startsWith("menus/") ? menus : dialogs).add(id);
        }
        Set<String> sources = Set.of("lands:lands", "lands:land", "lands:limits", "lands:trusts", "lands:bans",
                "lands:trust_target", "lands:permissions", "lands:flags", "lands:links", "lands:link_candidates",
                "lands:chunks");
        Set<String> actions = new HashSet<>();
        for (var field : LandsModule.class.getDeclaredFields()) {
            if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value.startsWith("lands:")) actions.add(value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        for (String file : LandsModule.SYSTEM_FILES) {
            String text = read(LandsModule.SYSTEM_ROOT + "/" + file);
            Map<String, Object> doc = new Yaml().load(text);
            assertEquals(file.startsWith("menus/") ? "menu/v1" : "dialog/v1", doc.get("schema"), file);
            check(MENU_ID, text, menus, file);
            check(DIALOG_ID, text, dialogs, file);
            check(SOURCE, text, sources, file);
            check(ACTION, text, actions, file);
        }
    }

    @Test
    void theOpenedMenusAreShipped() {
        for (String ref : List.of(LandMenus.MAIN, LandMenus.MANAGE, LandMenus.CLAIMS, LandMenus.TRUST_EDIT, LandMenus.DELETE_CONFIRM)) {
            assertTrue(ref.startsWith(LandsModule.FOLDER + ":"), ref);
            assertTrue(LandsModule.SYSTEM_FILES.contains("menus/" + ref.substring(ref.indexOf(':') + 1) + ".yaml"), ref);
        }
    }

    private static void check(Pattern pattern, String text, Set<String> known, String file) {
        Matcher m = pattern.matcher(text);
        while (m.find()) assertTrue(known.contains(m.group(1)), file + " names " + m.group(1));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SystemFilesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
