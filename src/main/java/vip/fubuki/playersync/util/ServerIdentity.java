package vip.fubuki.playersync.util;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * This server's identity within the shared {@code server_info} table.
 *
 * Every ownership check keys off Server_id, so two servers sharing one id silently turn all of
 * them into no-ops. Each boot therefore stamps a random token into its row, which lets the
 * heartbeat notice another server writing to the same row.
 */
public final class ServerIdentity {

    /** How long after its last update a server_info row still counts as a live server's. */
    public static final long LIVENESS_WINDOW_MS = 300_000;

    /** Identifies this boot of this server. Never 0, so 0 means "written before this column". */
    private static final long BOOT_TOKEN = newBootToken();

    private ServerIdentity() {
    }

    public static long bootToken() {
        return BOOT_TOKEN;
    }

    private static long newBootToken() {
        SecureRandom random = new SecureRandom();
        long token;
        do {
            token = random.nextLong();
        } while (token == 0);
        return token;
    }

    /**
     * Refreshes this server's liveness timestamp, and reports an error when another running server
     * has stamped its own token into the same row, i.e. two servers share a Server_id.
     *
     * The refresh matches only while the row is still ours, so this keeps the healthy path at the
     * one round-trip the plain timestamp update always cost - it runs on the server thread.
     */
    public static void heartbeat() throws SQLException {
        int serverId = JdbcConfig.SERVER_ID.get();
        // 0 is a token written by a version without this column, not a foreign server's.
        int refreshed = JDBCsetUp.executeUpdateCount(
                "UPDATE server_info SET last_update=%d, boot_token=%d"
                        + " WHERE id=%d AND boot_token IN (0, %d)",
                System.currentTimeMillis(), BOOT_TOKEN, serverId, BOOT_TOKEN);
        if (refreshed > 0) {
            return;
        }
        reportCollision(serverId);
        // Take the row back, so both servers keep overwriting each other and keep reporting for
        // as long as the collision lasts.
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis()
                + ", boot_token=" + BOOT_TOKEN + " WHERE id=" + serverId);
    }

    /** Reports which foreign boot token has taken our row, if one has. */
    private static void reportCollision(int serverId) throws SQLException {
        long foreignToken;
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery(
                "SELECT boot_token FROM server_info WHERE id=" + serverId)) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                return;
            }
            foreignToken = rs.getLong("boot_token");
        }
        if (foreignToken == 0 || foreignToken == BOOT_TOKEN) {
            return; // the refresh above would have matched; nothing to report
        }
        PlayerSync.LOGGER.error(
                "Server_id collision detected: another running server is using Server_id {} "
                        + "(its boot token {} replaced ours, {}). While two servers share one id "
                        + "every PlayerSync ownership check is defeated, including the "
                        + "already-online kick and the last_server guards, so player data can be "
                        + "duplicated or lost. Give each server its own Server_id in "
                        + "playersync-common.toml.",
                serverId, foreignToken, BOOT_TOKEN);
    }
}
