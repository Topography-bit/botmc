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
    private long lastAnimTime = 0;

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

        long now = System.currentTimeMillis();
        if (lastAnimTime > 0) {
            float dt = Math.min(50, now - lastAnimTime) / 1000f;

            float hoverTarget = hovered ? 1f : 0f;
            hoverProgress += (hoverTarget - hoverProgress) * Math.min(1f, 10f * dt);
            if (Math.abs(hoverProgress - hoverTarget) < 0.005f) hoverProgress = hoverTarget;

            float pressTarget = pressed ? 1f : 0f;
            pressProgress += (pressTarget - pressProgress) * Math.min(1f, 16f * dt);
            if (Math.abs(pressProgress - pressTarget) < 0.005f) pressProgress = pressTarget;
        }
        lastAnimTime = now;
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
