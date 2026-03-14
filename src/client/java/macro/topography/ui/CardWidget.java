package macro.topography.ui;

import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.IntSupplier;

public class CardWidget extends WidgetContainer {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private int fillColor = 0xFF141419;
    private IntSupplier borderColorSupplier = () -> 0xFF1E1E28;
    private float radius = 10;
    private float borderWidth = 1f;
    private float padding = 20;

    public CardWidget() {}

    public CardWidget setFillColor(int c) { this.fillColor = c; return this; }

    public CardWidget setBorderColor(int c) {
        this.borderColorSupplier = () -> c;
        return this;
    }

    public CardWidget setBorderColorSupplier(IntSupplier supplier) {
        this.borderColorSupplier = supplier;
        return this;
    }

    public CardWidget setRadius(float r) { this.radius = r; return this; }
    public CardWidget setBorderWidth(float w) { this.borderWidth = w; return this; }
    public CardWidget setPadding(float p) { this.padding = p; return this; }
    public float getPadding() { return padding; }
    public float getRadius() { return radius; }

    public float innerX() { return x + padding; }
    public float innerY() { return y + padding; }
    public float innerW() { return w - padding * 2; }
    public float innerH() { return h - padding * 2; }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        // ── Hover-responsive shadow (rest → elevated) ────────────
        float shadowSpread = 6 + 6 * hoverProgress;
        int shadowAlpha = (int) ((20 + 10 * hoverProgress) * alpha / 255f);
        S.drawShadow(ctx, x, y, w, h, radius, Math.round(shadowSpread),
                TopographySurfaceRenderer.withAlpha(0xFF000000, shadowAlpha));

        // ── Border (brightens on hover) ──────────────────────────
        int baseBorder = borderColorSupplier.getAsInt();
        int border = applyAlpha(TopographySurfaceRenderer.lerpColor(
                baseBorder, TopographySurfaceRenderer.brighten(baseBorder, 10),
                hoverProgress), alpha);

        // ── Card fill ────────────────────────────────────────────
        int fill = applyAlpha(fillColor, alpha);
        S.drawOutlinedRounded(ctx, x, y, w, h, radius, fill, border, borderWidth);

        // ── Gradient top (lighter top → standard, adds volume) ───
        int topBright = TopographySurfaceRenderer.brighten(fillColor, 6);
        S.drawGradientRounded(ctx, x + borderWidth, y + borderWidth,
                w - borderWidth * 2, (h - borderWidth * 2) * 0.4f,
                Math.max(0, radius - borderWidth),
                applyAlpha(topBright, alpha), fill, 1f);

        // ── Inner highlight (glass edge — double border top) ─────
        float hlInset = Math.max(4, radius);
        S.drawRounded(ctx, x + hlInset, y + borderWidth, w - hlInset * 2, 1, 0,
                TopographySurfaceRenderer.withAlpha(0xFFFFFF, (int) (5 * alpha / 255f)));

        // ── Inset shadow ─────────────────────────────────────────
        float innerR = Math.max(0, radius - borderWidth);
        S.drawInsetShadow(ctx, x + borderWidth, y + borderWidth,
                w - borderWidth * 2, h - borderWidth * 2,
                innerR, 3, applyAlpha(0xFF000000, alpha));
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
