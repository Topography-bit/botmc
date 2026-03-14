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
        int r = Math.max(0, Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2)));

        if (r <= 1) {
            context.fill(left, top, right, bottom, color);
            return;
        }

        int baseAlpha = (color >>> 24) & 0xFF;

        // Center body (no corners)
        context.fill(left + r, top, right - r, bottom, color);
        context.fill(left, top + r, left + r, bottom - r, color);
        context.fill(right - r, top + r, right, bottom - r, color);

        // Corner rows with sub-pixel anti-aliasing
        for (int row = 0; row < r; row++) {
            float dy = r - row - 0.5f;
            float dx = (float) Math.sqrt((float) r * r - dy * dy);
            float exactInset = r - dx;

            int solidInset = (int) Math.ceil(exactInset);
            float coverage = 1.0f - (exactInset - (float) Math.floor(exactInset));

            // Fully covered interior pixels
            if (left + solidInset < right - solidInset) {
                context.fill(left + solidInset, top + row, right - solidInset, top + row + 1, color);
                context.fill(left + solidInset, bottom - row - 1, right - solidInset, bottom - row, color);
            }

            // Anti-aliased edge pixels (left & right, top & bottom corners)
            int edgePixel = (int) Math.floor(exactInset);
            if (coverage > 0.01f && edgePixel >= 0 && edgePixel < r) {
                int edgeAlpha = Math.max(1, Math.round(baseAlpha * coverage));
                int edgeColor = withAlpha(color, edgeAlpha);
                // Top-left & top-right
                context.fill(left + edgePixel, top + row, left + edgePixel + 1, top + row + 1, edgeColor);
                context.fill(right - edgePixel - 1, top + row, right - edgePixel, top + row + 1, edgeColor);
                // Bottom-left & bottom-right
                context.fill(left + edgePixel, bottom - row - 1, left + edgePixel + 1, bottom - row, edgeColor);
                context.fill(right - edgePixel - 1, bottom - row - 1, right - edgePixel, bottom - row, edgeColor);
            }
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

    public void drawGlow(DrawContext context, float x, float y, float width, float height,
                         float radius, int spread, int color) {
        if (width <= 0.0f || height <= 0.0f || spread <= 0) return;
        for (int i = spread; i >= 1; i--) {
            float frac = 1.0f - (float) i / (spread + 1);
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * frac * frac));
            drawRounded(context, x - i, y - i, width + i * 2, height + i * 2,
                    radius + i * 0.5f, withAlpha(color, alpha));
        }
    }

    public void drawInsetShadow(DrawContext context, float x, float y, float width, float height,
                                float radius, int depth, int color) {
        if (width <= 0.0f || height <= 0.0f || depth <= 0) return;
        for (int i = 0; i < depth; i++) {
            float inset = i + 1;
            float frac = (float) (depth - i) / depth;
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * frac * frac * 0.5f));
            drawRounded(context, x + inset, y + inset, width - inset * 2, 1,
                    Math.max(0, radius - inset), withAlpha(color, alpha));
        }
    }

    public static int lerpColor(int from, int to, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int fa = (from >>> 24) & 0xFF, fr = (from >>> 16) & 0xFF, fg = (from >>> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF, tr = (to >>> 16) & 0xFF, tg = (to >>> 8) & 0xFF, tb = to & 0xFF;
        return (Math.round(fa + (ta - fa) * t) << 24)
             | (Math.round(fr + (tr - fr) * t) << 16)
             | (Math.round(fg + (tg - fg) * t) << 8)
             |  Math.round(fb + (tb - fb) * t);
    }

    public static int brighten(int color, int amount) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + amount);
        int g = Math.min(255, ((color >> 8) & 0xFF) + amount);
        int b = Math.min(255, (color & 0xFF) + amount);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int multiplyAlpha(int color, float alphaScale) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alphaScale)));
        return withAlpha(color, alpha);
    }

    public static int withAlpha(int color, int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
    }
}
