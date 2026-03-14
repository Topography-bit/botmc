package macro.topography.ui;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public class WidgetContainer extends Widget {

    protected final List<Widget> children = new ArrayList<>();

    public <T extends Widget> T add(T child) {
        children.add(child);
        return child;
    }

    public void remove(Widget child) {
        children.remove(child);
    }

    public void clear() {
        children.clear();
    }

    public List<Widget> getChildren() {
        return children;
    }

    @Override
    public void updateHover(int mouseX, int mouseY) {
        super.updateHover(mouseX, mouseY);
        for (Widget child : children) {
            child.updateHover(mouseX, mouseY);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;
        renderSelf(ctx, mouseX, mouseY, alpha);
        for (Widget child : children) {
            if (child.isVisible()) {
                child.render(ctx, mouseX, mouseY, alpha);
            }
        }
    }

    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        // Override in subclasses to draw container background/border
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !enabled) return false;
        // Children first (top to bottom in visual order, last added = on top)
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (Widget child : children) {
            child.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (Widget child : children) {
            if (child.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }
}
