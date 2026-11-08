package fr.iban.lands.messages;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangFilesTest {

    @Test
    void frenchAndEnglishHaveTheSameKeys() {
        assertEquals(keys("fr"), keys("en"));
    }

    @Test
    void everyPermissionGroupAndFlagHasALabel() {
        for (String lang : new String[]{"fr", "en"}) {
            Set<String> keys = keys(lang);
            for (String action : constants("fr.iban.lands.enums.Action")) {
                assertTrue(keys.contains(LandMessages.ACTION + action), lang + ": " + action);
            }
            for (String group : constants("fr.iban.lands.enums.ActionGroup")) {
                assertTrue(keys.contains(LandMessages.GROUP + group), lang + ": " + group);
            }
            for (String flag : constants("fr.iban.lands.enums.Flag")) {
                assertTrue(keys.contains(LandMessages.FLAG + flag), lang + ": " + flag);
            }
        }
    }

    /** The constants' names, lower case, without initializing the enum (its ItemStacks need a server). */
    private static List<String> constants(String enumClass) {
        try {
            Class<?> type = Class.forName(enumClass, false, LangFilesTest.class.getClassLoader());
            return Arrays.stream(type.getDeclaredFields()).filter(Field::isEnumConstant)
                    .map(f -> f.getName().toLowerCase(Locale.ROOT)).toList();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static Set<String> keys(String lang) {
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/lang/" + lang + ".yml")) {
            assertNotNull(in, lang);
            Map<String, Object> root = new Yaml().load(in);
            Set<String> keys = new TreeSet<>();
            flatten("", root, keys);
            return keys;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Set<String> into) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?> child) flatten(key + ".", (Map<String, Object>) child, into);
            else into.add(key);
        }
    }
}
