package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ToggleWidget extends Widget {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();

    private static final float TRACK_W = 26;
    private static final float TRACK_H = 12;
    private static final float TRACK_R = 6;
    private static final float THUMB_SIZE = 8;
    private static final float THUMB_PAD = 2;

    private final TopographySmoothTextRenderer font;
    private final String label;
    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;

    private int onColor = 0xFF34D399;
    private int offColor = 0xFF2A2A34;
    private int labelColor = 0xFF6B6F80;

    private float toggleProgress = -1;
    private long lastToggleNanos = -1;

    private static final float TOGGLE_ON_TAU = 60f;   // ~180ms to settle
    private static final float TOGGLE_OFF_TAU = 80f;  // ~240ms to settle

    public ToggleWidget(TopographySmoothTextRenderer font, String label,
                        BooleanSupplier getter, Consumer<Boolean> setter) {
        this.font = font;
        this.label = label;
        this.getter = getter;
        this.setter = setter;
        this.h = font.lineHeight() + 4;
    }

    public ToggleWidget setColors(int on, int off, int label) {
        this.onColor = on;
        this.offColor = off;
        this.labelColor = label;
        return this;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        boolean on = getter.getAsBoolean();

        // ── Animate toggle position (exponential smoothing) ──────
        long now = System.nanoTime();
        if (toggleProgress < 0) {
            toggleProgress = on ? 1f : 0f;
            lastToggleNanos = now;
        } else if (lastToggleNanos >= 0) {
            float dtMs = Math.min(50f, (now - lastToggleNanos) / 1_000_000f);
            float target = on ? 1f : 0f;
            if (Math.abs(toggleProgress - target) > 0.001f) {
                float tau = on ? TOGGLE_ON_TAU : TOGGLE_OFF_TAU;
                float decay = (float) Math.exp(-dtMs / tau);
                toggleProgress = target + (toggleProgress - target) * decay;
            } else {
                toggleProgress = target;
            }
        }
        lastToggleNanos = now;

        // ── Label text ───────────────────────────────────────────
        int lblCol = applyAlpha(labelColor, alpha);
        font.draw(ctx, label, x, y + 2, lblCol);

        // ── Pill toggle ──────────────────────────────────────────
        float pillX = x + font.width(label) + 4;
        float pillY = y + (h - TRACK_H) / 2f;

        // ── Track fill ────────────────────────────────────────────
        int trackColor = applyAlpha(
                TopographySurfaceRenderer.lerpColor(offColor, onColor, toggleProgress), alpha);
        if (hoverProgress > 0.01f) {
            trackColor = TopographySurfaceRenderer.lerpColor(trackColor,
                    TopographySurfaceRenderer.brighten(trackColor, 20), hoverProgress);
        }
        S.drawRounded(ctx, pillX, pillY, TRACK_W, TRACK_H, TRACK_R, trackColor);

        // Track gradient shine (lighter top — convex volume)
        int shineA = (int) (8 * alpha / 255f);
        if (shineA > 0) {
            S.drawGradientRounded(ctx, pillX + 1, pillY + 1, TRACK_W - 2, (TRACK_H - 2) * 0.5f,
                    TRACK_R - 1,
                    TopographySurfaceRenderer.withAlpha(0xFFFFFF, shineA),
                    TopographySurfaceRenderer.withAlpha(0xFFFFFF, 0), 1f);
        }

        // OFF: inner shadow (sunken track)
        float offFade = 1f - toggleProgress;
        if (offFade > 0.1f) {
            S.drawInsetShadow(ctx, pillX, pillY, TRACK_W, TRACK_H, TRACK_R,
                    2, TopographySurfaceRenderer.withAlpha(0xFF000000, (int) (alpha * 0.15f * offFade)));
        }

        // Track glow when ON
        if (toggleProgress > 0.5f) {
            int glowAlpha = (int) (15 * toggleProgress * alpha / 255f);
            S.drawGlow(ctx, pillX, pillY, TRACK_W, TRACK_H, TRACK_R, 4,
                    TopographySurfaceRenderer.withAlpha(onColor, glowAlpha));
        }

        // ── Thumb ────────────────────────────────────────────────
        float thumbTravel = TRACK_W - THUMB_SIZE - THUMB_PAD * 2;
        float thumbX = pillX + THUMB_PAD + thumbTravel * toggleProgress;
        float thumbY = pillY + THUMB_PAD;

        // Thumb shadow
        S.drawShadow(ctx, thumbX, thumbY, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE / 2f, 2,
                TopographySurfaceRenderer.withAlpha(0xFF000000, (int) (alpha * 0.20f)));

        // Thumb ON glow
        if (toggleProgress > 0.5f) {
            float glowT = (toggleProgress - 0.5f) * 2f;
            int tGlowA = (int) (15 * glowT * alpha / 255f);
            S.drawGlow(ctx, thumbX, thumbY, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE / 2f, 3,
                    TopographySurfaceRenderer.withAlpha(onColor, tGlowA));
        }

        // Thumb body
        S.drawRounded(ctx, thumbX, thumbY, THUMB_SIZE, THUMB_SIZE, THUMB_SIZE / 2f,
                applyAlpha(0xFFFFFFFF, alpha));

        // Thumb specular highlight
        int hlA = (int) (alpha * 0.15f);
        if (hlA > 0) {
            S.drawRounded(ctx, thumbX + 1, thumbY + 1, THUMB_SIZE - 2, 2, (THUMB_SIZE - 2) / 2f,
                    TopographySurfaceRenderer.withAlpha(0xFFFFFF, hlA));
        }

        // Clickable width
        this.w = font.width(label) + 4 + TRACK_W;
    }

    @Override
    protected void onClicked() {
        setter.accept(!getter.getAsBoolean());
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
