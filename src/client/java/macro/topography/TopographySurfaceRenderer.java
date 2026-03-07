package macro.topography;

import net.minecraft.client.gui.DrawContext;

public final class TopographySurfaceRenderer {

    public void drawGlassPanel(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor) {
        drawShadow(context, x, y, width, height, radius, 12, withAlpha(0x000000, 64));
        drawOutlinedRounded(context, x, y, width, height, radius, fillColor, borderColor, 1.0f);
        drawRounded(context, x + 1.0f, y + 1.0f, width - 2.0f, Math.max(12.0f, height * 0.22f), radius, withAlpha(0xFFFFFF, 10));
    }

    public void drawOutlinedRounded(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor, float borderSize) {
        drawRounded(context, x, y, width, height, radius, borderColor);
        drawRounded(context, x + borderSize, y + borderSize, width - borderSize * 2.0f, height - borderSize * 2.0f, radius, fillColor);
    }

    public void drawRounded(DrawContext context, float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(x + width);
        int bottom = Math.round(y + height);
        int roundedRadius = Math.max(0, Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2)));

        if (roundedRadius <= 1) {
            context.fill(left, top, right, bottom, color);
            return;
        }

        context.fill(left + roundedRadius, top, right - roundedRadius, bottom, color);
        context.fill(left, top + roundedRadius, right, bottom - roundedRadius, color);

        for (int row = 0; row < roundedRadius; row++) {
            float dy = roundedRadius - row - 0.5f;
            int inset = Math.max(0, Math.round(roundedRadius - (float) Math.sqrt(roundedRadius * roundedRadius - dy * dy)));
            context.fill(left + inset, top + row, right - inset, top + row + 1, color);
            context.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
        }
    }

    public void drawGradientRounded(DrawContext context, float x, float y, float width, float height, float radius, int leftColor, int rightColor, float alpha) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        int start = multiplyAlpha(leftColor, alpha);
        int end = multiplyAlpha(rightColor, alpha);
        context.fillGradient(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height), start, end);
    }

    public void drawShadow(DrawContext context, float x, float y, float width, float height, float radius, int spread, int color) {
        if (width <= 0.0f || height <= 0.0f || spread <= 0) {
            return;
        }

        for (int i = 1; i <= spread; i++) {
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * (1.0f - i / (float) (spread + 1))));
            drawRounded(
                context,
                x + i * 0.35f,
                y + i * 0.55f,
                width,
                height,
                radius + i * 0.2f,
                withAlpha(color, alpha)
            );
        }
    }

    private static int multiplyAlpha(int color, float alphaScale) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alphaScale)));
        return withAlpha(color, alpha);
    }

    public static int withAlpha(int color, int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
    }
}
