package macro.topography;

import net.minecraft.client.gui.DrawContext;

public final class TopographySurfaceRenderer {

    public void drawGlassPanel(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor) {
        drawShadow(context, x, y, width, height, radius, 8, withAlpha(0x000000, 64));
        drawOutlinedRounded(context, x, y, width, height, radius, fillColor, borderColor, 1.0f);
        drawRoundedFast(context, x + 1.0f, y + 1.0f, width - 2.0f, Math.max(12.0f, height * 0.22f), radius, withAlpha(0xFFFFFF, 10));
    }

    public void drawOutlinedRounded(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor, float borderSize) {
        drawRounded(context, x, y, width, height, radius, borderColor);
        drawRounded(context, x + borderSize, y + borderSize, width - borderSize * 2.0f, height - borderSize * 2.0f, Math.max(0, radius - borderSize), fillColor);
    }

    /** High-quality rounded rect with per-pixel 2D distance-field AA. Use for visible surfaces. */
    public void drawRounded(DrawContext context, float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0f || height <= 0.0f) return;

        int baseAlpha = (color >>> 24) & 0xFF;
        if (baseAlpha == 0) return;

        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(x + width);
        int bottom = Math.round(y + height);
        int iw = right - left;
        int ih = bottom - top;
        int r = Math.max(0, Math.min(Math.round(radius), Math.min(iw / 2, ih / 2)));

        if (r <= 1) {
            context.fill(left, top, right, bottom, color);
            return;
        }

        // Center body
        context.fill(left + r, top, right - r, bottom, color);
        context.fill(left, top + r, left + r, bottom - r, color);
        context.fill(right - r, top + r, right, bottom - r, color);

        // Corner quadrants — per-pixel 2D distance-field AA
        for (int cy = 0; cy < r; cy++) {
            float dy = r - cy - 0.5f;
            float dxEdge = (float) Math.sqrt(Math.max(0, (float) r * r - dy * dy));
            int solidCol = (int) Math.ceil(r - dxEdge);

            if (solidCol < r) {
                context.fill(left + solidCol, top + cy, left + r, top + cy + 1, color);
                context.fill(right - r, top + cy, right - solidCol, top + cy + 1, color);
                context.fill(left + solidCol, bottom - cy - 1, left + r, bottom - cy, color);
                context.fill(right - r, bottom - cy - 1, right - solidCol, bottom - cy, color);
            }

            int checkFrom = Math.max(0, solidCol - 3);
            for (int cx = checkFrom; cx < solidCol; cx++) {
                float ddx = r - cx - 0.5f;
                float dist = (float) Math.sqrt(ddx * ddx + dy * dy);
                float coverage = Math.max(0f, Math.min(1f, r - dist + 0.5f));
                if (coverage < 0.01f) continue;
                int aa = Math.max(1, Math.round(baseAlpha * coverage));
                int aaColor = withAlpha(color, aa);

                context.fill(left + cx, top + cy, left + cx + 1, top + cy + 1, aaColor);
                context.fill(right - cx - 1, top + cy, right - cx, top + cy + 1, aaColor);
                context.fill(left + cx, bottom - cy - 1, left + cx + 1, bottom - cy, aaColor);
                context.fill(right - cx - 1, bottom - cy - 1, right - cx, bottom - cy, aaColor);
            }
        }
    }

    /** Fast rounded rect — row-based inset only, no per-pixel AA. For shadows/glows where quality doesn't matter. */
    public void drawRoundedFast(DrawContext context, float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0f || height <= 0.0f) return;
        if (((color >>> 24) & 0xFF) == 0) return;

        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(x + width);
        int bottom = Math.round(y + height);
        int iw = right - left;
        int ih = bottom - top;
        int r = Math.max(0, Math.min(Math.round(radius), Math.min(iw / 2, ih / 2)));

        if (r <= 1) {
            context.fill(left, top, right, bottom, color);
            return;
        }

        // Center body
        context.fill(left + r, top, right - r, bottom, color);
        context.fill(left, top + r, left + r, bottom - r, color);
        context.fill(right - r, top + r, right, bottom - r, color);

        // Simple row-based corners — one fill per row per corner, no AA
        for (int row = 0; row < r; row++) {
            float dy = r - row - 0.5f;
            float dx = (float) Math.sqrt((float) r * r - dy * dy);
            int inset = (int) Math.ceil(r - dx);
            if (inset >= r) continue;

            context.fill(left + inset, top + row, left + r, top + row + 1, color);
            context.fill(right - r, top + row, right - inset, top + row + 1, color);
            context.fill(left + inset, bottom - row - 1, left + r, bottom - row, color);
            context.fill(right - r, bottom - row - 1, right - inset, bottom - row, color);
        }
    }

    public void drawGradientRounded(DrawContext context, float x, float y, float width, float height, float radius, int topColor, int bottomColor, float alpha) {
        if (width <= 0.0f || height <= 0.0f) return;

        int start = multiplyAlpha(topColor, alpha);
        int end = multiplyAlpha(bottomColor, alpha);

        int left = Math.round(x);
        int top = Math.round(y);
        int right = Math.round(x + width);
        int bottom = Math.round(y + height);
        int r = Math.max(0, Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2)));

        if (r <= 1) {
            context.fillGradient(left, top, right, bottom, start, end);
            return;
        }

        int totalH = bottom - top;
        for (int row = 0; row < totalH; row++) {
            float t = totalH > 1 ? (float) row / (totalH - 1) : 0f;
            int rowColor = lerpColor(start, end, t);

            int inset = 0;
            if (row < r) {
                float dy = r - row - 0.5f;
                float dx = (float) Math.sqrt((float) r * r - dy * dy);
                inset = (int) Math.ceil(r - dx);
            } else if (row >= totalH - r) {
                float dy = r - (totalH - 1 - row) - 0.5f;
                float dx = (float) Math.sqrt((float) r * r - dy * dy);
                inset = (int) Math.ceil(r - dx);
            }

            context.fill(left + inset, top + row, right - inset, top + row + 1, rowColor);
        }
    }

    public void drawShadow(DrawContext context, float x, float y, float width, float height, float radius, int spread, int color) {
        if (width <= 0.0f || height <= 0.0f || spread <= 0) return;

        // Cap layers for performance — skip every other layer at high spread
        int step = spread > 8 ? 2 : 1;
        for (int i = step; i <= spread; i += step) {
            float frac = (float) i / (spread + 1);
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * (1.0f - frac) * (1.0f - frac)));
            if (step > 1) alpha = Math.min(255, alpha * step); // compensate skipped layers
            float expand = i * 0.5f;
            drawRoundedFast(context,
                x - expand,
                y - expand * 0.3f + i * 0.4f,
                width + expand * 2,
                height + expand * 0.6f + i * 0.2f,
                radius + i * 0.7f,
                withAlpha(color, alpha));
        }
    }

    public void drawGlow(DrawContext context, float x, float y, float width, float height,
                         float radius, int spread, int color) {
        if (width <= 0.0f || height <= 0.0f || spread <= 0) return;

        // Skip every other layer for performance at high spread
        int step = spread > 6 ? 2 : 1;
        for (int i = spread; i >= 1; i -= step) {
            float frac = 1.0f - (float) i / (spread + 1);
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * frac * frac));
            if (step > 1) alpha = Math.min(255, alpha * step);
            drawRoundedFast(context, x - i, y - i, width + i * 2, height + i * 2,
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
            drawRoundedFast(context, x + inset, y + inset, width - inset * 2, 1,
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
