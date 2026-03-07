package macro.topography;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public final class MyFirstButton {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final Text label;
    private final Runnable onPress;

    private float hoverAnimation;

    public MyFirstButton(int x, int y, int width, int height, Text label, Runnable onPress) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label;
        this.onPress = onPress;
    }

    public void drawButton(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
        boolean hovered = isHovered(mouseX, mouseY);
        hoverAnimation = MathHelper.lerp(0.12f, hoverAnimation, hovered ? 1.0f : 0.0f);

        int backgroundColor = interpolateColor(0xFF121212, 0xFF7F00FF, hoverAnimation);
        int borderColor = interpolateColor(0x33262626, 0x66E100FF, hoverAnimation);

        context.fill(x, y, x + width, y + height, backgroundColor);
        drawBorder(context, borderColor);

        int textY = y + (height - textRenderer.fontHeight) / 2;
        context.drawCenteredTextWithShadow(textRenderer, label, x + width / 2, textY, 0xFFFFFFFF);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isHovered(mouseX, mouseY)) {
            return false;
        }

        if (onPress != null) {
            onPress.run();
        }
        return true;
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static int interpolateColor(int startColor, int endColor, float delta) {
        float progress = MathHelper.clamp(delta, 0.0f, 1.0f);

        int startA = (startColor >>> 24) & 0xFF;
        int startR = (startColor >>> 16) & 0xFF;
        int startG = (startColor >>> 8) & 0xFF;
        int startB = startColor & 0xFF;

        int endA = (endColor >>> 24) & 0xFF;
        int endR = (endColor >>> 16) & 0xFF;
        int endG = (endColor >>> 8) & 0xFF;
        int endB = endColor & 0xFF;

        int a = Math.round(MathHelper.lerp(progress, startA, endA));
        int r = Math.round(MathHelper.lerp(progress, startR, endR));
        int g = Math.round(MathHelper.lerp(progress, startG, endG));
        int b = Math.round(MathHelper.lerp(progress, startB, endB));

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawBorder(DrawContext context, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}
