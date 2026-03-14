package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * A clickable label that cycles through a list of options.
 * Displays: "Label: < Value >"
 */
public class CycleSelectorWidget extends Widget {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private final TopographySmoothTextRenderer font;
    private final String label;
    private final List<String> options;
    private final IntSupplier indexGetter;
    private final Consumer<Integer> indexSetter;

    private int labelColor = 0xFF6B6F80;
    private int valueColor = 0xFFE8E8F0;
    private int arrowColor = 0xFF5C65C7;
    private int arrowHoverColor = 0xFF818CF8;

    public CycleSelectorWidget(TopographySmoothTextRenderer font, String label,
                                List<String> options,
                                IntSupplier indexGetter, Consumer<Integer> indexSetter) {
        this.font = font;
        this.label = label;
        this.options = options;
        this.indexGetter = indexGetter;
        this.indexSetter = indexSetter;
        this.h = font.lineHeight() + 4;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        int idx = indexGetter.getAsInt();
        String value = (idx >= 0 && idx < options.size()) ? options.get(idx) : "—";

        int lblCol = applyAlpha(labelColor, alpha);
        font.draw(ctx, label, x, y + 2, lblCol);

        float valX = x + font.width(label) + 4;
        int arrCol = applyAlpha(hovered ? arrowHoverColor : arrowColor, alpha);
        font.draw(ctx, "\u25C0 ", valX, y + 2, arrCol);

        float arrowLeftW = font.width("\u25C0 ");
        int valCol = applyAlpha(valueColor, alpha);
        font.draw(ctx, value, valX + arrowLeftW, y + 2, valCol);

        float valueW = font.width(value);
        font.draw(ctx, " \u25B6", valX + arrowLeftW + valueW, y + 2, arrCol);

        this.w = font.width(label) + 4 + arrowLeftW + valueW + font.width(" \u25B6");
    }

    @Override
    protected void onClicked() {
        int idx = indexGetter.getAsInt();
        int next = (idx + 1) % options.size();
        indexSetter.accept(next);
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
