package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

public class ButtonWidget extends Widget {

    public enum Style { FILLED, OUTLINED, GHOST }

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private final TopographySmoothTextRenderer font;
    private String label;
    private Style style;
    private int fillColor;
    private int hoverColor;
    private int textColor;
    private int borderColor;
    private float radius = 6;
    private Runnable onClick;

    public ButtonWidget(TopographySmoothTextRenderer font, String label, Style style) {
        this.font = font;
        this.label = label;
        this.style = style;
    }

    public ButtonWidget setColors(int fill, int hover, int text) {
        this.fillColor = fill;
        this.hoverColor = hover;
        this.textColor = text;
        return this;
    }

    public ButtonWidget setBorderColor(int border) {
        this.borderColor = border;
        return this;
    }

    public ButtonWidget setRadius(float r) {
        this.radius = r;
        return this;
    }

    public ButtonWidget setOnClick(Runnable action) {
        this.onClick = action;
        return this;
    }

    public ButtonWidget setLabel(String label) {
        this.label = label;
        return this;
    }

    public ButtonWidget setStyle(Style style) {
        this.style = style;
        return this;
    }

    public ButtonWidget setFillColor(int c) { this.fillColor = c; return this; }
    public ButtonWidget setHoverColor(int c) { this.hoverColor = c; return this; }
    public ButtonWidget setTextColor(int c) { this.textColor = c; return this; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;

        int bg, txt, brd;

        if (!enabled) {
            bg = applyAlpha(0xFF1A1A1E, alpha);
            txt = applyAlpha(0xFF43464F, alpha);
            brd = applyAlpha(0xFF1E1E24, alpha);
        } else {
            // Smooth lerp between normal and hover colors
            bg = applyAlpha(TopographySurfaceRenderer.lerpColor(fillColor, hoverColor, hoverProgress), alpha);
            txt = applyAlpha(textColor, alpha);
            brd = applyAlpha(TopographySurfaceRenderer.lerpColor(borderColor,
                    TopographySurfaceRenderer.brighten(borderColor, 15), hoverProgress), alpha);

            // Press darkening
            if (pressProgress > 0.01f) {
                bg = TopographySurfaceRenderer.lerpColor(bg,
                        TopographySurfaceRenderer.brighten(bg, -25), pressProgress);
            }
        }

        switch (style) {
            case FILLED -> {
                // Shadow (shrinks on press — sink effect)
                float btnSpread = 4 * (1f - pressProgress * 0.7f);
                int btnShadowA = (int) ((12 * (1f - pressProgress * 0.5f)) * alpha / 255f);
                S.drawShadow(ctx, x, y, w, h, radius, Math.round(btnSpread),
                        TopographySurfaceRenderer.withAlpha(0xFF000000, btnShadowA));

                // Glow on hover
                if (hoverProgress > 0.01f && enabled) {
                    int glowAlpha = (int) (35 * hoverProgress * alpha / 255f);
                    S.drawGlow(ctx, x, y, w, h, radius, 8,
                            TopographySurfaceRenderer.withAlpha(fillColor, glowAlpha));
                }

                // Base fill
                S.drawRounded(ctx, x, y, w, h, radius, bg);

                // Gradient shine (convex — lighter top, fades on press)
                float shineFade = 1f - pressProgress * 0.8f;
                int shineA = (int) (10 * shineFade * alpha / 255f);
                if (shineA > 0) {
                    S.drawGradientRounded(ctx, x + 1, y + 1, w - 2, (h - 2) * 0.5f,
                            Math.max(0, radius - 1),
                            TopographySurfaceRenderer.withAlpha(0xFFFFFF, shineA),
                            TopographySurfaceRenderer.withAlpha(0xFFFFFF, 0), 1f);
                }

                // Top edge highlight (disappears on press)
                float hlInset = Math.max(3, radius);
                int hlA = (int) (12 * shineFade * alpha / 255f);
                if (hlA > 0) {
                    S.drawRounded(ctx, x + hlInset, y + 1, w - hlInset * 2, 1, 0,
                            TopographySurfaceRenderer.withAlpha(0xFFFFFF, hlA));
                }
            }
            case OUTLINED -> S.drawOutlinedRounded(ctx, x, y, w, h, radius, bg, brd, 1f);
            case GHOST -> {
                if (hoverProgress > 0.01f) {
                    int ghostAlpha = (int) (alpha * 0.2f * hoverProgress);
                    S.drawRounded(ctx, x, y, w, h, radius,
                            TopographySurfaceRenderer.withAlpha(fillColor, ghostAlpha));
                }
            }
        }

        float textY = y + (h - font.lineHeight()) / 2f;
        font.drawCentered(ctx, label, x + w / 2, textY, txt);
    }

    @Override
    protected void onClicked() {
        if (onClick != null && enabled) onClick.run();
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
