package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ToggleWidget extends Widget {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private final TopographySmoothTextRenderer font;
    private final String label;
    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;

    private int onColor = 0xFF49D28B;
    private int offColor = 0xFFFF4455;
    private int labelColor = 0xFF6B6F80;

    public ToggleWidget(TopographySmoothTextRenderer font, String label,
                        BooleanSupplier getter, Consumer<Boolean> setter) {
        this.font = font;
        this.label = label;
        this.getter = getter;
        this.setter = setter;
        this.h = font.lineHeight() + 4;
    }

    public ToggleWidget setColors(int on, int off, int label) {
        this.onColor = on;
        this.offColor = off;
        this.labelColor = label;
        return this;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        boolean on = getter.getAsBoolean();

        int lblCol = applyAlpha(labelColor, alpha);
        font.draw(ctx, label, x, y + 2, lblCol);

        float valueX = x + font.width(label);
        String valueText = on ? "[ON]" : "[OFF]";
        int valueCol = applyAlpha(on ? onColor : offColor, alpha);
        if (hovered) {
            valueCol = applyAlpha(brighten(on ? onColor : offColor), alpha);
        }
        font.draw(ctx, valueText, valueX, y + 2, valueCol);

        // Update clickable width to cover full label + value
        this.w = font.width(label) + font.width(valueText);
    }

    @Override
    protected void onClicked() {
        setter.accept(!getter.getAsBoolean());
    }

    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 30);
        int b = Math.min(255, (color & 0xFF) + 30);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
