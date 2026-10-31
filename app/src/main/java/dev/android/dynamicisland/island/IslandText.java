package dev.android.dynamicisland.island;

import android.content.Context;
import android.content.res.Resources;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Module-owned text generated from the English-first Android string resources, readable inside SystemUI. */
public final class IslandText {
    private static final String ROOT = "/dev/android/dynamicisland/i18n/";
    private static final ConcurrentHashMap<String, Properties> CACHE = new ConcurrentHashMap<>();

    private IslandText() { }

    /** Resources of this module's own package, read from the host process once. */
    private static volatile Resources moduleResources;

    public static String get(Context host, String key, Object... arguments) {
        return get(host.getResources().getConfiguration().getLocales().get(0), key, arguments);
    }

    private static String moduleString(Context host, String key, Object... arguments) {
        try {
            Resources resources = moduleResources;
            if (resources == null) {
                resources = host.createPackageContext("dev.android.dynamicisland", 0).getResources();
                moduleResources = resources;
            }
            int id = resources.getIdentifier(key, "string", "dev.android.dynamicisland");
            if (id != 0) {
                return arguments.length == 0 ? resources.getString(id) : resources.getString(id, arguments);
            }
        } catch (Throwable ignored) {
            // a host that cannot read module resources keeps the other paths below
        }
        return null;
    }

    public static String get(Locale locale, String key, Object... arguments) {
        Locale use = locale == null ? Locale.ENGLISH : locale;
        // compiled-in labels first: they cannot fail, whatever the host allows
        String built = IslandTextTable.lookup(key, use.getLanguage());
        if (built != null) {
            return arguments.length == 0 ? built : String.format(use, built, arguments);
        }
        String text = catalog(use.getLanguage()).getProperty(key);
        if (text == null || text.isEmpty()) text = catalog("en").getProperty(key);
        if (text == null || text.isEmpty()) {
            // Last resort: never show a key. "island_do_not_disturb" becomes "Do not disturb", so a label
            // this build has not been taught yet still reads as words instead of a resource name.
            text = prettify(key);
        }
        return arguments.length == 0 ? text : String.format(use, text, arguments);
    }

    public static List<String> languages() {
        String supported = catalog("languages").getProperty("supported", "en");
        return Collections.unmodifiableList(Arrays.asList(supported.split(",")));
    }

    /** A key turned into words: {@code island_do_not_disturb} -> {@code Do not disturb}. */
    private static String prettify(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String text = key.startsWith("island_") ? key.substring("island_".length()) : key;
        text = text.replace('_', ' ').trim();
        if (text.isEmpty()) {
            return key;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Properties catalog(String language) {
        return CACHE.computeIfAbsent(language, name -> {
            Properties values = new Properties();
            if (!name.matches("[a-zA-Z-]+")) return values;
            try (InputStream input = IslandText.class.getResourceAsStream(ROOT + name + ".properties")) {
                if (input != null) values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) {
                // Missing locale data must not break SystemUI; the caller tries the English catalog.
            }
            return values;
        });
    }
}
