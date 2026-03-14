package macro.topography;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class TopographySurfaceRenderer {

    private static float guiScale() {
        return (float) MinecraftClient.getInstance().getWindow().getScaleFactor();
    }

    public void drawGlassPanel(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor) {
        drawShadow(context, x, y, width, height, radius, 8, withAlpha(0x000000, 64));
        drawOutlinedRounded(context, x, y, width, height, radius, fillColor, borderColor, 1.0f);
        drawRoundedFast(context, x + 1.0f, y + 1.0f, width - 2.0f, Math.max(12.0f, height * 0.22f), radius, withAlpha(0xFFFFFF, 10));
    }

    public void drawOutlinedRounded(DrawContext context, float x, float y, float width, float height, float radius, int fillColor, int borderColor, float borderSize) {
        drawRounded(context, x, y, width, height, radius, borderColor);
        drawRounded(context, x + borderSize, y + borderSize, width - borderSize * 2.0f, height - borderSize * 2.0f, Math.max(0, radius - borderSize), fillColor);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HIGH-QUALITY: renders at screen-pixel resolution via matrix scale
    // ═══════════════════════════════════════════════════════════════════

    public void drawRounded(DrawContext context, float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0f || height <= 0.0f) return;
        int baseAlpha = (color >>> 24) & 0xFF;
        if (baseAlpha == 0) return;

        float gs = guiScale();

        // Convert to screen pixels
        int left = Math.round(x * gs);
        int top = Math.round(y * gs);
        int right = Math.round((x + width) * gs);
        int bottom = Math.round((y + height) * gs);
        int iw = right - left;
        int ih = bottom - top;
        int r = Math.max(0, Math.min(Math.round(radius * gs), Math.min(iw / 2, ih / 2)));

        // Scale matrix to screen pixel space
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(1f / gs, 1f / gs);

        if (r <= 1) {
            context.fill(left, top, right, bottom, color);
            matrices.popMatrix();
            return;
        }

        // Center body (3 fills)
        context.fill(left + r, top, right - r, bottom, color);
        context.fill(left, top + r, left + r, bottom - r, color);
        context.fill(right - r, top + r, right, bottom - r, color);

        // Corners — batched: merge consecutive rows with same inset into single rect
        // At screen-pixel resolution, row-based insets are smooth enough (no per-pixel AA needed)
        int prevInset = -1;
        int batchStart = 0;
        for (int row = 0; row <= r; row++) {
            int inset;
            if (row < r) {
                float dy = r - row - 0.5f;
                float dx = (float) Math.sqrt(Math.max(0, (float) r * r - dy * dy));
                inset = (int) Math.ceil(r - dx);
            } else {
                inset = -2; // sentinel to flush last batch
            }

            if (inset != prevInset && prevInset >= 0 && prevInset < r) {
                // Flush batch: rows [batchStart, row) all have inset=prevInset
                int h1 = row - batchStart;
                // Top-left
                context.fill(left + prevInset, top + batchStart, left + r, top + batchStart + h1, color);
                // Top-right
                context.fill(right - r, top + batchStart, right - prevInset, top + batchStart + h1, color);
                // Bottom-left
                context.fill(left + prevInset, bottom - batchStart - h1, left + r, bottom - batchStart, color);
                // Bottom-right
                context.fill(right - r, bottom - batchStart - h1, right - prevInset, bottom - batchStart, color);
            }

            if (inset != prevInset) {
                batchStart = row;
                prevInset = inset;
            }
        }

        // Single AA fringe: one edge pixel per row with distance-based coverage
        for (int row = 0; row < r; row++) {
            float dy = r - row - 0.5f;
            float dx = (float) Math.sqrt(Math.max(0, (float) r * r - dy * dy));
            float exactInset = r - dx;
            int edgeCol = (int) Math.floor(exactInset);
            if (edgeCol < 0 || edgeCol >= r) continue;
            float coverage = 1f - (exactInset - edgeCol);
            if (coverage < 0.02f) continue; // skip near-empty only
            int aa = Math.max(1, Math.round(baseAlpha * coverage));
            int aaColor = withAlpha(color, aa);

            context.fill(left + edgeCol, top + row, left + edgeCol + 1, top + row + 1, aaColor);
            context.fill(right - edgeCol - 1, top + row, right - edgeCol, top + row + 1, aaColor);
            context.fill(left + edgeCol, bottom - row - 1, left + edgeCol + 1, bottom - row, aaColor);
            context.fill(right - edgeCol - 1, bottom - row - 1, right - edgeCol, bottom - row, aaColor);
        }

        matrices.popMatrix();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FAST: GUI-pixel resolution, no AA. For shadows/glows only.
    // ═══════════════════════════════════════════════════════════════════

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

        context.fill(left + r, top, right - r, bottom, color);
        context.fill(left, top + r, left + r, bottom - r, color);
        context.fill(right - r, top + r, right, bottom - r, color);

        // Batched corners: merge consecutive rows with same inset
        int prevInset = -1;
        int batchStart = 0;
        for (int row = 0; row <= r; row++) {
            int inset;
            if (row < r) {
                float dy = r - row - 0.5f;
                float dx = (float) Math.sqrt((float) r * r - dy * dy);
                inset = (int) Math.ceil(r - dx);
                if (inset >= r) inset = r; // mark as skip
            } else {
                inset = -2; // sentinel
            }

            if (inset != prevInset && prevInset >= 0 && prevInset < r) {
                int h1 = row - batchStart;
                context.fill(left + prevInset, top + batchStart, left + r, top + batchStart + h1, color);
                context.fill(right - r, top + batchStart, right - prevInset, top + batchStart + h1, color);
                context.fill(left + prevInset, bottom - batchStart - h1, left + r, bottom - batchStart, color);
                context.fill(right - r, bottom - batchStart - h1, right - prevInset, bottom - batchStart, color);
            }

            if (inset != prevInset) {
                batchStart = row;
                prevInset = inset;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GRADIENT ROUNDED — screen-pixel resolution
    // ═══════════════════════════════════════════════════════════════════

    public void drawGradientRounded(DrawContext context, float x, float y, float width, float height, float radius, int topColor, int bottomColor, float alpha) {
        if (width <= 0.0f || height <= 0.0f) return;

        int start = multiplyAlpha(topColor, alpha);
        int end = multiplyAlpha(bottomColor, alpha);

        float gs = guiScale();

        int left = Math.round(x * gs);
        int top = Math.round(y * gs);
        int right = Math.round((x + width) * gs);
        int bottom = Math.round((y + height) * gs);
        int r = Math.max(0, Math.min(Math.round(radius * gs), Math.min((right - left) / 2, (bottom - top) / 2)));

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(1f / gs, 1f / gs);

        if (r <= 1) {
            context.fillGradient(left, top, right, bottom, start, end);
            matrices.popMatrix();
            return;
        }

        // Batch rows with same inset to reduce fill calls
        int totalH = bottom - top;
        int prevInset = -1;
        int batchStart = 0;

        for (int row = 0; row <= totalH; row++) {
            int inset;
            if (row >= totalH) {
                inset = -2; // sentinel
            } else if (row < r) {
                float dy = r - row - 0.5f;
                float dx = (float) Math.sqrt((float) r * r - dy * dy);
                inset = (int) Math.ceil(r - dx);
            } else if (row >= totalH - r) {
                float dy = r - (totalH - 1 - row) - 0.5f;
                float dx = (float) Math.sqrt((float) r * r - dy * dy);
                inset = (int) Math.ceil(r - dx);
            } else {
                inset = 0;
            }

            if (inset != prevInset && prevInset >= 0) {
                // Flush batch as gradient
                int batchH = row - batchStart;
                float t1 = totalH > 1 ? (float) batchStart / (totalH - 1) : 0f;
                float t2 = totalH > 1 ? (float) (row - 1) / (totalH - 1) : 0f;
                int c1 = lerpColor(start, end, t1);
                int c2 = lerpColor(start, end, t2);
                if (batchH == 1 || c1 == c2) {
                    context.fill(left + prevInset, top + batchStart, right - prevInset, top + row, c1);
                } else {
                    context.fillGradient(left + prevInset, top + batchStart, right - prevInset, top + row, c1, c2);
                }
            }

            if (inset != prevInset) {
                batchStart = row;
                prevInset = inset;
            }
        }

        matrices.popMatrix();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EFFECTS: shadow, glow, inset shadow — use fast renderer
    // ═══════════════════════════════════════════════════════════════════

    public void drawShadow(DrawContext context, float x, float y, float width, float height, float radius, int spread, int color) {
        if (width <= 0.0f || height <= 0.0f || spread <= 0) return;

        int step = spread > 8 ? 2 : 1;
        for (int i = step; i <= spread; i += step) {
            float frac = (float) i / (spread + 1);
            int alpha = Math.max(1, Math.round(((color >>> 24) & 0xFF) * (1.0f - frac) * (1.0f - frac)));
            if (step > 1) alpha = Math.min(255, alpha * step);
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

    // ═══════════════════════════════════════════════════════════════════
    //  COLOR UTILITIES
    // ═══════════════════════════════════════════════════════════════════

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
