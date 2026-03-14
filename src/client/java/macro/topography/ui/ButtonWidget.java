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

        int bg = hovered && enabled ? applyAlpha(hoverColor, alpha) : applyAlpha(fillColor, alpha);
        int txt = applyAlpha(textColor, alpha);
        int brd = applyAlpha(borderColor, alpha);

        if (!enabled) {
            bg = applyAlpha(0xFF1A1A1E, alpha);
            txt = applyAlpha(0xFF43464F, alpha);
            brd = applyAlpha(0xFF1E1E24, alpha);
        }

        switch (style) {
            case FILLED -> S.drawRounded(ctx, x, y, w, h, radius, bg);
            case OUTLINED -> S.drawOutlinedRounded(ctx, x, y, w, h, radius, bg, brd, 1f);
            case GHOST -> {
                if (hovered) S.drawRounded(ctx, x, y, w, h, radius, bg);
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
