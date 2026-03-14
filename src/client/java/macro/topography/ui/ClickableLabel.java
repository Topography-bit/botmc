package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ClickableLabel extends Widget {

    private final TopographySmoothTextRenderer font;
    private Supplier<String> textSupplier;
    private IntSupplier normalColor;
    private IntSupplier hoverColorSupplier;
    private Runnable onClick;

    public ClickableLabel(TopographySmoothTextRenderer font, Supplier<String> text,
                          IntSupplier normalColor, IntSupplier hoverColor) {
        this.font = font;
        this.textSupplier = text;
        this.normalColor = normalColor;
        this.hoverColorSupplier = hoverColor;
        this.h = font.lineHeight() + 4;
    }

    public ClickableLabel(TopographySmoothTextRenderer font, String text, int normalColor, int hoverColor) {
        this(font, () -> text, () -> normalColor, () -> hoverColor);
    }

    public ClickableLabel setOnClick(Runnable action) {
        this.onClick = action;
        return this;
    }

    public ClickableLabel setTextSupplier(Supplier<String> supplier) {
        this.textSupplier = supplier;
        return this;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        String text = textSupplier.get();
        if (text == null || text.isEmpty()) return;

        int color = hovered ? hoverColorSupplier.getAsInt() : normalColor.getAsInt();
        color = applyAlpha(color, alpha);
        font.draw(ctx, text, x, y, color);

        // Auto-size width to text
        this.w = font.width(text);
    }

    @Override
    protected void onClicked() {
        if (onClick != null) onClick.run();
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
