package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class LabelWidget extends Widget {

    public enum Align { LEFT, CENTER, RIGHT }

    private final TopographySmoothTextRenderer font;
    private Supplier<String> textSupplier;
    private IntSupplier colorSupplier;
    private Align align = Align.LEFT;
    private boolean ellipsis = false;

    public LabelWidget(TopographySmoothTextRenderer font, String text, int color) {
        this.font = font;
        this.textSupplier = () -> text;
        this.colorSupplier = () -> color;
        this.h = font.lineHeight();
    }

    public LabelWidget(TopographySmoothTextRenderer font, Supplier<String> text, IntSupplier color) {
        this.font = font;
        this.textSupplier = text;
        this.colorSupplier = color;
        this.h = font.lineHeight();
    }

    public LabelWidget setAlign(Align align) {
        this.align = align;
        return this;
    }

    public LabelWidget setText(String text) {
        this.textSupplier = () -> text;
        return this;
    }

    public LabelWidget setTextSupplier(Supplier<String> supplier) {
        this.textSupplier = supplier;
        return this;
    }

    public LabelWidget setColor(int color) {
        this.colorSupplier = () -> color;
        return this;
    }

    public LabelWidget setColorSupplier(IntSupplier supplier) {
        this.colorSupplier = supplier;
        return this;
    }

    public LabelWidget setEllipsis(boolean ellipsis) {
        this.ellipsis = ellipsis;
        return this;
    }

    public TopographySmoothTextRenderer getFont() {
        return font;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        String text = textSupplier.get();
        if (text == null || text.isEmpty()) return;
        if (ellipsis && w > 0) {
            text = font.ellipsize(text, (int) w);
        }
        int color = TopographySurfaceRenderer.withAlpha(colorSupplier.getAsInt(),
                ((colorSupplier.getAsInt() >>> 24) & 0xFF) * alpha / 255);
        switch (align) {
            case LEFT -> font.draw(ctx, text, x, y, color);
            case CENTER -> font.drawCentered(ctx, text, x + w / 2, y, color);
            case RIGHT -> font.drawRight(ctx, text, x + w, y, color);
        }
    }
}
