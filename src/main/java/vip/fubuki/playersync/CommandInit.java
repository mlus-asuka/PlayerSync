package vip.fubuki.playersync;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zaxxer.hikari.HikariPoolMXBean;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.SyncLogger;
import vip.fubuki.playersync.util.Tables;

import java.sql.ResultSet;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Admin commands for PlayerSync. All commands require permission level 2 (op).
 *
 * <p>Root: {@code /playersync}
 *
 * <ul>
 *   <li>{@code status} — server + pool + heartbeat summary</li>
 *   <li>{@code flush [player]} — force an immediate save</li>
 *   <li>{@code info <player>} — show DB row metadata</li>
 *   <li>{@code reload} — reload config from disk</li>
 *   <li>{@code orphans} — list stuck online=1 rows</li>
 *   <li>{@code clearorphans [server_id]} — clear them</li>
 *   <li>{@code peers} — list peer servers</li>
 *   <li>{@code peerkill <id>} — force-disable a zombie peer</li>
 *   <li>{@code cleanup} — clear orphans + stale peers in one go</li>
 *   <li>{@code dump <player>} — dump DB row keys & sizes</li>
 *   <li>{@code resync <player>} — force re-apply from DB</li>
 *   <li>{@code poolstats} — immediate pool stats</li>
 *   <li>{@code wipe <player>} — DANGER: delete all rows for a player</li>
 *   <li>{@code version} — mod version</li>
 * </ul>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = PlayerSync.MODID)
public class CommandInit {

    private static final int PERM_OP = 2;
    // AUDIT FIX (security): permission level 2 is the level command blocks and
    // datapack functions execute at — any player able to place a command block could
    // run wipe/peerkill/clearorphans/cleanup/resync. Destructive subcommands now
    // require level 3 (excludes command blocks + datapacks, still allows console,
    // RCON and full ops). Read-only commands stay at level 2.
    private static final int PERM_ADMIN = 3;

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("playersync")
                .requires(cs -> cs.hasPermission(PERM_OP))

                // ---- Status / info ----
                .then(Commands.literal("version").executes(CommandInit::runVersion))
                .then(Commands.literal("status").executes(CommandInit::runStatus))
                .then(Commands.literal("poolstats").executes(CommandInit::runPoolStats))

                // ---- Player ops ----
                .then(Commands.literal("flush")
                        .executes(CommandInit::runFlushAll)
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CommandInit::runFlushPlayer)))
                .then(Commands.literal("info")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(CommandInit::runInfo)))
                .then(Commands.literal("dump")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(CommandInit::runDump)))
                .then(Commands.literal("resync")
                        .requires(cs -> cs.hasPermission(PERM_ADMIN))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CommandInit::runResync)))
                .then(Commands.literal("wipe")
                        .requires(cs -> cs.hasPermission(PERM_ADMIN))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.literal("confirm")
                                        .executes(CommandInit::runWipe))))

                // ---- Inventory viewer ----
                .then(Commands.literal("inventory")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> runInventoryView(ctx, "all"))
                                .then(Commands.literal("main")
                                        .executes(ctx -> runInventoryView(ctx, "main")))
                                .then(Commands.literal("armor")
                                        .executes(ctx -> runInventoryView(ctx, "armor")))
                                .then(Commands.literal("ender")
                                        .executes(ctx -> runInventoryView(ctx, "ender")))
                                .then(Commands.literal("curios")
                                        .executes(ctx -> runInventoryView(ctx, "curios")))
                                .then(Commands.literal("all")
                                        .executes(ctx -> runInventoryView(ctx, "all")))))

                // ---- Cluster ops ----
                .then(Commands.literal("orphans").executes(CommandInit::runOrphans))
                .then(Commands.literal("clearorphans")
                        .requires(cs -> cs.hasPermission(PERM_ADMIN))
                        .executes(CommandInit::runClearOrphansAll)
                        .then(Commands.argument("server_id", IntegerArgumentType.integer(0))
                                .executes(CommandInit::runClearOrphansId)))
                .then(Commands.literal("peers").executes(CommandInit::runPeers))
                .then(Commands.literal("peerkill")
                        .requires(cs -> cs.hasPermission(PERM_ADMIN))
                        .then(Commands.argument("server_id", IntegerArgumentType.integer(0))
                                .executes(CommandInit::runPeerKill)))
                .then(Commands.literal("cleanup")
                        .requires(cs -> cs.hasPermission(PERM_ADMIN))
                        .executes(CommandInit::runCleanup))

                // ---- Config ----
                .then(Commands.literal("reload").executes(CommandInit::runReload))
                .then(Commands.literal("help").executes(CommandInit::runHelp))
        );
    }

    // ========================================================================
    // Command handlers
    // ========================================================================

    /**
     * AUDIT FIX (main-thread DoS): Brigadier executes command handlers on the server
     * main thread — every synchronous JDBC call here could stall ticks for up to the
     * pool's 10s connectionTimeout (per statement). All DB-touching command bodies
     * now run on the sync executor and deliver chat output back via server.execute().
     * Argument resolution stays on the main thread (Brigadier contract).
     */
    private static int runAsyncDb(CommandSourceStack src, String label, Runnable dbTask) {
        ThreadPoolExecutor exec = VanillaSync.getExecutor();
        if (exec == null || exec.isShutdown()) {
            src.sendFailure(Component.literal("§cPlayerSync executor unavailable"));
            return 0;
        }
        java.util.concurrent.CompletableFuture.runAsync(dbTask, exec).exceptionally(t -> {
            replyFailure(src, "§c" + label + " failed: " + t.getMessage());
            return null;
        });
        return 1;
    }

    /** Delivers a success line on the main thread (async command bodies must not touch chat directly). */
    private static void replySuccess(CommandSourceStack src, String msg, boolean broadcast) {
        src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(msg), broadcast));
    }

    /** Delivers a failure line on the main thread. */
    private static void replyFailure(CommandSourceStack src, String msg) {
        src.getServer().execute(() -> src.sendFailure(Component.literal(msg)));
    }

    private static int runVersion(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§ePlayerSync §f" + PlayerSync.MODID + " §7(NeoForge 1.21.1)"), false);
        return 1;
    }

    private static int runStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        final int serverId = JdbcConfig.SERVER_ID.get();

        // Executor stats (read on main thread — cheap, no I/O)
        ThreadPoolExecutor exec = VanillaSync.getExecutor();
        final int active = exec != null ? exec.getActiveCount() : -1;
        final int queue = exec != null ? exec.getQueue().size() : -1;
        final int pool = exec != null ? exec.getPoolSize() : -1;

        // Hikari stats
        HikariPoolMXBean hk = JDBCsetUp.getPoolMXBean();
        final int hA = hk != null ? hk.getActiveConnections() : -1;
        final int hI = hk != null ? hk.getIdleConnections() : -1;

        final int online = src.getServer().getPlayerList().getPlayerCount();

        return runAsyncDb(src, "status", () -> {
            long hbAgeTmp = -1;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT last_update FROM " + Tables.serverInfo() + " WHERE id=?", serverId)) {
                ResultSet rs = qr.resultSet();
                if (rs.next()) hbAgeTmp = System.currentTimeMillis() - rs.getLong("last_update");
            } catch (Exception ignored) {}
            final long hbAge = hbAgeTmp;

            replySuccess(src, "§a=== PlayerSync status ===", false);
            replySuccess(src, "§7server_id: §f" + serverId
                    + "   §7heartbeat_age: §f" + (hbAge >= 0 ? hbAge + "ms" : "§c?"), false);
            replySuccess(src, "§7players online (this server): §f" + online, false);
            replySuccess(src, "§7executor: §factive=" + active + " §7queue=§f" + queue + " §7pool=§f" + pool, false);
            replySuccess(src, "§7hikari: §factive=" + hA + " §7idle=§f" + hI, false);
            replySuccess(src, "§7auto_save: §f" + JdbcConfig.AUTO_SAVE_INTERVAL_MINUTES.get() + "min"
                    + "   §7heartbeat_interval: §f" + JdbcConfig.HEARTBEAT_INTERVAL_SECONDS.get() + "s", false);
        });
    }

    private static int runPoolStats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ThreadPoolExecutor exec = VanillaSync.getExecutor();
        HikariPoolMXBean hk = JDBCsetUp.getPoolMXBean();
        int active = exec != null ? exec.getActiveCount() : -1;
        int queue = exec != null ? exec.getQueue().size() : -1;
        int idle = exec != null ? exec.getPoolSize() - exec.getActiveCount() : -1;
        int hA = hk != null ? hk.getActiveConnections() : -1;
        int hI = hk != null ? hk.getIdleConnections() : -1;
        SyncLogger.poolStats(active, queue, idle, hA, hI);
        ctx.getSource().sendSuccess(() -> Component.literal("§aPool stats logged to sync.log §7(exec a=" + active
                + " q=" + queue + "/" + (exec != null ? exec.getQueue().size() + exec.getQueue().remainingCapacity() : "?")
                + ", hikari a=" + hA + "/" + JdbcConfig.HIKARI_POOL_MAX_SIZE.get() + ")"), false);
        return 1;
    }

    private static int runFlushAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int count = 0;
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (p.getTags().contains("player_synced") && !p.isDeadOrDying()) {
                VanillaSync.snapshotAndQueueSave(p, "ADMIN_FLUSH");
                count++;
            }
        }
        final int queued = count;
        ctx.getSource().sendSuccess(() -> Component.literal("§aFlush queued for §f" + queued + " §aplayer(s)"), true);
        SyncLogger.playerEvent("SYSTEM", "ADMIN_FLUSH_ALL", "Triggered by " + ctx.getSource().getTextName() + " (" + queued + " players)");
        return queued;
    }

    private static int runFlushPlayer(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(ctx, "target");
        VanillaSync.snapshotAndQueueSave(p, "ADMIN_FLUSH");
        ctx.getSource().sendSuccess(() -> Component.literal("§aFlush queued for §f" + p.getName().getString()), true);
        SyncLogger.playerEvent(p.getUUID().toString(), "ADMIN_FLUSH",
                "Triggered by " + ctx.getSource().getTextName());
        return 1;
    }

    private static int runInfo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<com.mojang.authlib.GameProfile> profiles =
                GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo matching player"));
            return 0;
        }
        com.mojang.authlib.GameProfile profile = profiles.iterator().next();
        UUID uuid = profile.getId();
        String name = profile.getName();
        CommandSourceStack src = ctx.getSource();

        return runAsyncDb(src, "info", () -> {
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT last_server, online, LENGTH(inventory) AS inv_len, LENGTH(enderchest) AS ec_len,"
                            + " LENGTH(armor) AS arm_len, xp, health FROM " + Tables.playerData() + " WHERE uuid=?",
                    uuid.toString())) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) {
                    replyFailure(src, "§cNo DB row for " + name + " (" + uuid + ")");
                    return;
                }
                int lastSrv = rs.getInt("last_server");
                int onlineFlag = rs.getInt("online");
                int invLen = rs.getInt("inv_len");
                int ecLen = rs.getInt("ec_len");
                int armLen = rs.getInt("arm_len");
                int xp = rs.getInt("xp");
                int hp = rs.getInt("health");
                replySuccess(src, "§a=== Info: §f" + name + " §7(" + uuid + ")", false);
                replySuccess(src, "§7last_server: §f" + lastSrv
                        + (lastSrv == JdbcConfig.SERVER_ID.get() ? " §8(this server)" : ""), false);
                replySuccess(src, "§7online: §f" + onlineFlag
                        + "   §7xp: §f" + xp + "   §7health: §f" + hp, false);
                replySuccess(src, "§7data sizes: §finventory=" + invLen
                        + "B  armor=" + armLen + "B  enderchest=" + ecLen + "B", false);
            } catch (Exception e) {
                replyFailure(src, "§cQuery failed: " + e.getMessage());
            }
        });
    }

    private static int runDump(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<com.mojang.authlib.GameProfile> profiles =
                GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo matching player"));
            return 0;
        }
        UUID uuid = profiles.iterator().next().getId();
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();
        PlayerSync.LOGGER.info("[admin-dump] dumping full row for {} (triggered by {})", uuid, triggeredBy);

        return runAsyncDb(src, "dump", () -> {
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT * FROM " + Tables.playerData() + " WHERE uuid=?", uuid.toString())) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) {
                    replyFailure(src, "§cNo row found");
                    return;
                }
                int cols = rs.getMetaData().getColumnCount();
                StringBuilder sb = new StringBuilder("[admin-dump] ").append(uuid).append(" {");
                for (int i = 1; i <= cols; i++) {
                    String col = rs.getMetaData().getColumnName(i);
                    Object v = rs.getObject(i);
                    String val = v == null ? "null" : (v instanceof byte[] ? "<" + ((byte[]) v).length + " bytes>"
                            : v instanceof String ? "<" + ((String) v).length() + " chars>"
                            : v.toString());
                    sb.append(col).append("=").append(val);
                    if (i < cols) sb.append(", ");
                }
                sb.append("}");
                PlayerSync.LOGGER.info(sb.toString());
                SyncLogger.playerEvent(uuid.toString(), "ADMIN_DUMP", "Dumped by " + triggeredBy);
                replySuccess(src, "§aDumped to server log — search §f[admin-dump]", false);
            } catch (Exception e) {
                replyFailure(src, "§cDump failed: " + e.getMessage());
            }
        });
    }

    private static int runResync(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(ctx, "target");
        p.removeTag("player_synced");
        ctx.getSource().sendSuccess(() -> Component.literal("§eKicking §f" + p.getName().getString()
                + " §eto force resync on rejoin"), true);
        SyncLogger.playerEvent(p.getUUID().toString(), "ADMIN_RESYNC", "Triggered by " + ctx.getSource().getTextName());
        p.connection.disconnect(Component.literal("§ePlayerSync resync — please reconnect"));
        return 1;
    }

    private static int runWipe(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Collection<com.mojang.authlib.GameProfile> profiles =
                GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo matching player"));
            return 0;
        }
        UUID uuid = profiles.iterator().next().getId();
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();

        return runAsyncDb(src, "wipe", () -> {
            try {
                int d1 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.playerData() + " WHERE uuid=?", uuid.toString());
                int d2 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.curios() + " WHERE uuid=?", uuid.toString());
                int d3 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.modPlayerData() + " WHERE uuid=?", uuid.toString());
                final int total = d1 + d2 + d3;
                replySuccess(src, "§cWiped §f" + total
                        + " §crow(s) for player §f" + uuid + " §8(player_data=" + d1 + ", curios=" + d2 + ", mod=" + d3 + ")", true);
                SyncLogger.playerEvent(uuid.toString(), "ADMIN_WIPE",
                        "Wiped " + total + " rows by " + triggeredBy);
                PlayerSync.LOGGER.warn("[admin-wipe] {} wiped by {} ({} rows)", uuid, triggeredBy, total);
            } catch (Exception e) {
                replyFailure(src, "§cWipe failed: " + e.getMessage());
            }
        });
    }

    private static int runOrphans(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;

        return runAsyncDb(src, "orphans", () -> {
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT p.uuid, p.last_server, s.last_update FROM " + Tables.playerData() + " p"
                            + " LEFT JOIN " + Tables.serverInfo() + " s ON s.id = p.last_server"
                            + " WHERE p.online=1")) {
                ResultSet rs = qr.resultSet();
                int count = 0;
                long now = System.currentTimeMillis();
                int selfId = JdbcConfig.SERVER_ID.get();
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    int ls = rs.getInt("last_server");
                    long lu = rs.getLong("last_update");
                    long age = now - lu;
                    boolean stale = lu == 0 || age > staleMs || ls == 0;
                    if (stale && ls != selfId) {
                        count++;
                        replySuccess(src, "§7- §f" + uuid + " §7last_server=§f" + ls
                                + " §7heartbeat_age=§f" + (lu == 0 ? "none" : (age / 1000) + "s"), false);
                    }
                }
                replySuccess(src, "§a" + count + " §aorphan row(s) found (online=1 on dead peer)", false);
            } catch (Exception e) {
                replyFailure(src, "§cOrphans query failed: " + e.getMessage());
            }
        });
    }

    private static int runClearOrphansAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        // Clear online=1 for rows whose last_server heartbeat is stale OR last_server=0
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
        long threshold = System.currentTimeMillis() - staleMs;
        int selfId = JdbcConfig.SERVER_ID.get();
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();

        return runAsyncDb(src, "clearorphans", () -> {
            try {
                int n = JDBCsetUp.executePreparedUpdateRet(
                        "UPDATE " + Tables.playerData() + " p SET p.online=0"
                                + " WHERE p.online=1 AND p.last_server <> ?"
                                + " AND (p.last_server = 0 OR NOT EXISTS ("
                                + " SELECT 1 FROM " + Tables.serverInfo() + " s WHERE s.id = p.last_server AND s.last_update >= ?))",
                        selfId, threshold);
                replySuccess(src, "§aCleared §f" + n + " §aorphan row(s)", true);
                SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEAR_ORPHANS",
                        "Cleared " + n + " rows by " + triggeredBy);
            } catch (Exception e) {
                replyFailure(src, "§cClear failed: " + e.getMessage());
            }
        });
    }

    private static int runClearOrphansId(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "server_id");
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();

        return runAsyncDb(src, "clearorphans", () -> {
            try {
                int n = JDBCsetUp.executePreparedUpdateRet(
                        "UPDATE " + Tables.playerData() + " SET online=0 WHERE last_server=? AND online=1", id);
                replySuccess(src, "§aCleared §f" + n
                        + " §aorphan row(s) with last_server=§f" + id, true);
                SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEAR_ORPHANS_ID",
                        "Cleared " + n + " rows for server_id=" + id + " by " + triggeredBy);
            } catch (Exception e) {
                replyFailure(src, "§cClear failed: " + e.getMessage());
            }
        });
    }

    private static int runPeers(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;

        return runAsyncDb(src, "peers", () -> {
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT id, enable, last_update FROM " + Tables.serverInfo() + " ORDER BY id")) {
                ResultSet rs = qr.resultSet();
                int self = JdbcConfig.SERVER_ID.get();
                long now = System.currentTimeMillis();
                replySuccess(src, "§a=== Peer servers ===", false);
                int shown = 0;
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int enabled = rs.getInt("enable");
                    long lu = rs.getLong("last_update");
                    long age = now - lu;
                    boolean stale = enabled == 1 && age > staleMs;
                    String tag = id == self ? "§a[SELF]§r "
                            : stale ? "§c[STALE]§r "
                            : enabled == 0 ? "§8[STOPPED]§r "
                            : "§a[ALIVE]§r ";
                    replySuccess(src, "§7id=§f" + id + " §7enable=§f" + enabled
                            + " §7age=§f" + (lu == 0 ? "never" : (age / 1000) + "s") + "  " + tag, false);
                    shown++;
                }
                replySuccess(src, "§7Total peers: §f" + shown, false);
            } catch (Exception e) {
                replyFailure(src, "§cPeers query failed: " + e.getMessage());
            }
        });
    }

    private static int runPeerKill(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "server_id");
        if (id == JdbcConfig.SERVER_ID.get()) {
            ctx.getSource().sendFailure(Component.literal("§cCannot peer-kill self"));
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();

        return runAsyncDb(src, "peerkill", () -> {
            try {
                int n = JDBCsetUp.executePreparedUpdateRet(
                        "UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE id=?", id);
                replySuccess(src, n > 0 ? "§aMarked peer §f" + id + " §aas stopped (enable=0)"
                        : "§cNo peer found with id=" + id, true);
                SyncLogger.playerEvent("SYSTEM", "ADMIN_PEER_KILL",
                        "Peer " + id + " marked stopped by " + triggeredBy);
            } catch (Exception e) {
                replyFailure(src, "§cPeerkill failed: " + e.getMessage());
            }
        });
    }

    private static int runCleanup(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        // Both stages (orphan rows + stale peers) folded into ONE async task so their
        // ordering is preserved without chaining two executor hops.
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
        long threshold = System.currentTimeMillis() - staleMs;
        int selfId = JdbcConfig.SERVER_ID.get();
        CommandSourceStack src = ctx.getSource();
        final String triggeredBy = src.getTextName();

        return runAsyncDb(src, "cleanup", () -> {
            try {
                int n1 = JDBCsetUp.executePreparedUpdateRet(
                        "UPDATE " + Tables.playerData() + " p SET p.online=0"
                                + " WHERE p.online=1 AND p.last_server <> ?"
                                + " AND (p.last_server = 0 OR NOT EXISTS ("
                                + " SELECT 1 FROM " + Tables.serverInfo() + " s WHERE s.id = p.last_server AND s.last_update >= ?))",
                        selfId, threshold);
                replySuccess(src, "§aCleared §f" + n1 + " §aorphan row(s)", true);
                SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEAR_ORPHANS",
                        "Cleared " + n1 + " rows by " + triggeredBy);
            } catch (Exception e) {
                replyFailure(src, "§cClear failed: " + e.getMessage());
            }
            try {
                int n = JDBCsetUp.executePreparedUpdateRet(
                        "UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE enable=1 AND id <> ? AND last_update < ?",
                        selfId, threshold);
                replySuccess(src, "§aDisabled §f" + n + " §astale peer server(s)", true);
                SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEANUP",
                        "Cleanup by " + triggeredBy + " disabled " + n + " stale peers");
            } catch (Exception e) {
                replyFailure(src, "§cCleanup stage 2 failed: " + e.getMessage());
            }
        });
    }

    private static int runReload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        // NeoForge's ModConfigSpec is mostly static and not reloadable at runtime.
        // We expose the command as a marker so admins know to restart after edits,
        // but also flush in-memory caches that read config lazily (Tables prefix).
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§eModConfigSpec is loaded at startup; full reload requires a server restart."), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§7Runtime-readable values (thread pool / heartbeat period / toggles) will take effect on next tick."), false);
        return 1;
    }

    /**
     * Pretty-prints a player's inventory / armor / ender chest / curios from the DB.
     * Works on offline players too — reads the serialized columns directly instead
     * of requiring the entity to be online. Output is compact, per-section, with
     * item ID and count per non-empty slot.
     */
    private static int runInventoryView(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String section)
            throws CommandSyntaxException {
        Collection<com.mojang.authlib.GameProfile> profiles =
                GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo matching player"));
            return 0;
        }
        com.mojang.authlib.GameProfile profile = profiles.iterator().next();
        UUID uuid = profile.getId();
        String name = profile.getName();

        CommandSourceStack src = ctx.getSource();

        return runAsyncDb(src, "inventory", () -> {
            // EXECUTOR: raw column reads only — item deserialization touches the
            // registries, so the formatting hops back to the main thread below.
            String inventoryRawTmp = null, armorRawTmp = null, enderRawTmp = null;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT inventory, armor, enderchest FROM " + Tables.playerData() + " WHERE uuid=?",
                    uuid.toString())) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) {
                    replyFailure(src, "§cNo DB row for " + name + " (" + uuid + ")");
                    return;
                }
                inventoryRawTmp = rs.getString("inventory");
                armorRawTmp = rs.getString("armor");
                enderRawTmp = rs.getString("enderchest");
            } catch (Exception e) {
                replyFailure(src, "§cDB query failed: " + e.getMessage());
                return;
            }

            String curiosRawTmp = null;
            if ("all".equals(section) || "curios".equals(section)) {
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT curios_item FROM " + Tables.curios() + " WHERE uuid=?", uuid.toString())) {
                    ResultSet rs = qr.resultSet();
                    if (rs.next()) curiosRawTmp = rs.getString("curios_item");
                } catch (Exception ignored) {}
            }

            final String inventoryRaw = inventoryRawTmp;
            final String armorRaw = armorRawTmp;
            final String enderRaw = enderRawTmp;
            final String curiosRaw = curiosRawTmp;

            // MAIN THREAD: deserialize + pretty-print.
            src.getServer().execute(() -> {
                src.sendSuccess(() -> Component.literal("§a=== Inventory of §f" + name + " §7(" + uuid + ")"), false);

                int totalShown = 0;
                if ("all".equals(section) || "main".equals(section)) {
                    totalShown += printSection(src, "§6Main inventory", inventoryRaw, 36);
                }
                if ("all".equals(section) || "armor".equals(section)) {
                    totalShown += printSection(src, "§6Armor §8(0=boots,1=legs,2=chest,3=helm)", armorRaw, 4);
                }
                if ("all".equals(section) || "ender".equals(section)) {
                    totalShown += printSection(src, "§6Ender chest", enderRaw, 27);
                }
                if ("all".equals(section) || "curios".equals(section)) {
                    totalShown += printCurios(src, curiosRaw);
                }

                final int shown = totalShown;
                src.sendSuccess(() -> Component.literal("§7— §f" + shown + " §7non-empty slot(s) shown"), false);
            });
        });
    }

    /** Prints a vanilla-style slot section (Map<Integer,String>). Returns non-empty count. */
    private static int printSection(CommandSourceStack src, String header, String raw, int expectedSize) {
        if (raw == null || raw.length() <= 2) {
            src.sendSuccess(() -> Component.literal(header + "§7: §8(empty)"), false);
            return 0;
        }
        java.util.Map<Integer, String> map;
        try {
            map = vip.fubuki.playersync.util.LocalJsonUtil.StringToEntryMap(raw);
        } catch (Exception e) {
            src.sendSuccess(() -> Component.literal(header + "§7: §c<parse error: " + e.getMessage() + ">"), false);
            return 0;
        }
        if (map.isEmpty()) {
            src.sendSuccess(() -> Component.literal(header + "§7: §8(empty)"), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(header + "§7 (" + map.size() + " slot(s) filled of " + expectedSize + "):"), false);
        int shown = 0;
        for (java.util.Map.Entry<Integer, String> e : new java.util.TreeMap<>(map).entrySet()) {
            String line = formatSlotLine(e.getKey().toString(), e.getValue());
            if (line != null) {
                src.sendSuccess(() -> Component.literal(line), false);
                shown++;
            }
        }
        return shown;
    }

    /** Curios has composite keys ("slotType:index" and "cos:slotType:index"). */
    private static int printCurios(CommandSourceStack src, String raw) {
        if (raw == null || raw.length() <= 2) {
            src.sendSuccess(() -> Component.literal("§6Curios§7: §8(empty)"), false);
            return 0;
        }
        java.util.Map<String, String> map;
        try {
            map = vip.fubuki.playersync.util.LocalJsonUtil.StringToMap(raw);
        } catch (Exception e) {
            src.sendSuccess(() -> Component.literal("§6Curios§7: §c<parse error>"), false);
            return 0;
        }
        if (map.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§6Curios§7: §8(empty)"), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6Curios§7 (" + map.size() + " slot(s) filled):"), false);
        int shown = 0;
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(map).entrySet()) {
            String line = formatSlotLine(e.getKey(), e.getValue());
            if (line != null) {
                src.sendSuccess(() -> Component.literal(line), false);
                shown++;
            }
        }
        return shown;
    }

    /** Deserializes a single slot payload into a human-readable line. */
    private static String formatSlotLine(String slotKey, String payload) {
        try {
            net.minecraft.world.item.ItemStack stack =
                    vip.fubuki.playersync.sync.VanillaSync.deserializeAndCreatePlaceholderIfNeeded(payload);
            if (stack == null || stack.isEmpty()) return null;
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            String idStr = id == null ? "unknown" : id.toString();
            String display = stack.getHoverName().getString();
            // Placeholder items (items from a mod not loaded on this server) show up with their
            // original id preserved inside CustomData — the deserializer already handled that.
            boolean placeholder = idStr.equals("minecraft:paper")
                    && stack.getComponents().has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
                    && stack.getComponents().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
                        .copyTag().contains("playersync:original_item_nbt");
            String prefix = placeholder ? "§d[placeholder] " : "§f";
            return "§7  [" + slotKey + "] " + prefix + idStr + "§7 x§f" + stack.getCount()
                    + (display.equals(stack.getItem().getDescription().getString()) ? "" : " §8(" + display + ")");
        } catch (Throwable t) {
            return "§7  [" + slotKey + "] §c<parse error: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static int runHelp(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§a=== /playersync command reference ==="), false);
        String[] lines = {
                "§e/playersync status §7— server + pool + heartbeat summary",
                "§e/playersync poolstats §7— log pool stats immediately",
                "§e/playersync flush [player] §7— force save all / one",
                "§e/playersync info <player> §7— DB row metadata",
                "§e/playersync inventory <player> [main|armor|ender|curios|all] §7— pretty-print stored inventory",
                "§e/playersync dump <player> §7— dump DB row to server log",
                "§e/playersync resync <player> §7— kick to force re-sync",
                "§e/playersync wipe <player> confirm §7— DELETE rows (DANGER)",
                "§e/playersync orphans §7— list stuck online=1",
                "§e/playersync clearorphans [id] §7— clear orphan rows",
                "§e/playersync peers §7— list peer servers",
                "§e/playersync peerkill <id> §7— force-disable a peer",
                "§e/playersync cleanup §7— orphans + stale peers",
                "§e/playersync reload §7— status note about config reload",
                "§e/playersync version §7— mod version",
        };
        for (String l : lines) {
            src.sendSuccess(() -> Component.literal(l), false);
        }
        return 1;
    }
}
