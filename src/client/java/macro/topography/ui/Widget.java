package macro.topography.ui;

import net.minecraft.client.gui.DrawContext;

public abstract class Widget {

    protected float x, y, w, h;
    protected boolean hovered;
    protected boolean pressed;
    protected boolean visible = true;
    protected boolean enabled = true;

    protected float hoverProgress = 0f;
    protected float pressProgress = 0f;
    private long lastAnimNanos = -1;

    // Time constants in ms (reaches ~95% in 3×tau)
    private static final float HOVER_IN_TAU  = 50f;   // ~150ms to settle
    private static final float HOVER_OUT_TAU = 70f;   // ~210ms to settle
    private static final float PRESS_IN_TAU  = 20f;   // ~60ms to settle
    private static final float PRESS_OUT_TAU = 40f;   // ~120ms to settle

    public void setBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getW() { return w; }
    public float getH() { return h; }
    public float getBottom() { return y + h; }
    public float getRight() { return x + w; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean v) { this.visible = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; }

    public boolean isHovered() { return hovered; }
    public boolean isPressed() { return pressed; }

    public boolean containsPoint(double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public void updateHover(int mouseX, int mouseY) {
        hovered = visible && enabled && containsPoint(mouseX, mouseY);

        long now = System.nanoTime();
        if (lastAnimNanos < 0) {
            lastAnimNanos = now;
            return;
        }

        float dtMs = Math.min(50f, (now - lastAnimNanos) / 1_000_000f);
        lastAnimNanos = now;

        // Hover: exponential smoothing (frame-rate independent, no micro-stutter)
        float hoverTarget = hovered ? 1f : 0f;
        if (Math.abs(hoverProgress - hoverTarget) > 0.001f) {
            float tau = hovered ? HOVER_IN_TAU : HOVER_OUT_TAU;
            float decay = (float) Math.exp(-dtMs / tau);
            hoverProgress = hoverTarget + (hoverProgress - hoverTarget) * decay;
        } else {
            hoverProgress = hoverTarget;
        }

        // Press: exponential smoothing
        float pressTarget = pressed ? 1f : 0f;
        if (Math.abs(pressProgress - pressTarget) > 0.001f) {
            float tau = pressed ? PRESS_IN_TAU : PRESS_OUT_TAU;
            float decay = (float) Math.exp(-dtMs / tau);
            pressProgress = pressTarget + (pressProgress - pressTarget) * decay;
        } else {
            pressProgress = pressTarget;
        }
    }

    public abstract void render(DrawContext ctx, int mouseX, int mouseY, int alpha);

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !enabled) return false;
        if (button == 0 && containsPoint(mouseX, mouseY)) {
            pressed = true;
            onClicked();
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed) {
            pressed = false;
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    protected void onClicked() {}
}
