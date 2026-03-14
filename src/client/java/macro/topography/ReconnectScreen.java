package macro.topography;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;

import java.awt.Font;

/**
 * Replaces DisconnectedScreen when auto-reconnect is active.
 * Shows kick reason, countdown bar, attempt counter, and cancel button.
 */
public class ReconnectScreen extends Screen {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();
    private static final TopographySmoothTextRenderer TITLE_FONT =
            new TopographySmoothTextRenderer("Inter", 18, Font.BOLD);
    private static final TopographySmoothTextRenderer BODY_FONT =
            new TopographySmoothTextRenderer("Inter", 12, Font.PLAIN);
    private static final TopographySmoothTextRenderer SMALL_FONT =
            new TopographySmoothTextRenderer("Inter", 10, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL_FONT =
            new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);

    // Colors
    private static final int BG          = 0xF00C0C10;
    private static final int CARD_BG     = 0xFF141419;
    private static final int CARD_BORDER = 0xFF1E1E26;
    private static final int ACCENT      = 0xFF818CF8;
    private static final int GREEN       = 0xFF34D399;
    private static final int RED         = 0xFFEF4444;
    private static final int TEXT_PRI    = 0xFFE8E8F0;
    private static final int TEXT_DIM    = 0xFF6E7388;
    private static final int TEXT_SEC    = 0xFF6B6F80;
    private static final int BAR_BG     = 0xFF1A1A24;
    private static final int BTN_BG     = 0xFF1A1A24;
    private static final int BTN_HOVER  = 0xFF242430;

    private final String serverAddress;
    private final String reason;
    private final int attempt;
    private final int maxAttempts;
    private final boolean gaveUp;

    private int countdown;
    private int totalDelay;
    private String status;

    // Cancel button hover
    private boolean cancelHovered = false;

    public ReconnectScreen(String serverAddress, String reason,
                           int countdown, int totalDelay,
                           int attempt, int maxAttempts, boolean gaveUp) {
        super(Text.literal("Reconnecting"));
        this.serverAddress = serverAddress;
        this.reason = reason;
        this.countdown = countdown;
        this.totalDelay = totalDelay;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.gaveUp = gaveUp;
    }

    public void updateCountdown(int countdown, int totalDelay) {
        this.countdown = countdown;
        this.totalDelay = totalDelay;
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Full-screen dark background
        ctx.fill(0, 0, width, height, BG);

        float cardW = Math.min(360, width * 0.85f);
        float cardH = gaveUp ? 160 : 200;
        float cx = (width - cardW) / 2f;
        float cy = (height - cardH) / 2f;
        float pad = 24;
        float radius = 14;

        // Card shadow
        S.drawShadow(ctx, cx, cy, cardW, cardH, radius, 6,
                TopographySurfaceRenderer.withAlpha(0xFF000000, 40));

        // Card background + border
        S.drawOutlinedRounded(ctx, cx, cy, cardW, cardH, radius,
                CARD_BG, CARD_BORDER, 1f);

        float textX = cx + pad;
        float innerW = cardW - pad * 2;
        float ty = cy + pad;

        if (gaveUp) {
            // ── GAVE UP ─────────────────────────────────────────
            TITLE_FONT.draw(ctx, "Reconnect Failed", textX, ty, RED);
            ty += TITLE_FONT.lineHeight() + 10;

            BODY_FONT.draw(ctx, "Failed after " + attempt + " attempts.",
                    textX, ty, TEXT_DIM);
            ty += BODY_FONT.lineHeight() + 6;

            if (reason != null && !reason.isEmpty()) {
                String displayReason = SMALL_FONT.ellipsize(reason, (int) innerW);
                SMALL_FONT.draw(ctx, displayReason, textX, ty, TEXT_SEC);
            }

            // Back button
            renderBackButton(ctx, cx, cy, cardW, cardH, pad, radius, mouseX, mouseY);
        } else {
            // ── RECONNECTING ────────────────────────────────────
            TITLE_FONT.draw(ctx, "Reconnecting...", textX, ty, ACCENT);
            ty += TITLE_FONT.lineHeight() + 8;

            // Reason
            if (reason != null && !reason.isEmpty()) {
                String displayReason = BODY_FONT.ellipsize(reason, (int) innerW);
                BODY_FONT.draw(ctx, displayReason, textX, ty, TEXT_DIM);
                ty += BODY_FONT.lineHeight() + 6;
            }

            // Server + attempt
            String attemptText = "Attempt " + attempt + "/" + maxAttempts;
            if (serverAddress != null) {
                attemptText += "  •  " + serverAddress;
            }
            SMALL_FONT.draw(ctx, SMALL_FONT.ellipsize(attemptText, (int) innerW),
                    textX, ty, TEXT_SEC);
            ty += SMALL_FONT.lineHeight() + 14;

            // Status + countdown
            int secsLeft = Math.max(1, (countdown + 19) / 20);
            String statusLabel = (status != null && !status.isEmpty()) ? status : "Connecting...";
            SMALL_FONT.draw(ctx, SMALL_FONT.ellipsize(statusLabel, (int)(innerW - 40)),
                    textX, ty, TEXT_SEC);
            LABEL_FONT.drawRight(ctx, secsLeft + "s",
                    cx + cardW - pad, ty, TEXT_PRI);
            ty += LABEL_FONT.lineHeight() + 6;

            // Progress bar
            float barH = 4;
            float barRadius = 2;
            float progress = totalDelay > 0
                    ? 1f - (float) countdown / totalDelay : 0f;
            progress = Math.max(0f, Math.min(1f, progress));

            // Bar background
            S.drawRounded(ctx, textX, ty, innerW, barH, barRadius, BAR_BG);
            // Bar fill
            if (progress > 0.01f) {
                float fillW = Math.max(barH, innerW * progress);
                S.drawRounded(ctx, textX, ty, fillW, barH, barRadius, GREEN);
            }
            ty += barH + 16;

            // Cancel button
            renderCancelButton(ctx, cx, cy, cardW, cardH, pad, radius, mouseX, mouseY);
        }
    }

    private void renderCancelButton(DrawContext ctx, float cx, float cy,
                                     float cardW, float cardH, float pad, float radius,
                                     int mouseX, int mouseY) {
        float btnW = 80;
        float btnH = 28;
        float btnX = cx + cardW - pad - btnW;
        float btnY = cy + cardH - pad - btnH;
        float btnR = 8;

        cancelHovered = mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH;

        int bg = cancelHovered ? BTN_HOVER : BTN_BG;
        S.drawOutlinedRounded(ctx, btnX, btnY, btnW, btnH, btnR,
                bg, CARD_BORDER, 1f);
        LABEL_FONT.drawCentered(ctx, "CANCEL",
                btnX + btnW / 2f, btnY + (btnH - LABEL_FONT.lineHeight()) / 2f,
                cancelHovered ? TEXT_PRI : TEXT_SEC);
    }

    private void renderBackButton(DrawContext ctx, float cx, float cy,
                                   float cardW, float cardH, float pad, float radius,
                                   int mouseX, int mouseY) {
        float btnW = 120;
        float btnH = 28;
        float btnX = cx + (cardW - btnW) / 2f;
        float btnY = cy + cardH - pad - btnH;
        float btnR = 8;

        cancelHovered = mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH;

        int bg = cancelHovered ? BTN_HOVER : BTN_BG;
        S.drawOutlinedRounded(ctx, btnX, btnY, btnW, btnH, btnR,
                bg, CARD_BORDER, 1f);
        LABEL_FONT.drawCentered(ctx, "BACK TO MENU",
                btnX + btnW / 2f, btnY + (btnH - LABEL_FONT.lineHeight()) / 2f,
                cancelHovered ? TEXT_PRI : TEXT_SEC);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean bl) {
        if (click.buttonInfo().button() == 0 && cancelHovered) {
            ReconnectManager.cancel();
            client.setScreen(new MultiplayerScreen(new TitleScreen()));
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public void close() {
        ReconnectManager.cancel();
        client.setScreen(new MultiplayerScreen(new TitleScreen()));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
