package macro.topography.ui;

import macro.topography.TopographySmoothTextRenderer;
import macro.topography.TopographySurfaceRenderer;
import macro.topography.TopographyUiConfig;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class KeybindWidget extends Widget {

    private static final int ACCENT = 0xFF00C8FF;
    private static final int TEXT_PRIMARY = 0xFFEAEAEF;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;

    private final TopographySmoothTextRenderer font;
    private final IntSupplier keyCodeGetter;
    private final Consumer<Integer> keyCodeSetter;
    private boolean capturing;

    public KeybindWidget(TopographySmoothTextRenderer font,
                         IntSupplier keyCodeGetter, Consumer<Integer> keyCodeSetter) {
        this.font = font;
        this.keyCodeGetter = keyCodeGetter;
        this.keyCodeSetter = keyCodeSetter;
        this.h = font.lineHeight() + 4;
    }

    public boolean isCapturing() {
        return capturing;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, int alpha) {
        if (!visible) return;

        int keyCode = keyCodeGetter.getAsInt();
        String bindText = capturing ? "[...]" : "[" + TopographyUiConfig.getKeyLabel(keyCode) + "]";
        String label = "bind: " + bindText;

        int color = capturing ? ACCENT : (hovered ? TEXT_PRIMARY : TEXT_SECONDARY);
        color = applyAlpha(color, alpha);
        font.draw(ctx, label, x, y, color);

        this.w = font.width(label);
    }

    @Override
    protected void onClicked() {
        capturing = !capturing;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!capturing) return false;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            keyCodeSetter.accept(GLFW.GLFW_KEY_UNKNOWN);
        } else {
            keyCodeSetter.accept(keyCode);
        }
        capturing = false;
        return true;
    }

    public void cancelCapture() {
        capturing = false;
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
