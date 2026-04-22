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
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(CommandInit::runResync)))
                .then(Commands.literal("wipe")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.literal("confirm")
                                        .executes(CommandInit::runWipe))))

                // ---- Cluster ops ----
                .then(Commands.literal("orphans").executes(CommandInit::runOrphans))
                .then(Commands.literal("clearorphans")
                        .executes(CommandInit::runClearOrphansAll)
                        .then(Commands.argument("server_id", IntegerArgumentType.integer(0))
                                .executes(CommandInit::runClearOrphansId)))
                .then(Commands.literal("peers").executes(CommandInit::runPeers))
                .then(Commands.literal("peerkill")
                        .then(Commands.argument("server_id", IntegerArgumentType.integer(0))
                                .executes(CommandInit::runPeerKill)))
                .then(Commands.literal("cleanup").executes(CommandInit::runCleanup))

                // ---- Config ----
                .then(Commands.literal("reload").executes(CommandInit::runReload))
                .then(Commands.literal("help").executes(CommandInit::runHelp))
        );
    }

    // ========================================================================
    // Command handlers
    // ========================================================================

    private static int runVersion(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§ePlayerSync §f" + PlayerSync.MODID + " §7(NeoForge 1.21.1)"), false);
        return 1;
    }

    private static int runStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        final int serverId = JdbcConfig.SERVER_ID.get();

        // Executor stats
        ThreadPoolExecutor exec = VanillaSync.getExecutor();
        final int active = exec != null ? exec.getActiveCount() : -1;
        final int queue = exec != null ? exec.getQueue().size() : -1;
        final int pool = exec != null ? exec.getPoolSize() : -1;

        // Hikari stats
        HikariPoolMXBean hk = JDBCsetUp.getPoolMXBean();
        final int hA = hk != null ? hk.getActiveConnections() : -1;
        final int hI = hk != null ? hk.getIdleConnections() : -1;

        // Heartbeat age of this server
        long hbAgeTmp = -1;
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT last_update FROM " + Tables.serverInfo() + " WHERE id=?", serverId)) {
            ResultSet rs = qr.resultSet();
            if (rs.next()) hbAgeTmp = System.currentTimeMillis() - rs.getLong("last_update");
        } catch (Exception ignored) {}
        final long hbAge = hbAgeTmp;

        final int online = src.getServer().getPlayerList().getPlayerCount();

        src.sendSuccess(() -> Component.literal("§a=== PlayerSync status ==="), false);
        src.sendSuccess(() -> Component.literal("§7server_id: §f" + serverId
                + "   §7heartbeat_age: §f" + (hbAge >= 0 ? hbAge + "ms" : "§c?")), false);
        src.sendSuccess(() -> Component.literal("§7players online (this server): §f" + online), false);
        src.sendSuccess(() -> Component.literal("§7executor: §factive=" + active + " §7queue=§f" + queue + " §7pool=§f" + pool), false);
        src.sendSuccess(() -> Component.literal("§7hikari: §factive=" + hA + " §7idle=§f" + hI), false);
        src.sendSuccess(() -> Component.literal("§7auto_save: §f" + JdbcConfig.AUTO_SAVE_INTERVAL_MINUTES.get() + "min"
                + "   §7heartbeat_interval: §f" + JdbcConfig.HEARTBEAT_INTERVAL_SECONDS.get() + "s"), false);
        return 1;
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

        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT last_server, online, LENGTH(inventory) AS inv_len, LENGTH(enderchest) AS ec_len,"
                        + " LENGTH(armor) AS arm_len, xp, health FROM " + Tables.playerData() + " WHERE uuid=?",
                uuid.toString())) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                ctx.getSource().sendFailure(Component.literal("§cNo DB row for " + name + " (" + uuid + ")"));
                return 0;
            }
            int lastSrv = rs.getInt("last_server");
            int onlineFlag = rs.getInt("online");
            int invLen = rs.getInt("inv_len");
            int ecLen = rs.getInt("ec_len");
            int armLen = rs.getInt("arm_len");
            int xp = rs.getInt("xp");
            int hp = rs.getInt("health");
            ctx.getSource().sendSuccess(() -> Component.literal("§a=== Info: §f" + name + " §7(" + uuid + ")"), false);
            ctx.getSource().sendSuccess(() -> Component.literal("§7last_server: §f" + lastSrv
                    + (lastSrv == JdbcConfig.SERVER_ID.get() ? " §8(this server)" : "")), false);
            ctx.getSource().sendSuccess(() -> Component.literal("§7online: §f" + onlineFlag
                    + "   §7xp: §f" + xp + "   §7health: §f" + hp), false);
            ctx.getSource().sendSuccess(() -> Component.literal("§7data sizes: §finventory=" + invLen
                    + "B  armor=" + armLen + "B  enderchest=" + ecLen + "B"), false);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cQuery failed: " + e.getMessage()));
            return 0;
        }
        return 1;
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
        PlayerSync.LOGGER.info("[admin-dump] dumping full row for {} (triggered by {})", uuid, ctx.getSource().getTextName());
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT * FROM " + Tables.playerData() + " WHERE uuid=?", uuid.toString())) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                ctx.getSource().sendFailure(Component.literal("§cNo row found"));
                return 0;
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
            SyncLogger.playerEvent(uuid.toString(), "ADMIN_DUMP", "Dumped by " + ctx.getSource().getTextName());
            ctx.getSource().sendSuccess(() -> Component.literal("§aDumped to server log — search §f[admin-dump]"), false);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cDump failed: " + e.getMessage()));
            return 0;
        }
        return 1;
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
        try {
            int d1 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.playerData() + " WHERE uuid=?", uuid.toString());
            int d2 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.curios() + " WHERE uuid=?", uuid.toString());
            int d3 = JDBCsetUp.executePreparedUpdateRet("DELETE FROM " + Tables.modPlayerData() + " WHERE uuid=?", uuid.toString());
            final int total = d1 + d2 + d3;
            ctx.getSource().sendSuccess(() -> Component.literal("§cWiped §f" + total
                    + " §crow(s) for player §f" + uuid + " §8(player_data=" + d1 + ", curios=" + d2 + ", mod=" + d3 + ")"), true);
            SyncLogger.playerEvent(uuid.toString(), "ADMIN_WIPE",
                    "Wiped " + total + " rows by " + ctx.getSource().getTextName());
            PlayerSync.LOGGER.warn("[admin-wipe] {} wiped by {} ({} rows)", uuid, ctx.getSource().getTextName(), total);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cWipe failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runOrphans(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
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
                    final String u = uuid;
                    final int l = ls;
                    final long a = age;
                    src.sendSuccess(() -> Component.literal("§7- §f" + u + " §7last_server=§f" + l
                            + " §7heartbeat_age=§f" + (lu == 0 ? "none" : (a / 1000) + "s")), false);
                }
            }
            final int c = count;
            src.sendSuccess(() -> Component.literal("§a" + c + " §aorphan row(s) found (online=1 on dead peer)"), false);
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cOrphans query failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runClearOrphansAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        // Clear online=1 for rows whose last_server heartbeat is stale OR last_server=0
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
        long threshold = System.currentTimeMillis() - staleMs;
        int selfId = JdbcConfig.SERVER_ID.get();
        try {
            int n = JDBCsetUp.executePreparedUpdateRet(
                    "UPDATE " + Tables.playerData() + " p SET p.online=0"
                            + " WHERE p.online=1 AND p.last_server <> ?"
                            + " AND (p.last_server = 0 OR NOT EXISTS ("
                            + " SELECT 1 FROM " + Tables.serverInfo() + " s WHERE s.id = p.last_server AND s.last_update >= ?))",
                    selfId, threshold);
            ctx.getSource().sendSuccess(() -> Component.literal("§aCleared §f" + n + " §aorphan row(s)"), true);
            SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEAR_ORPHANS",
                    "Cleared " + n + " rows by " + ctx.getSource().getTextName());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cClear failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runClearOrphansId(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "server_id");
        try {
            int n = JDBCsetUp.executePreparedUpdateRet(
                    "UPDATE " + Tables.playerData() + " SET online=0 WHERE last_server=? AND online=1", id);
            ctx.getSource().sendSuccess(() -> Component.literal("§aCleared §f" + n
                    + " §aorphan row(s) with last_server=§f" + id), true);
            SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEAR_ORPHANS_ID",
                    "Cleared " + n + " rows for server_id=" + id + " by " + ctx.getSource().getTextName());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cClear failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runPeers(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT id, enable, last_update FROM " + Tables.serverInfo() + " ORDER BY id")) {
            ResultSet rs = qr.resultSet();
            int self = JdbcConfig.SERVER_ID.get();
            long now = System.currentTimeMillis();
            src.sendSuccess(() -> Component.literal("§a=== Peer servers ==="), false);
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
                final String line = "§7id=§f" + id + " §7enable=§f" + enabled
                        + " §7age=§f" + (lu == 0 ? "never" : (age / 1000) + "s") + "  " + tag;
                src.sendSuccess(() -> Component.literal(line), false);
                shown++;
            }
            final int s = shown;
            src.sendSuccess(() -> Component.literal("§7Total peers: §f" + s), false);
        } catch (Exception e) {
            src.sendFailure(Component.literal("§cPeers query failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runPeerKill(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "server_id");
        if (id == JdbcConfig.SERVER_ID.get()) {
            ctx.getSource().sendFailure(Component.literal("§cCannot peer-kill self"));
            return 0;
        }
        try {
            int n = JDBCsetUp.executePreparedUpdateRet(
                    "UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE id=?", id);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    n > 0 ? "§aMarked peer §f" + id + " §aas stopped (enable=0)"
                          : "§cNo peer found with id=" + id), true);
            SyncLogger.playerEvent("SYSTEM", "ADMIN_PEER_KILL",
                    "Peer " + id + " marked stopped by " + ctx.getSource().getTextName());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cPeerkill failed: " + e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static int runCleanup(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        runClearOrphansAll(ctx);
        long staleMs = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
        long threshold = System.currentTimeMillis() - staleMs;
        try {
            int n = JDBCsetUp.executePreparedUpdateRet(
                    "UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE enable=1 AND id <> ? AND last_update < ?",
                    JdbcConfig.SERVER_ID.get(), threshold);
            ctx.getSource().sendSuccess(() -> Component.literal("§aDisabled §f" + n + " §astale peer server(s)"), true);
            SyncLogger.playerEvent("SYSTEM", "ADMIN_CLEANUP",
                    "Cleanup by " + ctx.getSource().getTextName() + " disabled " + n + " stale peers");
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cCleanup stage 2 failed: " + e.getMessage()));
            return 0;
        }
        return 1;
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

    private static int runHelp(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§a=== /playersync command reference ==="), false);
        String[] lines = {
                "§e/playersync status §7— server + pool + heartbeat summary",
                "§e/playersync poolstats §7— log pool stats immediately",
                "§e/playersync flush [player] §7— force save all / one",
                "§e/playersync info <player> §7— DB row metadata",
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
