package fr.iban.lands.storage;

/**
 * The names of ArtisanLands' tables under the suite's prefix
 * ({@code api.getDatabase().tablePrefix("lands")}, {@code artisan_lands_} by default).
 * Concatenated into SQL: the prefix is validated by Artisan (lower-case letters, digits, {@code _}).
 */
public record LandTables(String prefix) {

    public String lands() { return prefix + "lands"; }
    public String bans() { return prefix + "bans"; }
    public String trusts() { return prefix + "trusts"; }
    public String flags() { return prefix + "flags"; }
    public String links() { return prefix + "links"; }
    public String chunks() { return prefix + "chunks"; }
    public String worldDefaults() { return prefix + "world_defaults"; }
    public String sublands() { return prefix + "sublands"; }
    public String limits() { return prefix + "limits"; }
    public String effects() { return prefix + "effects"; }
    public String commands() { return prefix + "commands"; }
    public String schemaVersion() { return prefix + "schema_version"; }
    public String imports() { return prefix + "imports"; }
}
