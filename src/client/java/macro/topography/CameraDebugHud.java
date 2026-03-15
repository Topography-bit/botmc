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
 * Camera debug tools: HUD overlay + CSV logger.
 *
 * HUD overlay (F7):  Shows real-time motor control state —
 *   saccade/spring mode, velocity, tremor, goal lag, etc.
 *
 * CSV logger (Shift+F7):  Per-frame telemetry to camera_debug.csv
 *   for offline analysis in Python (velocity profiles, FFT, etc.)
 */
public final class CameraDebugHud {

    private CameraDebugHud() {}

    private static boolean hudEnabled = false;
    private static boolean csvEnabled = false;
    private static PrintWriter csvWriter = null;
    private static long csvFrameCount = 0;
    private static long csvStartNano = 0;

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

    public static boolean isHudEnabled() { return hudEnabled; }
    public static boolean isCsvEnabled() { return csvEnabled; }

    public static void register() {
        HudRenderCallback.EVENT.register(CameraDebugHud::render);
    }

    // ── HUD Overlay ──────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!Autopilot.isEnabled()) return;

        // CSV logging runs every frame regardless of HUD visibility
        if (csvEnabled) {
            writeCsvFrame();
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
    }

    // ── CSV Logger ───────────────────────────────────────────────

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
        // Flush every 500 frames to avoid data loss
        if (csvFrameCount % 500 == 0) {
            csvWriter.flush();
        }
    }
}
