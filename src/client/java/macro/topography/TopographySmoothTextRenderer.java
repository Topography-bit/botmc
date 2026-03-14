package macro.topography;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class TopographySmoothTextRenderer {

    private static final AtomicInteger TEXTURE_COUNTER = new AtomicInteger();
    private static final int BASE_PADDING = 3;

    private final String family;
    private final int baseFontSize;
    private final int style;

    // Lazily initialized at current guiScale
    private int activeScale = -1;
    private Font font;
    private FontMetrics metrics;
    private int scaledPadding;
    private final Map<String, CachedText> cache = new HashMap<>();

    public TopographySmoothTextRenderer(String preferredFamily, int fontSize, int style) {
        this.family = preferredFamily;
        this.baseFontSize = fontSize;
        this.style = style;
    }

    // ── Public API (all values in GUI coordinates) ──────────────

    public int lineHeight() {
        ensureReady();
        return Math.round(metrics.getHeight() / (float) activeScale);
    }

    public int ascent() {
        ensureReady();
        return Math.round(metrics.getAscent() / (float) activeScale);
    }

    public float width(String text) {
        if (text == null || text.isEmpty()) return 0f;
        ensureReady();
        return metrics.stringWidth(text) / (float) activeScale;
    }

    public String ellipsize(String text, int maxWidth) {
        if (text == null || text.isEmpty() || width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int targetWidth = Math.max(0, maxWidth - (int) width(ellipsis));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = builder + String.valueOf(text.charAt(i));
            if (width(candidate) > targetWidth) break;
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    public List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) { lines.add(""); return lines; }
        String[] paragraphs = text.split("\\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) { lines.add(""); continue; }
            String[] words = paragraph.split(" ");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(candidate) <= maxWidth || current.isEmpty()) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                    if (maxLines > 0 && lines.size() >= maxLines) {
                        lines.set(maxLines - 1, ellipsize(lines.get(maxLines - 1), maxWidth));
                        return lines.subList(0, maxLines);
                    }
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
        }
        if (maxLines > 0 && lines.size() > maxLines) {
            List<String> limited = new ArrayList<>(lines.subList(0, maxLines));
            limited.set(maxLines - 1, ellipsize(limited.get(maxLines - 1), maxWidth));
            return limited;
        }
        return lines;
    }

    public void draw(DrawContext context, String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) return;
        ensureReady();
        CachedText cached = getOrCreate(text, color);

        // Display size in GUI coords = texture pixels / guiScale
        // textureWidth/Height also set to the same value so UV maps 0→1 (full texture coverage)
        int displayW = Math.round(cached.texPixelsW / (float) activeScale);
        int displayH = Math.round(cached.texPixelsH / (float) activeScale);
        int guiPad = Math.round(scaledPadding / (float) activeScale);

        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            cached.textureId,
            Math.round(x) - guiPad,
            Math.round(y) - guiPad,
            0f, 0f,
            displayW,
            displayH,
            displayW,
            displayH
        );
    }

    public void drawCentered(DrawContext context, String text, float centerX, float y, int color) {
        draw(context, text, centerX - width(text) / 2f, y, color);
    }

    public void drawWithTracking(DrawContext context, String text, float x, float y, int color, float tracking) {
        if (text == null || text.isEmpty()) return;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            draw(context, ch, cx, y, color);
            if (i < text.length() - 1) cx += width(ch) + tracking;
        }
    }

    public float widthWithTracking(String text, float tracking) {
        if (text == null || text.isEmpty()) return 0f;
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += width(String.valueOf(text.charAt(i)));
            if (i < text.length() - 1) total += tracking;
        }
        return total;
    }

    public void drawCenteredWithTracking(DrawContext context, String text, float centerX, float y,
                                          int color, float tracking) {
        drawWithTracking(context, text, centerX - widthWithTracking(text, tracking) / 2f, y, color, tracking);
    }

    public void drawRight(DrawContext context, String text, float rightX, float y, int color) {
        draw(context, text, rightX - width(text), y, color);
    }

    // ── Lazy initialization & scale tracking ────────────────────

    private void ensureReady() {
        int scale = currentGuiScale();
        if (scale != activeScale) {
            rebuildForScale(scale);
        }
    }

    private void rebuildForScale(int scale) {
        // Destroy old textures
        if (!cache.isEmpty()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                for (CachedText ct : cache.values()) {
                    mc.getTextureManager().destroyTexture(ct.textureId);
                }
            }
            cache.clear();
        }

        activeScale = scale;
        scaledPadding = BASE_PADDING * scale;

        int scaledFontSize = baseFontSize * scale;
        this.font = resolveFont(family, scaledFontSize, style);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = configureGraphics(probe.createGraphics());
        g.setFont(font);
        this.metrics = g.getFontMetrics();
        g.dispose();
    }

    private static int currentGuiScale() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getWindow() != null) {
            int s = (int) mc.getWindow().getScaleFactor();
            if (s >= 1) return s;
        }
        return 2; // safe default
    }

    // ── Texture cache ───────────────────────────────────────────

    private CachedText getOrCreate(String text, int color) {
        String key = text + '\u0000' + Integer.toHexString(color);
        return cache.computeIfAbsent(key, ignored -> createTexture(text, color));
    }

    private CachedText createTexture(String text, int color) {
        int rawWidth = Math.max(1, metrics.stringWidth(text));
        int rawHeight = Math.max(1, metrics.getHeight());
        int texW = rawWidth + scaledPadding * 2;
        int texH = rawHeight + scaledPadding * 2;

        BufferedImage image = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = configureGraphics(image.createGraphics());
        graphics.setFont(font);
        graphics.setColor(toAwtColor(color));
        graphics.drawString(text, scaledPadding, scaledPadding + metrics.getAscent());
        graphics.dispose();

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(buffer.toByteArray()));
            int textureIndex = TEXTURE_COUNTER.incrementAndGet();
            NativeImageBackedTexture texture = new NativeImageBackedTexture(
                    () -> "topography_text_" + textureIndex, nativeImage);
            Identifier id = Identifier.of("topography", "ui/text/" + textureIndex);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            return new CachedText(id, texW, texH);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build smooth text texture for '" + text + "'", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static Graphics2D configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return graphics;
    }

    private static Font resolveFont(String preferredFamily, int fontSize, int style) {
        List<String> fallbacks = List.of(preferredFamily, "Inter", "Geist", "Segoe UI", "SF Pro Display", "SansSerif");
        String[] availableFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT);
        for (String family : fallbacks) {
            for (String available : availableFamilies) {
                if (available.equalsIgnoreCase(family)) {
                    return new Font(available, style, fontSize);
                }
            }
        }
        return new Font("SansSerif", style, fontSize);
    }

    private static Color toAwtColor(int color) {
        return new Color((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, (color >>> 24) & 0xFF);
    }

    private record CachedText(Identifier textureId, int texPixelsW, int texPixelsH) {}
}
