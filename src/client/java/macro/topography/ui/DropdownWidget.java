package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * A dropdown selector widget. Shows current value; click to expand options list.
 */
public class DropdownWidget extends Widget {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private static final int BG_COLOR       = 0xFF1A1A24;
    private static final int BG_HOVER       = 0xFF222230;
    private static final int BORDER_COLOR   = 0xFF2A2A36;
    private static final int ARROW_COLOR    = 0xFF5C65C7;
    private static final float ITEM_H       = 18f;
    private static final float DROPDOWN_R   = 8f;
    private static final float PAD_X        = 8f;

    private final TopographySmoothTextRenderer font;
    private final String label;
    private final List<String> options;
    private final IntSupplier indexGetter;
    private final Consumer<Integer> indexSetter;

    private int labelColor = 0xFF6B6F80;
    private int valueColor = 0xFFE8E8F0;

    private boolean open = false;
    private int hoveredOption = -1;

    // Computed dropdown bounds
    private float dropX, dropY, dropW, dropH;

    public DropdownWidget(TopographySmoothTextRenderer font, String label,
                          List<String> options,
                          IntSupplier indexGetter, Consumer<Integer> indexSetter) {
        this.font = font;
        this.label = label;
        this.options = options;
        this.indexGetter = indexGetter;
        this.indexSetter = indexSetter;
        this.h = font.lineHeight() + 4;
    }

    public boolean isOpen() { return open; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        int idx = indexGetter.getAsInt();
        String value = (idx >= 0 && idx < options.size()) ? options.get(idx) : "---";

        // Label
        int lblCol = applyAlpha(labelColor, alpha);
        font.draw(ctx, label, x, y + 2, lblCol);

        float labelW = font.width(label);
        float boxX = x + labelW + 4;

        // Compute widest option for box width
        float maxOptW = 0;
        for (String opt : options) {
            maxOptW = Math.max(maxOptW, font.width(opt));
        }
        float boxW = maxOptW + PAD_X * 2 + 12; // 12 for arrow

        // Selected value box
        int boxBg = applyAlpha(BG_COLOR, alpha);
        int boxBorder = applyAlpha(BORDER_COLOR, alpha);
        S.drawOutlinedRounded(ctx, boxX, y, boxW, h, 6, boxBg, boxBorder, 1f);

        int valCol = applyAlpha(valueColor, alpha);
        font.draw(ctx, value, boxX + PAD_X, y + 2, valCol);

        // Arrow indicator (v or ^)
        String arrow = open ? "^" : "v";
        int arrCol = applyAlpha(ARROW_COLOR, alpha);
        font.draw(ctx, arrow, boxX + boxW - PAD_X - font.width(arrow), y + 2, arrCol);

        this.w = labelW + 4 + boxW;

        // Store dropdown bounds — opens UPWARD
        dropW = boxW;
        dropH = options.size() * ITEM_H + 4; // 2px padding top+bottom
        dropX = boxX;
        dropY = y - dropH - 2;
    }

    /**
     * Call this AFTER all other widgets have rendered, so the dropdown draws on top.
     */
    public void renderOverlay(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!open || !visible) return;

        // Dropdown background
        int bg = applyAlpha(0xFF111118, alpha);
        int border = applyAlpha(BORDER_COLOR, alpha);
        S.drawOutlinedRounded(ctx, dropX, dropY, dropW, dropH, DROPDOWN_R, bg, border, 1f);

        // Options
        hoveredOption = -1;
        float iy = dropY + 2;
        for (int i = 0; i < options.size(); i++) {
            boolean itemHovered = mouseX >= dropX && mouseX <= dropX + dropW
                    && mouseY >= iy && mouseY < iy + ITEM_H;
            if (itemHovered) hoveredOption = i;

            if (itemHovered) {
                S.drawRounded(ctx, dropX + 2, iy, dropW - 4, ITEM_H, 6,
                        applyAlpha(BG_HOVER, alpha));
            }

            boolean selected = (i == indexGetter.getAsInt());
            int txtCol = applyAlpha(selected ? 0xFF818CF8 : valueColor, alpha);
            float textY = iy + (ITEM_H - font.lineHeight()) / 2f;
            font.draw(ctx, options.get(i), dropX + PAD_X, textY, txtCol);

            iy += ITEM_H;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !enabled || button != 0) return false;

        // Click on dropdown option
        if (open) {
            if (mouseX >= dropX && mouseX <= dropX + dropW
                    && mouseY >= dropY && mouseY <= dropY + dropH) {
                if (hoveredOption >= 0 && hoveredOption < options.size()) {
                    indexSetter.accept(hoveredOption);
                }
                open = false;
                return true;
            }
            // Click outside — close
            open = false;
            return true;
        }

        // Click on the selector box to open
        float labelW = font.width(label);
        float boxX = x + labelW + 4;
        float maxOptW = 0;
        for (String opt : options) {
            maxOptW = Math.max(maxOptW, font.width(opt));
        }
        float boxW = maxOptW + PAD_X * 2 + 12;

        if (mouseX >= boxX && mouseX <= boxX + boxW
                && mouseY >= y && mouseY <= y + h) {
            open = true;
            return true;
        }

        return false;
    }

    public void closeIfOpen() {
        open = false;
    }

    @Override
    protected void onClicked() {
        // Handled in mouseClicked
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
