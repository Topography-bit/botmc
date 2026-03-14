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
    private static final TopographySmoothTextRenderer HEADING = new TopographySmoothTextRenderer("Inter", 14, Font.BOLD);
    private static final TopographySmoothTextRenderer BODY    = new TopographySmoothTextRenderer("Inter", 12, Font.PLAIN);
    private static final TopographySmoothTextRenderer SMALL   = new TopographySmoothTextRenderer("Inter", 10, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL   = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);

    private static final int GREEN       = 0xFF49D28B;
    private static final int RED         = 0xFFFF4455;
    private static final int YELLOW      = 0xFFD6953E;
    private static final int ACCENT      = 0xFF00C8FF;
    private static final int TEXT_PRIMARY   = 0xFFEAEAEF;
    private static final int TEXT_DIM       = 0xFF43464F;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;
    private static final int SEPARATOR      = 0xFF1A1A20;
    private static final int CARD_BORDER    = 0xFF1E1E24;

    private static final float BTN_H = 30;
    private static final float BTN_RADIUS = 6;

    private final String title;
    private final String subtitle;
    private final BooleanSupplier activeSupplier;
    private final Runnable toggleAction;
    private final Supplier<String> uptimeSupplier;
    private final Supplier<String> killsSupplier;

    private final ButtonWidget actionButton;
    private final KeybindWidget keybindWidget;

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

    @Override
    public void setBounds(float x, float y, float w, float h) {
        super.setBounds(x, y, w, h);
        layoutChildren();
    }

    private void layoutChildren() {
        float pad = getPadding();
        float btnW = w - pad * 2;
        float btnX = x + pad;
        float btnY = y + h - pad - BTN_H - SMALL.lineHeight() - 6;

        actionButton.setBounds(btnX, btnY, btnW, BTN_H);
        keybindWidget.setBounds(btnX, btnY + BTN_H + 6, btnW, SMALL.lineHeight() + 4);
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        boolean active = activeSupplier.getAsBoolean();
        boolean otherBusy = TopographyController.isAnyActive() && !active;

        // Card background with dynamic border
        int borderColor = active ? GREEN : CARD_BORDER;
        float borderW = active ? 1.5f : 1f;
        setBorderColorSupplier(() -> borderColor);
        setBorderWidth(borderW);
        super.renderSelf(ctx, mouseX, mouseY, alpha);

        float pad = getPadding();
        float innerW = w - pad * 2;
        float cy = y + pad;

        // Title (with ellipsis if card is narrow)
        String displayTitle = HEADING.ellipsize(title, (int) innerW);
        HEADING.draw(ctx, displayTitle, x + pad, cy, applyAlpha(TEXT_PRIMARY, alpha));
        cy += HEADING.lineHeight() + 2;

        // Subtitle (with ellipsis)
        String displaySubtitle = SMALL.ellipsize(subtitle, (int) innerW);
        SMALL.draw(ctx, displaySubtitle, x + pad, cy, applyAlpha(TEXT_DIM, alpha));
        cy += SMALL.lineHeight() + 10;

        // Separator
        S.drawRounded(ctx, x + pad, cy, innerW, 1, 0, applyAlpha(SEPARATOR, alpha));
        cy += 9;

        // Status
        int statusColor = active ? GREEN : (otherBusy ? YELLOW : TEXT_SECONDARY);
        String statusText = active ? "Running" : (otherBusy ? "Busy" : "Idle");

        // Pulsing dot
        int dotAlpha = alpha;
        if (active) {
            double pulse = (Math.sin(System.currentTimeMillis() / 400.0) + 1.0) / 2.0;
            dotAlpha = (int)(alpha * (0.4 + 0.6 * pulse));
        }
        S.drawRounded(ctx, x + pad, cy + 3, 6, 6, 3, applyAlpha(statusColor, dotAlpha));
        String statusLine = BODY.ellipsize("Status: " + statusText, (int)(innerW - 12));
        BODY.draw(ctx, statusLine, x + pad + 12, cy, applyAlpha(statusColor, alpha));
        cy += BODY.lineHeight() + 3;

        // Stats
        if (active) {
            String uptime = SMALL.ellipsize("Uptime: " + uptimeSupplier.get(), (int) innerW);
            SMALL.draw(ctx, uptime, x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
            cy += SMALL.lineHeight() + 2;
            String kills = SMALL.ellipsize("Kills: " + killsSupplier.get(), (int) innerW);
            SMALL.draw(ctx, kills, x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
        }

        // Update button appearance based on state
        updateButtonState(active, otherBusy);
    }

    private void updateButtonState(boolean active, boolean otherBusy) {
        if (active) {
            actionButton.setLabel("STOP");
            actionButton.setStyle(ButtonWidget.Style.FILLED);
            actionButton.setColors(RED, 0xFFCC3344, 0xFFFFFFFF);
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
            actionButton.setColors(ACCENT, 0xFF00DAFF, 0xFF0D0D0F);
            actionButton.setEnabled(true);
        }
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
