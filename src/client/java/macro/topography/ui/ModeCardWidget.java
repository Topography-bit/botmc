package macro.topography.ui;

import macro.topography.TopographyController;
import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.awt.Font;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ModeCardWidget extends CardWidget {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();
    private static final TopographySmoothTextRenderer HEADING = new TopographySmoothTextRenderer("Inter", 16, Font.BOLD);
    private static final TopographySmoothTextRenderer BODY    = new TopographySmoothTextRenderer("Inter", 12, Font.PLAIN);
    private static final TopographySmoothTextRenderer SMALL   = new TopographySmoothTextRenderer("Inter", 11, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL   = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);
    private static final TopographySmoothTextRenderer MONO    = new TopographySmoothTextRenderer("Consolas", 11, Font.PLAIN);

    // ── Palette ────────────────────────────────────────────────
    private static final int GREEN         = 0xFF34D399;
    private static final int GREEN_DIM     = 0xFF1A7A52;
    private static final int RED           = 0xFFEF4444;
    private static final int RED_HOVER     = 0xFFF87171;
    private static final int YELLOW        = 0xFFD6953E;
    private static final int ACCENT        = 0xFF818CF8;
    private static final int ACCENT_HOVER  = 0xFF9BA3FF;
    private static final int TEXT_PRIMARY  = 0xFFE8E8F0;
    private static final int TEXT_DIM      = 0xFF3E4150;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;
    private static final int SEPARATOR     = 0xFF1C1C24;
    private static final int CARD_BORDER   = 0xFF1E1E26;

    private static final float BTN_H = 36;

    private final String title;
    private final String subtitle;
    private final BooleanSupplier activeSupplier;
    private final Runnable toggleAction;
    private final Supplier<String> uptimeSupplier;
    private final Supplier<String> killsSupplier;

    private final ButtonWidget actionButton;
    private final KeybindWidget keybindWidget;
    private int staggerIndex = 0;
    private long firstRenderTime = 0;

    public ModeCardWidget(String title, String subtitle,
                          BooleanSupplier activeSupplier,
                          Runnable toggleAction,
                          Supplier<String> uptimeSupplier,
                          Supplier<String> killsSupplier,
                          KeybindWidget keybindWidget) {
        this.title = title;
        this.subtitle = subtitle;
        this.activeSupplier = activeSupplier;
        this.toggleAction = toggleAction;
        this.uptimeSupplier = uptimeSupplier;
        this.killsSupplier = killsSupplier;
        this.keybindWidget = keybindWidget;

        this.actionButton = new ButtonWidget(LABEL, "LAUNCH", ButtonWidget.Style.FILLED);
        this.actionButton.setOnClick(toggleAction);

        add(actionButton);
        add(keybindWidget);
    }

    public KeybindWidget getKeybindWidget() {
        return keybindWidget;
    }

    public void setStaggerIndex(int index) {
        this.staggerIndex = index;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        if (firstRenderTime == 0) firstRenderTime = System.currentTimeMillis();

        long delay = staggerIndex * 60L;
        float elapsed = (System.currentTimeMillis() - firstRenderTime - delay) / 300f;
        float ease = Math.max(0f, Math.min(1f, elapsed));
        ease = 1f - (1f - ease) * (1f - ease);

        if (ease <= 0f) return;

        int staggerAlpha = (int) (alpha * ease);
        super.render(ctx, mouseX, mouseY, staggerAlpha);
    }

    @Override
    public void setBounds(float x, float y, float w, float h) {
        super.setBounds(x, y, w, h);
        layoutChildren();
    }

    private void layoutChildren() {
        float pad = getPadding();
        float btnW = w - pad * 2;
        float btnX = x + pad;
        float btnY = y + h - pad - BTN_H - SMALL.lineHeight() - 8;

        actionButton.setBounds(btnX, btnY, btnW, BTN_H);
        keybindWidget.setBounds(btnX, btnY + BTN_H + 8, btnW, SMALL.lineHeight() + 4);
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        boolean active = activeSupplier.getAsBoolean();
        boolean otherBusy = TopographyController.isAnyActive() && !active;

        // ── Ambient glow ─────────────────────────────────────────
        if (active) {
            float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 1500.0));
            int innerGlowA = (int) (25 * pulse * alpha / 255f);
            S.drawGlow(ctx, x, y, w, h, getRadius(), 12,
                    TopographySurfaceRenderer.withAlpha(GREEN, innerGlowA));
            int ambientGlowA = (int) (10 * pulse * alpha / 255f);
            S.drawGlow(ctx, x, y, w, h, getRadius(), 28,
                    TopographySurfaceRenderer.withAlpha(GREEN, ambientGlowA));
        }

        // Card border
        int borderColor = active ? GREEN : CARD_BORDER;
        float borderW = active ? 1.5f : 1f;
        setBorderColorSupplier(() -> borderColor);
        setBorderWidth(borderW);
        super.renderSelf(ctx, mouseX, mouseY, alpha);

        float pad = getPadding();
        float innerW = w - pad * 2;
        float cy = y + pad;

        // ── Title ────────────────────────────────────────────────
        String displayTitle = HEADING.ellipsize(title, (int) innerW);
        HEADING.draw(ctx, displayTitle, x + pad, cy, applyAlpha(TEXT_PRIMARY, alpha));
        cy += HEADING.lineHeight() + 4;

        // ── Subtitle ─────────────────────────────────────────────
        String displaySubtitle = SMALL.ellipsize(subtitle, (int) innerW);
        SMALL.draw(ctx, displaySubtitle, x + pad, cy, applyAlpha(TEXT_DIM, alpha));
        cy += SMALL.lineHeight() + 16;

        // ── Separator ────────────────────────────────────────────
        int sepBase = active
                ? TopographySurfaceRenderer.lerpColor(SEPARATOR, GREEN_DIM, 0.4f)
                : SEPARATOR;
        int sepSegs = 8;
        float sepSegW = innerW / sepSegs;
        float sepCenter = sepSegs / 2f;
        for (int i = 0; i < sepSegs; i++) {
            float distFromCenter = Math.abs(i + 0.5f - sepCenter) / sepCenter;
            float fade = 1f - distFromCenter * distFromCenter;
            int segAlpha = (int) (alpha * fade);
            S.drawRounded(ctx, x + pad + i * sepSegW, cy, sepSegW + 1, 1, 0,
                    applyAlpha(sepBase, segAlpha));
        }
        cy += 16;

        // ── Status indicator ─────────────────────────────────────
        int statusColor = active ? GREEN : (otherBusy ? YELLOW : TEXT_SECONDARY);
        String statusText = active ? "Running" : (otherBusy ? "Busy" : "Idle");

        float dotX = x + pad;
        float dotY = cy + 4;
        float dotSize = 7;
        float dotR = dotSize / 2f;

        if (active) {
            double pulse = (Math.sin(System.currentTimeMillis() / 400.0) + 1.0) / 2.0;
            int ringAlpha = (int) (30 * pulse * alpha / 255f);
            S.drawGlow(ctx, dotX - 1, dotY - 1, dotSize + 2, dotSize + 2, dotR + 1, 5,
                    TopographySurfaceRenderer.withAlpha(statusColor, ringAlpha));
            int dotAlpha = (int) (alpha * (0.5 + 0.5 * pulse));
            S.drawRounded(ctx, dotX, dotY, dotSize, dotSize, dotR, applyAlpha(statusColor, dotAlpha));
            int hlA = (int) (alpha * 0.20f);
            S.drawRounded(ctx, dotX + 1, dotY + 1, 2, 2, 1,
                    TopographySurfaceRenderer.withAlpha(0xFFFFFF, hlA));
        } else if (otherBusy) {
            double flicker = (Math.sin(System.currentTimeMillis() / 100.0) + 1.0) / 2.0;
            int dotAlpha = (int) (alpha * (0.3 + 0.7 * flicker));
            S.drawRounded(ctx, dotX, dotY, dotSize, dotSize, dotR, applyAlpha(statusColor, dotAlpha));
        } else {
            int dimBorder = applyAlpha(0xFF1A1A22, alpha);
            S.drawOutlinedRounded(ctx, dotX, dotY, dotSize, dotSize, dotR,
                    applyAlpha(TopographySurfaceRenderer.brighten(statusColor, -30), alpha),
                    dimBorder, 1f);
        }

        String statusLine = BODY.ellipsize("Status: " + statusText, (int) (innerW - 16));
        BODY.draw(ctx, statusLine, x + pad + 14, cy, applyAlpha(statusColor, alpha));
        cy += BODY.lineHeight() + 8;

        // ── Stats ────────────────────────────────────────────────
        if (active) {
            float labelW = SMALL.width("Uptime: ");
            SMALL.draw(ctx, "Uptime: ", x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
            MONO.draw(ctx, MONO.ellipsize(uptimeSupplier.get(), (int) (innerW - labelW)),
                    x + pad + labelW, cy, applyAlpha(TEXT_SECONDARY, alpha));
            cy += SMALL.lineHeight() + 4;

            float killsLabelW = SMALL.width("Kills: ");
            SMALL.draw(ctx, "Kills: ", x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
            MONO.draw(ctx, MONO.ellipsize(killsSupplier.get(), (int) (innerW - killsLabelW)),
                    x + pad + killsLabelW, cy, applyAlpha(TEXT_SECONDARY, alpha));
        }

        updateButtonState(active, otherBusy);
    }

    private void updateButtonState(boolean active, boolean otherBusy) {
        if (active) {
            actionButton.setLabel("STOP");
            actionButton.setStyle(ButtonWidget.Style.FILLED);
            actionButton.setColors(RED, RED_HOVER, 0xFFFFFFFF);
            actionButton.setEnabled(true);
        } else if (otherBusy) {
            actionButton.setLabel("LAUNCH");
            actionButton.setStyle(ButtonWidget.Style.OUTLINED);
            actionButton.setBorderColor(CARD_BORDER);
            actionButton.setColors(0xFF1A1A1E, 0xFF1A1A1E, TEXT_DIM);
            actionButton.setEnabled(false);
        } else {
            actionButton.setLabel("LAUNCH");
            actionButton.setStyle(ButtonWidget.Style.FILLED);
            actionButton.setColors(ACCENT, ACCENT_HOVER, 0xFF0D0D10);
            actionButton.setEnabled(true);
        }
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
