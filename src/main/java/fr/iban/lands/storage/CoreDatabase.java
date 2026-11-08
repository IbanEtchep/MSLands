package fr.iban.lands.storage;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;

/**
 * The database pool of the Core plugin (MSCore), where MSLands kept its tables, read by
 * reflection so that ArtisanLands neither compiles nor runs against MSCore. Only used for the
 * one-time {@link LegacyImport}.
 */
public final class CoreDatabase {

    private CoreDatabase() {
    }

    /** Null when Core is not enabled or its pool cannot be reached. */
    public static @Nullable DataSource find(@Nullable Plugin core) {
        if (core == null || !core.isEnabled()) return null;
        try {
            Class<?> dbAccess = Class.forName("fr.iban.common.data.sql.DbAccess", true, core.getClass().getClassLoader());
            Object dataSource = dbAccess.getMethod("getDataSource").invoke(null);
            return dataSource instanceof DataSource ds ? ds : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
