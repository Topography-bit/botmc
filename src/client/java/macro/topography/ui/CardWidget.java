package macro.topography.ui;

import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.IntSupplier;

public class CardWidget extends WidgetContainer {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private int fillColor = 0xFF141418;
    private IntSupplier borderColorSupplier = () -> 0xFF1E1E24;
    private float radius = 8;
    private float borderWidth = 1f;
    private float padding = 14;

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

    public float innerX() { return x + padding; }
    public float innerY() { return y + padding; }
    public float innerW() { return w - padding * 2; }
    public float innerH() { return h - padding * 2; }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        int fill = applyAlpha(fillColor, alpha);
        int border = applyAlpha(borderColorSupplier.getAsInt(), alpha);
        S.drawOutlinedRounded(ctx, x, y, w, h, radius, fill, border, borderWidth);
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
