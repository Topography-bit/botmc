package macro.topography;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Autopilot + Pathfinder debug: HUD overlay + tick-rate CSV logger.
 *
 * Toggle HUD:  F9
 * Toggle CSV:  Shift+F9
 *
 * CSV logs one row per game tick (20 Hz) with columns:
 *   tick, time_ms, px, py, pz, mx, my, mz, mob_dist,
 *   in_combat, direct_pursuit, aim_error, crosshair_entity,
 *   attack, misses, combat_stuck, forward, sprint, jump, strafe,
 *   path_len, wp_index, dist_wp, dist_goal, stuck, target
 */
public final class AutopilotDebugHud {

    private AutopilotDebugHud() {}

    private static boolean hudEnabled = false;
    private static boolean csvEnabled = false;
    private static PrintWriter csvWriter = null;
    private static long csvTick = 0;
    private static long csvStartNano = 0;

    // ── Public API ──────────────────────────────────────────────

    public static void toggleHud() { hudEnabled = !hudEnabled; }
    public static void toggleCsv() {
        if (csvEnabled) stopCsv(); else startCsv();
    }
    public static boolean isHudEnabled() { return hudEnabled; }
    public static boolean isCsvEnabled() { return csvEnabled; }

    public static void register() {
        HudRenderCallback.EVENT.register(AutopilotDebugHud::render);
    }

    /** Call from Autopilot.onTick() — writes one CSV row per tick. */
    public static void tickLog() {
        if (!csvEnabled || csvWriter == null) return;
        double timeMs = (System.nanoTime() - csvStartNano) / 1_000_000.0;
        csvTick++;

        csvWriter.printf(Locale.ROOT,
            "%d,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
          + "%d,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,"
          + "%d,%d,%.2f,%.2f,%d,"
          + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%s%n",
            csvTick, timeMs,
            Autopilot.dbg_playerX, Autopilot.dbg_playerY, Autopilot.dbg_playerZ,
            Autopilot.dbg_mobX, Autopilot.dbg_mobY, Autopilot.dbg_mobZ,
            Autopilot.dbg_mobDist,
            Autopilot.dbg_inCombat ? 1 : 0,
            Autopilot.dbg_directPursuit ? 1 : 0,
            Autopilot.dbg_aimError,
            Autopilot.dbg_crosshairOnEntity ? 1 : 0,
            Autopilot.dbg_attackFired ? 1 : 0,
            Autopilot.dbg_consecutiveMisses,
            Autopilot.dbg_combatStuckTicks,
            Autopilot.dbg_forward ? 1 : 0,
            Autopilot.dbg_sprint ? 1 : 0,
            Autopilot.dbg_jump ? 1 : 0,
            Autopilot.dbg_strafeDir,
            Autopilot.dbg_pathLen,
            Autopilot.dbg_wpIndex,
            Autopilot.dbg_distToWp,
            Autopilot.dbg_distToGoal,
            Autopilot.dbg_stuckTicks,
            Autopilot.dbg_wpX, Autopilot.dbg_wpY, Autopilot.dbg_wpZ,
            Autopilot.dbg_goalYaw, Autopilot.dbg_currentYaw,
            Autopilot.dbg_goalPitchTick, Autopilot.dbg_currentPitch,
            Autopilot.dbg_pathBlocked ? 1 : 0,
            Autopilot.dbg_targetName != null ? Autopilot.dbg_targetName : "none"
        );

        if (csvTick % 100 == 0) csvWriter.flush();
    }

    // ── HUD Overlay ───────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!Autopilot.isEnabled() || !hudEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        TextRenderer font = mc.textRenderer;
        if (font == null) return;

        // Right side of screen
        int screenW = mc.getWindow().getScaledWidth();
        int x = screenW - 260;
        int y = 4;
        int lineH = 10;
        int white  = 0xFFFFFFFF;
        int green  = 0xFF55FF55;
        int yellow = 0xFFFFFF55;
        int red    = 0xFFFF5555;
        int cyan   = 0xFF55FFFF;
        int gray   = 0xFFAAAAAA;
        int orange = 0xFFFFAA00;

        // ── Title ──
        String title = "[Autopilot]";
        int titleColor = green;
        if (Autopilot.dbg_inCombat) {
            title += " COMBAT";
            titleColor = red;
        } else if (Autopilot.dbg_directPursuit) {
            title += " PURSUIT";
            titleColor = orange;
        }
        ctx.drawText(font, title, x, y, titleColor, true);
        y += lineH;

        // ── Target ──
        if (Autopilot.dbg_mobDist < 999) {
            String mobName = Autopilot.dbg_targetName != null ? Autopilot.dbg_targetName : "?";
            // Shorten translation key
            int lastDot = mobName.lastIndexOf('.');
            if (lastDot >= 0) mobName = mobName.substring(lastDot + 1);
            ctx.drawText(font,
                String.format("mob: %s  dist: %.1f", mobName, Autopilot.dbg_mobDist),
                x, y, white, true);
        } else {
            ctx.drawText(font, "mob: none", x, y, gray, true);
        }
        y += lineH;

        // ── Aim ──
        if (Autopilot.dbg_inCombat || Autopilot.dbg_directPursuit) {
            int aimColor = Autopilot.dbg_aimError < 12 ? green : yellow;
            String xhair = Autopilot.dbg_crosshairOnEntity ? "YES" : "no";
            int xhairColor = Autopilot.dbg_crosshairOnEntity ? green : red;
            ctx.drawText(font,
                String.format("aim: %.1f deg  xhair: ", Autopilot.dbg_aimError),
                x, y, aimColor, true);
            ctx.drawText(font, xhair, x + 140, y, xhairColor, true);
            y += lineH;

            // Misses & combat stuck
            int missColor = Autopilot.dbg_consecutiveMisses >= 3 ? red : gray;
            int stuckColor = Autopilot.dbg_combatStuckTicks > 40 ? red
                : Autopilot.dbg_combatStuckTicks > 20 ? yellow : gray;
            ctx.drawText(font,
                String.format("misses: %d  combat_stuck: %d",
                    Autopilot.dbg_consecutiveMisses, Autopilot.dbg_combatStuckTicks),
                x, y, missColor, true);
            y += lineH;
        }

        // ── Movement ──
        String moveStr = "";
        if (Autopilot.dbg_forward) moveStr += "W ";
        if (Autopilot.dbg_sprint) moveStr += "SPRINT ";
        if (Autopilot.dbg_jump) moveStr += "JUMP ";
        if (Autopilot.dbg_strafeDir < 0) moveStr += "A ";
        if (Autopilot.dbg_strafeDir > 0) moveStr += "D ";
        if (moveStr.isEmpty()) moveStr = "(idle)";
        ctx.drawText(font, "keys: " + moveStr.trim(), x, y, white, true);
        y += lineH;

        // ── Yaw/Pitch ──
        double yawErr = Math.abs(Autopilot.dbg_goalYaw - Autopilot.dbg_currentYaw);
        if (yawErr > 180) yawErr = 360 - yawErr;
        double pitchErr = Math.abs(Autopilot.dbg_goalPitchTick - Autopilot.dbg_currentPitch);
        int yawColor = yawErr > 30 ? red : yawErr > 10 ? yellow : gray;
        ctx.drawText(font,
            String.format("yaw: %.0f -> %.0f (err %.0f)  pitch: %.0f -> %.0f (err %.0f)",
                Autopilot.dbg_currentYaw, Autopilot.dbg_goalYaw, yawErr,
                Autopilot.dbg_currentPitch, Autopilot.dbg_goalPitchTick, pitchErr),
            x, y, yawColor, true);
        y += lineH;

        // ── Path ──
        if (Autopilot.dbg_pathLen > 0) {
            int pathColor = Autopilot.dbg_pathBlocked ? red : cyan;
            String blockedStr = Autopilot.dbg_pathBlocked ? " BLOCKED" : "";
            ctx.drawText(font,
                String.format("path: %d nodes  wp: %d  dWp: %.1f  dGoal: %.1f%s",
                    Autopilot.dbg_pathLen, Autopilot.dbg_wpIndex,
                    Autopilot.dbg_distToWp, Autopilot.dbg_distToGoal, blockedStr),
                x, y, pathColor, true);
        } else {
            ctx.drawText(font, "path: none", x, y, gray, true);
        }
        y += lineH;

        // ── Waypoint position ──
        if (Autopilot.dbg_pathLen > 0) {
            ctx.drawText(font,
                String.format("wp -> %.0f %.0f %.0f",
                    Autopilot.dbg_wpX, Autopilot.dbg_wpY, Autopilot.dbg_wpZ),
                x, y, gray, true);
            y += lineH;
        }

        // ── Stuck ──
        if (Autopilot.dbg_stuckTicks > 5) {
            ctx.drawText(font,
                String.format("STUCK: %d ticks", Autopilot.dbg_stuckTicks),
                x, y, red, true);
            y += lineH;
        }

        // ── Repath ──
        if (Autopilot.dbg_lastRepathReason != null) {
            long ago = System.currentTimeMillis() - Autopilot.dbg_lastRepathMs;
            if (ago < 5000) { // show for 5 seconds after repath
                ctx.drawText(font,
                    String.format("repath: %s (%.1fs ago)", Autopilot.dbg_lastRepathReason, ago / 1000.0),
                    x, y, yellow, true);
                y += lineH;
            }
        }

        // ── Position ──
        ctx.drawText(font,
            String.format("pos: %.0f %.0f %.0f",
                Autopilot.dbg_playerX, Autopilot.dbg_playerY, Autopilot.dbg_playerZ),
            x, y, gray, true);
        y += lineH;

        // ── CSV status ──
        if (csvEnabled) {
            ctx.drawText(font,
                String.format("AP CSV: %d ticks", csvTick),
                x, y, green, true);
        }
    }

    // ── CSV Logger ────────────────────────────────────────────

    private static void startCsv() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            File dir = mc.runDirectory;
            File file = new File(dir, "autopilot_debug.csv");
            csvWriter = new PrintWriter(new FileWriter(file, false));
            csvWriter.println("tick,time_ms,"
                + "px,py,pz,mx,my,mz,mob_dist,"
                + "in_combat,direct_pursuit,aim_error,crosshair_entity,"
                + "attack,misses,combat_stuck,"
                + "forward,sprint,jump,strafe,"
                + "path_len,wp_index,dist_wp,dist_goal,stuck,"
                + "wp_x,wp_y,wp_z,goal_yaw,current_yaw,"
                + "goal_pitch,current_pitch,path_blocked,target");
            csvEnabled = true;
            csvTick = 0;
            csvStartNano = System.nanoTime();
            System.out.println("[AutopilotDebug] CSV started: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[AutopilotDebug] CSV start failed: " + e.getMessage());
        }
    }

    private static void stopCsv() {
        csvEnabled = false;
        if (csvWriter != null) {
            csvWriter.flush();
            csvWriter.close();
            csvWriter = null;
            System.out.println("[AutopilotDebug] CSV stopped. " + csvTick + " ticks.");
        }
    }
}
