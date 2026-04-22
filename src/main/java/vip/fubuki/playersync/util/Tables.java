package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.util.regex.Pattern;

/**
 * Central source of truth for PlayerSync table names.
 *
 * <p>Reads the optional {@code table_prefix} config at every call so that
 * administrators can safely share a single MySQL database with other mods
 * without colliding on generic names such as {@code player_data} or
 * {@code server_info}. The prefix defaults to an empty string to preserve
 * backward compatibility with existing installations.
 *
 * <p>Only the <em>table</em> identifier is prefixed. The database schema
 * qualifier (if any) must be added by the caller, e.g. via
 * {@code "`" + dbName + "`." + Tables.playerData()}.
 *
 * @author vyrriox
 */
public final class Tables {

    private Tables() {}

    // FIX PERF: precompile the validation pattern and cache the validated prefix.
    // String.matches() recompiles the regex on every call; this was invoked from
    // every SQL statement the mod issues (heartbeat, auto-save, join, logout, ...).
    // The config value cannot change at runtime, so a lazy singleton cache is safe.
    private static final Pattern VALID_PREFIX = Pattern.compile("[A-Za-z0-9_]*");
    private static volatile String cachedPrefix;
    private static volatile String cachedRaw;

    private static String prefix() {
        String raw;
        try { raw = JdbcConfig.TABLE_PREFIX.get(); }
        catch (Exception e) { return ""; }
        if (raw == null) raw = "";
        // Fast path: same raw value as last call → return cached validated prefix.
        String lastRaw = cachedRaw;
        if (lastRaw != null && lastRaw.equals(raw)) {
            return cachedPrefix;
        }
        // Validate and cache.
        String validated = VALID_PREFIX.matcher(raw).matches() ? raw : "";
        cachedPrefix = validated;
        cachedRaw = raw;
        return validated;
    }

    public static String playerData()     { return prefix() + "player_data"; }
    public static String serverInfo()     { return prefix() + "server_info"; }
    public static String curios()         { return prefix() + "curios"; }
    public static String backpackData()   { return prefix() + "backpack_data"; }
    public static String modPlayerData()  { return prefix() + "mod_player_data"; }
}
