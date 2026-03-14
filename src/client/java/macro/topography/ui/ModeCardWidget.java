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
    private static final int TEXT_DIM      = 0xFF6E7388;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;
    private static final int SEPARATOR     = 0xFF1C1C24;
    private static final int CARD_BORDER   = 0xFF1E1E26;

    private static final float BTN_H_DEFAULT = 36;
    private static final float BTN_H_COMPACT = 28;

    private final String title;
    private final String subtitle;
    private final BooleanSupplier activeSupplier;
    private final Runnable toggleAction;
    private final Supplier<String> uptimeSupplier;
    private final Supplier<String> killsSupplier;

    private final ButtonWidget actionButton;
    private final KeybindWidget keybindWidget;
    private int staggerIndex = 0;
    private long firstRenderNanos = 0;

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
        if (firstRenderNanos == 0) firstRenderNanos = System.nanoTime();

        float delayMs = staggerIndex * 60f;
        float elapsedMs = (System.nanoTime() - firstRenderNanos) / 1_000_000f - delayMs;
        float elapsed = Math.max(0f, Math.min(1f, elapsedMs / 300f));
        float inv = 1f - elapsed;
        float ease = 1f - inv * inv * inv;  // cubic ease-out

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
        float pad = h < 180 ? 12 : getPadding();
        float btnH = h < 180 ? BTN_H_COMPACT : BTN_H_DEFAULT;
        float gap = h < 180 ? 3 : 6;
        float btnW = w - pad * 2;
        float btnX = x + pad;
        float bindH = SMALL.lineHeight() + 4;
        float btnY = y + h - pad - btnH - bindH - gap;

        actionButton.setBounds(btnX, btnY, btnW, btnH);
        keybindWidget.setBounds(btnX, btnY + btnH + gap, btnW, bindH);
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        boolean active = activeSupplier.getAsBoolean();
        boolean otherBusy = TopographyController.isAnyActive() && !active;

        // ── Ambient glow ─────────────────────────────────────────
        if (active) {
            float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 1500.0));
            int innerGlowA = (int) (20 * pulse * alpha / 255f);
            S.drawGlow(ctx, x, y, w, h, getRadius(), 6,
                    TopographySurfaceRenderer.withAlpha(GREEN, innerGlowA));
            int ambientGlowA = (int) (8 * pulse * alpha / 255f);
            S.drawGlow(ctx, x, y, w, h, getRadius(), 12,
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

        // ── Compute available content zone (above button) ────────
        float contentTop = y + pad;
        float btnTop = actionButton.getY();
        float contentH = btnTop - contentTop - 4; // 4px margin above button

        // Decide what fits
        float titleH = HEADING.lineHeight();
        float subtitleH = SMALL.lineHeight();
        float sepH = 1;
        float statusH = BODY.lineHeight();
        float statsH = active ? (SMALL.lineHeight() + 4) * 2 : 0;

        // Calculate ideal total
        float idealGap1 = 6;   // after title
        float idealGap2 = 14;  // after subtitle
        float idealGap3 = 14;  // after separator
        float idealGap4 = 10;  // after status
        float idealTotal = titleH + idealGap1 + subtitleH + idealGap2 + sepH + idealGap3 + statusH + idealGap4 + statsH;

        // Scale gaps if too tight
        boolean showSep = true;
        boolean showStats = active;
        float gap1 = idealGap1, gap2 = idealGap2, gap3 = idealGap3, gap4 = idealGap4;

        if (idealTotal > contentH) {
            // Shrink gaps proportionally, but keep minimum spacing
            float shrink = Math.min(1f, Math.max(0f, contentH / idealTotal));
            gap1 = Math.max(2, idealGap1 * shrink);
            gap2 = Math.max(4, idealGap2 * shrink);
            gap3 = Math.max(4, idealGap3 * shrink);
            gap4 = Math.max(4, idealGap4 * shrink);
            float shrunkTotal = titleH + gap1 + subtitleH + gap2 + sepH + gap3 + statusH + gap4 + statsH;

            if (shrunkTotal > contentH) {
                // Remove separator
                showSep = false;
                gap2 = 4;
                gap3 = 0;
                float noSepTotal = titleH + gap1 + subtitleH + gap2 + statusH + gap4 + statsH;
                if (noSepTotal > contentH && active) {
                    // Remove stats
                    showStats = false;
                }
            }
        }

        float cy = contentTop;

        // ── Title ────────────────────────────────────────────────
        String displayTitle = HEADING.ellipsize(title, (int) innerW);
        HEADING.draw(ctx, displayTitle, x + pad, cy, applyAlpha(TEXT_PRIMARY, alpha));
        cy += titleH + gap1;

        // ── Subtitle ─────────────────────────────────────────────
        String displaySubtitle = SMALL.ellipsize(subtitle, (int) innerW);
        SMALL.draw(ctx, displaySubtitle, x + pad, cy, applyAlpha(TEXT_DIM, alpha));
        cy += subtitleH + gap2;

        // ── Separator ────────────────────────────────────────────
        if (showSep) {
            int sepBase = active
                    ? TopographySurfaceRenderer.lerpColor(SEPARATOR, GREEN_DIM, 0.4f)
                    : SEPARATOR;
            S.drawRoundedFast(ctx, x + pad, cy, innerW, 1, 0,
                    applyAlpha(sepBase, (int) (alpha * 0.7f)));
            cy += sepH + gap3;
        }

        // ── Status indicator ─────────────────────────────────────
        if (cy + statusH <= btnTop) {
            int statusColor = active ? GREEN : (otherBusy ? YELLOW : TEXT_SECONDARY);
            String statusText = active ? "Running" : (otherBusy ? "Busy" : "Idle");

            float dotX = x + pad;
            float dotY = cy + 4;
            float dotSize = 7;
            float dotR = dotSize / 2f;

            if (active) {
                double pulse = (Math.sin(System.currentTimeMillis() / 400.0) + 1.0) / 2.0;
                int ringAlpha = (int) (30 * pulse * alpha / 255f);
                S.drawGlow(ctx, dotX - 1, dotY - 1, dotSize + 2, dotSize + 2, dotR + 1, 3,
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
            cy += statusH + gap4;

            // ── Stats ────────────────────────────────────────────
            if (showStats && cy + SMALL.lineHeight() <= btnTop) {
                float labelW = SMALL.width("Uptime: ");
                SMALL.draw(ctx, "Uptime: ", x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
                MONO.draw(ctx, MONO.ellipsize(uptimeSupplier.get(), (int) (innerW - labelW)),
                        x + pad + labelW, cy, applyAlpha(TEXT_SECONDARY, alpha));
                cy += SMALL.lineHeight() + 4;

                if (cy + SMALL.lineHeight() <= btnTop) {
                    float killsLabelW = SMALL.width("Kills: ");
                    SMALL.draw(ctx, "Kills: ", x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
                    MONO.draw(ctx, MONO.ellipsize(killsSupplier.get(), (int) (innerW - killsLabelW)),
                            x + pad + killsLabelW, cy, applyAlpha(TEXT_SECONDARY, alpha));
                }
            }
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
