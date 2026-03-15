package macro.topography;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Camera debug tools: HUD overlay + CSV loggers.
 *
 * HUD overlay (F7):  Shows real-time motor control state —
 *   saccade/spring mode, velocity, tremor, goal lag, etc.
 *
 * CSV logger (Shift+F7):  Per-frame telemetry to camera_debug.csv
 *   for offline analysis in Python (velocity profiles, FFT, etc.)
 *
 * Human logger (Ctrl+F7):  Records camera_human.csv with the same
 *   columns but from YOUR real mouse input. Run the same route
 *   manually, then compare the two CSVs to validate humanness.
 */
public final class CameraDebugHud {

    private CameraDebugHud() {}

    private static boolean hudEnabled = false;

    // ── Bot CSV logger ──
    private static boolean csvEnabled = false;
    private static PrintWriter csvWriter = null;
    private static long csvFrameCount = 0;
    private static long csvStartNano = 0;

    // ── Human CSV logger ──
    private static boolean humanCsvEnabled = false;
    private static PrintWriter humanCsvWriter = null;
    private static long humanCsvFrameCount = 0;
    private static long humanCsvStartNano = 0;
    private static float humanPrevYaw = 0;
    private static float humanPrevPitch = 0;
    private static float humanPrevYawVel = 0;
    private static float humanPrevPitchVel = 0;
    private static long  humanPrevNano = 0;

    // ── Public API ────────────────────────────────────────────────

    public static void toggleHud() {
        hudEnabled = !hudEnabled;
    }

    public static void toggleCsv() {
        if (csvEnabled) {
            stopCsv();
        } else {
            startCsv();
        }
    }

    public static void toggleHumanCsv() {
        if (humanCsvEnabled) {
            stopHumanCsv();
        } else {
            startHumanCsv();
        }
    }

    public static boolean isHudEnabled() { return hudEnabled; }
    public static boolean isCsvEnabled() { return csvEnabled; }
    public static boolean isHumanCsvEnabled() { return humanCsvEnabled; }

    public static void register() {
        HudRenderCallback.EVENT.register(CameraDebugHud::render);
    }

    // ── HUD Overlay ──────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        // Human CSV runs regardless of autopilot
        if (humanCsvEnabled) {
            writeHumanCsvFrame();
        }

        // Bot CSV only when autopilot is active
        if (Autopilot.isEnabled() && csvEnabled) {
            writeCsvFrame();
        }

        // HUD only when autopilot is active
        if (!Autopilot.isEnabled()) {
            // Show human CSV status even without autopilot
            if (humanCsvEnabled) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null && mc.textRenderer != null) {
                    ctx.drawText(mc.textRenderer,
                        String.format("[Human CSV] recording (%d frames) - Ctrl+F7 to stop",
                            humanCsvFrameCount),
                        4, 4, 0xFF55FF55, true);
                }
            }
            return;
        }

        if (!hudEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        TextRenderer font = mc.textRenderer;
        if (font == null) return;

        int x = 4;
        int y = 4;
        int lineH = 10;
        int white   = 0xFFFFFFFF;
        int green   = 0xFF55FF55;
        int yellow  = 0xFFFFFF55;
        int red     = 0xFFFF5555;
        int cyan    = 0xFF55FFFF;
        int gray    = 0xFFAAAAAA;

        // ── Mode ──
        String mode;
        int modeColor;
        if (Autopilot.dbg_saccadeActive) {
            mode = String.format("SACCADE  tau=%.2f", Autopilot.dbg_saccadeTau);
            modeColor = yellow;
        } else {
            mode = "SPRING";
            modeColor = green;
        }
        if (Autopilot.dbg_falling) {
            mode += "  [FALLING]";
            modeColor = red;
        }
        if (Autopilot.dbg_preEdge) {
            mode += String.format("  [PRE-EDGE %.0f%% d=%.1f]",
                Autopilot.dbg_preEdgeStrength * 100, Autopilot.dbg_preEdgeDist);
        }

        ctx.drawText(font, "[Camera] " + mode, x, y, modeColor, true);
        y += lineH;

        // ── Velocity ──
        double velTotal = Math.sqrt(
            Autopilot.dbg_yawVel * Autopilot.dbg_yawVel
            + Autopilot.dbg_pitchVel * Autopilot.dbg_pitchVel);
        ctx.drawText(font,
            String.format("vel: %.1f deg/s  (yaw %.1f  pitch %.1f)",
                velTotal, Autopilot.dbg_yawVel, Autopilot.dbg_pitchVel),
            x, y, white, true);
        y += lineH;

        // ── Error & goal lag ──
        double lagYaw = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
            (float)(Autopilot.dbg_goalYawRaw - Autopilot.dbg_goalYawSmooth)));
        double lagPitch = Math.abs(Autopilot.dbg_goalPitchRaw - Autopilot.dbg_goalPitchSmooth);
        ctx.drawText(font,
            String.format("error: %.1f deg  lag: %.1f / %.1f",
                Autopilot.dbg_totalError, lagYaw, lagPitch),
            x, y, cyan, true);
        y += lineH;

        // ── Tremor ──
        ctx.drawText(font,
            String.format("tremor: yaw %.3f  pitch %.3f",
                Autopilot.dbg_tremorYaw, Autopilot.dbg_tremorPitch),
            x, y, gray, true);
        y += lineH;

        // ── Omega scale + attention ──
        ctx.drawText(font,
            String.format("omega: %.2f  attn: %.2f%s",
                Autopilot.dbg_omegaScale, Autopilot.dbg_attention,
                Autopilot.dbg_inDwell ? String.format("  DWELL(%d)", Autopilot.dbg_correctionsLeft) : ""),
            x, y, gray, true);
        y += lineH;

        // ── Fatigue + breath ──
        ctx.drawText(font,
            String.format("fatigue: %.1f%%  breath: %.3f%s",
                Autopilot.dbg_fatigue * 100.0, Autopilot.dbg_breathOffset,
                Autopilot.dbg_mouseLift ? "  [MOUSE LIFT]" : ""),
            x, y, gray, true);
        y += lineH;

        // ── CSV status ──
        if (csvEnabled) {
            ctx.drawText(font,
                String.format("CSV: recording (%d frames)", csvFrameCount),
                x, y, green, true);
        } else {
            ctx.drawText(font, "CSV: off (Shift+F7 to start)", x, y, gray, true);
        }
        y += lineH;

        // ── Human CSV status ──
        if (humanCsvEnabled) {
            ctx.drawText(font,
                String.format("Human CSV: recording (%d frames)", humanCsvFrameCount),
                x, y, green, true);
        } else {
            ctx.drawText(font, "Human CSV: off (Ctrl+F7 to start)", x, y, gray, true);
        }
    }

    // ── Bot CSV Logger ──────────────────────────────────────────

    private static void startCsv() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            File dir = mc.runDirectory;
            File file = new File(dir, "camera_debug.csv");
            csvWriter = new PrintWriter(new FileWriter(file, false));
            csvWriter.println("time_ms,yaw,pitch,goal_yaw_raw,goal_pitch_raw,"
                + "goal_yaw_smooth,goal_pitch_smooth,yaw_vel,pitch_vel,"
                + "saccade,saccade_tau,tremor_yaw,tremor_pitch,"
                + "omega_scale,falling,error,attention,dwell,"
                + "fatigue,breath_offset,mouse_lift,"
                + "pre_edge,pre_edge_strength,pre_edge_dist");
            csvEnabled = true;
            csvFrameCount = 0;
            csvStartNano = System.nanoTime();
            System.out.println("[CameraDebug] CSV started: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[CameraDebug] Failed to start CSV: " + e.getMessage());
        }
    }

    private static void stopCsv() {
        csvEnabled = false;
        if (csvWriter != null) {
            csvWriter.flush();
            csvWriter.close();
            csvWriter = null;
            System.out.println("[CameraDebug] CSV stopped. " + csvFrameCount + " frames written.");
        }
    }

    private static void writeCsvFrame() {
        if (csvWriter == null) return;
        double timeMs = (System.nanoTime() - csvStartNano) / 1_000_000.0;
        csvWriter.printf("%.2f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f,%d,%.4f,%.6f,%.6f,%.3f,%d,%.4f,%.3f,%d,%.4f,%.4f,%d,%d,%.4f,%.2f%n",
            timeMs,
            Autopilot.dbg_springYaw,
            Autopilot.dbg_springPitch,
            Autopilot.dbg_goalYawRaw,
            Autopilot.dbg_goalPitchRaw,
            Autopilot.dbg_goalYawSmooth,
            Autopilot.dbg_goalPitchSmooth,
            Autopilot.dbg_yawVel,
            Autopilot.dbg_pitchVel,
            Autopilot.dbg_saccadeActive ? 1 : 0,
            Autopilot.dbg_saccadeTau,
            Autopilot.dbg_tremorYaw,
            Autopilot.dbg_tremorPitch,
            Autopilot.dbg_omegaScale,
            Autopilot.dbg_falling ? 1 : 0,
            Autopilot.dbg_totalError,
            Autopilot.dbg_attention,
            Autopilot.dbg_inDwell ? 1 : 0,
            Autopilot.dbg_fatigue,
            Autopilot.dbg_breathOffset,
            Autopilot.dbg_mouseLift ? 1 : 0,
            Autopilot.dbg_preEdge ? 1 : 0,
            Autopilot.dbg_preEdgeStrength,
            Autopilot.dbg_preEdgeDist);
        csvFrameCount++;
        if (csvFrameCount % 500 == 0) {
            csvWriter.flush();
        }
    }

    // ── Human CSV Logger ────────────────────────────────────────
    //    Records the same format from YOUR real mouse input.
    //    Columns that don't apply to human (tremor, saccade, etc.)
    //    are filled with 0. This lets the same analysis scripts
    //    compare bot vs human side-by-side.

    private static void startHumanCsv() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return;
            File dir = mc.runDirectory;
            File file = new File(dir, "camera_human.csv");
            humanCsvWriter = new PrintWriter(new FileWriter(file, false));
            humanCsvWriter.println("time_ms,yaw,pitch,goal_yaw_raw,goal_pitch_raw,"
                + "goal_yaw_smooth,goal_pitch_smooth,yaw_vel,pitch_vel,"
                + "saccade,saccade_tau,tremor_yaw,tremor_pitch,"
                + "omega_scale,falling,error,attention,dwell,"
                + "fatigue,breath_offset,mouse_lift,"
                + "pre_edge,pre_edge_strength,pre_edge_dist");
            humanCsvEnabled = true;
            humanCsvFrameCount = 0;
            humanCsvStartNano = System.nanoTime();
            humanPrevYaw = mc.player.getYaw();
            humanPrevPitch = mc.player.getPitch();
            humanPrevYawVel = 0;
            humanPrevPitchVel = 0;
            humanPrevNano = humanCsvStartNano;
            System.out.println("[CameraDebug] Human CSV started: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[CameraDebug] Failed to start human CSV: " + e.getMessage());
        }
    }

    private static void stopHumanCsv() {
        humanCsvEnabled = false;
        if (humanCsvWriter != null) {
            humanCsvWriter.flush();
            humanCsvWriter.close();
            humanCsvWriter = null;
            System.out.println("[CameraDebug] Human CSV stopped. " + humanCsvFrameCount + " frames written.");
        }
    }

    private static void writeHumanCsvFrame() {
        if (humanCsvWriter == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        long now = System.nanoTime();
        double timeMs = (now - humanCsvStartNano) / 1_000_000.0;
        double dtSec = (now - humanPrevNano) / 1_000_000_000.0;
        humanPrevNano = now;

        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();

        // Compute velocity from deltas
        float dyaw = yaw - humanPrevYaw;
        // Wrap yaw delta
        while (dyaw > 180) dyaw -= 360;
        while (dyaw < -180) dyaw += 360;
        float dpitch = pitch - humanPrevPitch;

        float yawVel = (dtSec > 0.0001) ? (float)(dyaw / dtSec) : humanPrevYawVel;
        float pitchVel = (dtSec > 0.0001) ? (float)(dpitch / dtSec) : humanPrevPitchVel;

        // Detect falling (same threshold as bot)
        boolean falling = !mc.player.isOnGround() && mc.player.getVelocity().y < -0.18;

        // Write same format — bot-specific columns filled with 0
        humanCsvWriter.printf("%.2f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f,%d,%.4f,%.6f,%.6f,%.3f,%d,%.4f,%.3f,%d,%.4f,%.4f,%d,%d,%.4f,%.2f%n",
            timeMs,
            (double) yaw,           // yaw
            (double) pitch,         // pitch
            (double) yaw,           // goal_yaw_raw = actual yaw (human IS the goal)
            (double) pitch,         // goal_pitch_raw
            (double) yaw,           // goal_yaw_smooth
            (double) pitch,         // goal_pitch_smooth
            (double) yawVel,        // yaw_vel
            (double) pitchVel,      // pitch_vel
            0,                      // saccade (not applicable)
            0.0,                    // saccade_tau
            0.0, 0.0,              // tremor_yaw, tremor_pitch
            1.0,                    // omega_scale
            falling ? 1 : 0,        // falling
            0.0,                    // error (always 0 for human)
            1.0,                    // attention
            0,                      // dwell
            0.0,                    // fatigue
            0.0,                    // breath_offset
            0,                      // mouse_lift
            0, 0.0, -1.0);         // pre_edge, strength, dist

        humanCsvFrameCount++;
        humanPrevYaw = yaw;
        humanPrevPitch = pitch;
        humanPrevYawVel = yawVel;
        humanPrevPitchVel = pitchVel;

        if (humanCsvFrameCount % 500 == 0) {
            humanCsvWriter.flush();
        }
    }
}
