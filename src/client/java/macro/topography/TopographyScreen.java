package macro.topography;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class TopographyScreen extends Screen {

    private static final TopographySurfaceRenderer SURFACES = new TopographySurfaceRenderer();
    private static final TopographySmoothTextRenderer DISPLAY_FONT = new TopographySmoothTextRenderer("Inter", 16, Font.BOLD);
    private static final TopographySmoothTextRenderer TITLE_FONT = new TopographySmoothTextRenderer("Inter", 14, Font.BOLD);
    private static final TopographySmoothTextRenderer BODY_FONT = new TopographySmoothTextRenderer("Inter", 13, Font.PLAIN);
    private static final TopographySmoothTextRenderer CAPTION_FONT = new TopographySmoothTextRenderer("Inter", 11, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL_FONT = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);
    private static final float MODULE_RESPONSE_MIN = 0.15f;
    private static final float MODULE_RESPONSE_MAX = 1.0f;
    private static final float UI_SCALE_MIN = 0.90f;
    private static final float UI_SCALE_MAX = 1.12f;
    private static final float CARD_HEIGHT = 150.0f;
    private static final float CARD_GAP = 16.0f;

    private final Screen parent;
    private final Map<String, Float> animations = new HashMap<>();

    private TopographyTab activeTab = TopographyTab.COMBAT;
    private TopographyModuleDefinition selectedModule = TopographyModuleDefinition.ZEALOT_BOT;
    private float scrollOffset;
    private boolean capturingBind;
    private boolean editingModelPath;
    private boolean draggingModuleSlider;
    private boolean draggingScaleSlider;
    private String editingModelText = "";
    private int caretIndex;
    private String openDropdownId;
    private long lastFrameNanos;
    private int lastMouseX;
    private int lastMouseY;
    private int blurCooldownFrames = 2;

    public TopographyScreen(Screen parent) {
        super(Text.literal("Topography"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        animations.clear();
        animations.put("screen-open", 0.0f);
        blurCooldownFrames = 2;
        ensureSelection();
        syncModelEditor();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        ensureSelection();

        float frameDelta = computeFrameDelta();
        float open = animate("screen-open", 1.0f, frameDelta, 0.22f);
        Theme theme = resolveTheme();
        Layout layout = getLayout();

        if (TopographyUiConfig.isBlurEnabled()) {
            if (blurCooldownFrames > 0) {
                blurCooldownFrames--;
            } else {
                try {
                    context.applyBlur();
                } catch (IllegalStateException ignored) {
                    blurCooldownFrames = 1;
                }
            }
        }

        drawBackdrop(context, theme, open);
        drawWindow(context, layout, theme, mouseX, mouseY, frameDelta, open);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        Layout layout = getLayout();
        ensureSelection();

        if (handleSidebarClick(layout, mouseX, mouseY)) {
            return true;
        }

        if (handleThemeSwitchClick(layout, mouseX, mouseY)) {
            return true;
        }

        if (activeTab == TopographyTab.VISUALS) {
            return handleVisualsClick(layout, mouseX, mouseY);
        }

        if (activeTab == TopographyTab.MOVEMENT) {
            openDropdownId = null;
            return true;
        }

        for (CardLayout card : buildCardLayouts(layout.cardsViewport)) {
            if (card.bindButton.contains(mouseX, mouseY)) {
                selectModule(card.module);
                capturingBind = true;
                editingModelPath = false;
                openDropdownId = null;
                return true;
            }

            if (card.actionButton.contains(mouseX, mouseY)) {
                TopographyController.toggle(card.module);
                return true;
            }

            if (card.card.contains(mouseX, mouseY)) {
                selectModule(card.module);
                return true;
            }
        }

        return handleModuleInspectorClick(layout, mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingModuleSlider = false;
        draggingScaleSlider = false;
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        Layout layout = getLayout();
        ensureSelection();

        if (draggingScaleSlider && activeTab == TopographyTab.VISUALS) {
            Rect track = getVisualScaleSlider(layout).track;
            updateUiScaleFromMouse(track, click.x());
            return true;
        }

        if (draggingModuleSlider && isModuleCategory() && selectedModule != null) {
            Rect track = getModuleResponseSlider(layout).track;
            updateModuleResponseFromMouse(selectedModule, track, click.x());
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!isModuleCategory()) {
            return false;
        }

        Layout layout = getLayout();
        if (!layout.cardsViewport.contains(mouseX, mouseY)) {
            return false;
        }

        float maxScroll = getMaxScroll(layout.cardsViewport);
        if (maxScroll <= 0.0f) {
            return true;
        }

        scrollOffset = clamp(scrollOffset - (float) verticalAmount * 32.0f, 0.0f, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();

        if (capturingBind && selectedModule != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                capturingBind = false;
                return true;
            }

            if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                TopographyUiConfig.setKeyCode(selectedModule, GLFW.GLFW_KEY_UNKNOWN);
                capturingBind = false;
                return true;
            }

            TopographyUiConfig.setKeyCode(selectedModule, keyCode);
            capturingBind = false;
            return true;
        }

        if (editingModelPath && selectedModule != null && selectedModule.usesModelPath()) {
            if (handleModelInputKeys(input)) {
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        return false;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (!editingModelPath || selectedModule == null || !selectedModule.usesModelPath()) {
            return false;
        }

        if (!input.isValidChar()) {
            return false;
        }

        String value = input.asString();
        editingModelText = editingModelText.substring(0, caretIndex) + value + editingModelText.substring(caretIndex);
        caretIndex += value.length();
        TopographyUiConfig.setModelPath(selectedModule, editingModelText);
        return true;
    }

    @Override
    public void close() {
        MinecraftClient minecraft = this.client;
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    public boolean isCapturingBind() {
        return capturingBind;
    }
    private void drawBackdrop(DrawContext context, Theme theme, float open) {
        context.fillGradient(0, 0, width, height, withAlpha(theme.backgroundTop, Math.round(244 * open)), withAlpha(theme.backgroundBottom, Math.round(252 * open)));
        SURFACES.drawGradientRounded(context, -140.0f, -90.0f, width * 0.62f, height * 0.48f, 160.0f, withAlpha(theme.accentStart, 20), withAlpha(theme.accentEnd, 4), open);
        SURFACES.drawGradientRounded(context, width * 0.54f, height * 0.40f, width * 0.38f, height * 0.36f, 120.0f, withAlpha(theme.accentEnd, 16), withAlpha(theme.accentStart, 4), open);
        context.fillGradient(0, 0, width, Math.round(height * 0.24f), withAlpha(theme.accentStart, 12), withAlpha(theme.backgroundTop, 0));
        drawNoisePattern(context, theme, 0.72f * open);
    }

    private void drawWindow(DrawContext context, Layout layout, Theme theme, int mouseX, int mouseY, float frameDelta, float open) {
        float offsetY = (1.0f - open) * 12.0f;
        Layout shifted = layout.offset(0.0f, offsetY);

        SURFACES.drawShadow(context, shifted.window.x, shifted.window.y, shifted.window.width, shifted.window.height, 12.0f, 18, withAlpha(0x000000, Math.round(88 * open)));
        SURFACES.drawGlassPanel(context, shifted.window.x, shifted.window.y, shifted.window.width, shifted.window.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));
        SURFACES.drawGradientRounded(context, shifted.window.x + 1.0f, shifted.window.y + 1.0f, shifted.window.width - 2.0f, Math.min(64.0f, shifted.window.height * 0.18f), 11.0f, withAlpha(theme.accentStart, 13), withAlpha(theme.backgroundTop, 0), 1.0f);

        drawSidebar(context, shifted, theme, mouseX, mouseY, frameDelta);
        drawHeader(context, shifted, theme);

        if (activeTab == TopographyTab.VISUALS) {
            drawVisualsCategory(context, shifted, theme, frameDelta);
        } else if (activeTab == TopographyTab.MOVEMENT) {
            drawMovementCategory(context, shifted, theme);
        } else {
            drawModuleCategory(context, shifted, theme, mouseX, mouseY, frameDelta);
        }
    }

    private void drawSidebar(DrawContext context, Layout layout, Theme theme, int mouseX, int mouseY, float frameDelta) {
        SURFACES.drawGlassPanel(context, layout.sidebar.x, layout.sidebar.y, layout.sidebar.width, layout.sidebar.height, 12.0f, withAlpha(theme.sidebarFill, 217), withAlpha(theme.windowBorder, 255));

        Rect logo = new Rect(layout.sidebar.x + 12.0f, layout.sidebar.y + 12.0f, layout.sidebar.width - 24.0f, 40.0f);
        SURFACES.drawOutlinedRounded(context, logo.x, logo.y, logo.width, logo.height, 10.0f, withAlpha(theme.cardFill, 178), withAlpha(theme.windowBorder, 255), 1.0f);
        DISPLAY_FONT.drawCentered(context, "TP", logo.centerX(), logo.y + 12.0f, theme.textPrimary);

        float itemY = layout.sidebar.y + 76.0f;
        for (TopographyTab tab : TopographyTab.values()) {
            Rect item = new Rect(layout.sidebar.x + 10.0f, itemY, layout.sidebar.width - 20.0f, 46.0f);
            boolean hovered = item.contains(mouseX, mouseY);
            boolean selected = tab == activeTab;
            float selectedProgress = animate("sidebar-" + tab.name(), selected ? 1.0f : 0.0f, frameDelta, 0.22f);
            float hoverProgress = animate("sidebar-hover-" + tab.name(), hovered ? 1.0f : 0.0f, frameDelta, 0.16f);

            if (selectedProgress > 0.01f || hoverProgress > 0.01f) {
                int fill = mixColor(withAlpha(theme.cardFill, 0), withAlpha(theme.cardHover, 208), Math.max(selectedProgress * 0.85f, hoverProgress * 0.65f));
                SURFACES.drawRounded(context, item.x, item.y, item.width, item.height, 10.0f, fill);
            }

            if (selectedProgress > 0.01f) {
                SURFACES.drawGradientRounded(context, item.x - 1.0f, item.y + 7.0f, 3.0f, item.height - 14.0f, 1.5f, theme.accentStart, theme.accentEnd, 1.0f);
            }

            Rect iconBox = new Rect(item.x + (item.width - 24.0f) / 2.0f, item.y + 11.0f, 24.0f, 24.0f);
            drawSidebarIcon(context, tab, iconBox, theme, Math.max(selectedProgress, 0.35f + hoverProgress * 0.30f), selected);
            itemY += 54.0f;
        }

        int activeCount = 0;
        for (TopographyModuleDefinition module : TopographyModuleDefinition.values()) {
            if (TopographyController.isActive(module)) {
                activeCount++;
            }
        }

        Rect activeBadge = new Rect(layout.sidebar.x + 8.0f, layout.sidebar.bottom() - 42.0f, layout.sidebar.width - 16.0f, 26.0f);
        SURFACES.drawOutlinedRounded(context, activeBadge.x, activeBadge.y, activeBadge.width, activeBadge.height, 9.0f, withAlpha(theme.cardFill, 200), withAlpha(theme.windowBorder, 255), 1.0f);
        SURFACES.drawRounded(context, activeBadge.x + 8.0f, activeBadge.centerY() - 3.0f, 6.0f, 6.0f, 3.0f, activeCount > 0 ? theme.accentStart : theme.textDisabled);
        CAPTION_FONT.drawCentered(context, Integer.toString(activeCount), activeBadge.centerX() + 4.0f, activeBadge.y + 8.0f, theme.textPrimary);
    }

    private void drawHeader(DrawContext context, Layout layout, Theme theme) {
        LABEL_FONT.draw(context, activeTab.title().toUpperCase(Locale.ROOT), layout.header.x, layout.header.y + 2.0f, theme.textSecondary);
        TITLE_FONT.draw(context, activeTab.title(), layout.header.x, layout.header.y + 18.0f, theme.textPrimary);
        CAPTION_FONT.draw(context, activeTab.subtitle(), layout.header.x, layout.header.y + 38.0f, theme.textSecondary);

        Rect themeSwitch = getThemeSwitchContainer(layout);
        SURFACES.drawOutlinedRounded(context, themeSwitch.x, themeSwitch.y, themeSwitch.width, themeSwitch.height, 9.0f, withAlpha(theme.cardFill, 196), withAlpha(theme.windowBorder, 255), 1.0f);
        List<String> presets = TopographyUiConfig.ACCENT_PRESETS;
        String activePreset = TopographyUiConfig.getAccentPreset();
        for (int i = 0; i < presets.size(); i++) {
            Rect option = getThemeSwitchOption(layout, i);
            boolean selected = presets.get(i).equals(activePreset);
            boolean hovered = option.contains(lastMouseX, lastMouseY);
            if (selected) {
                SURFACES.drawGradientRounded(context, option.x, option.y, option.width, option.height, 7.0f, theme.accentStart, theme.accentEnd, 0.88f);
            } else if (hovered) {
                SURFACES.drawRounded(context, option.x, option.y, option.width, option.height, 7.0f, withAlpha(theme.cardHover, 222));
            }
            CAPTION_FONT.drawCentered(context, presets.get(i), option.centerX(), option.y + 9.0f, selected ? theme.textPrimary : theme.textSecondary);
        }
    }

    private void drawModuleCategory(DrawContext context, Layout layout, Theme theme, int mouseX, int mouseY, float frameDelta) {
        float contentFade = animate("content-fade-" + activeTab.name(), 1.0f, frameDelta, 0.16f);
        float slide = animate("details-slide", 1.0f, frameDelta, 0.18f);

        SURFACES.drawGlassPanel(context, layout.cardsViewport.x, layout.cardsViewport.y, layout.cardsViewport.width, layout.cardsViewport.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));

        Rect inspector = layout.inspector.offset((1.0f - slide) * 18.0f, 0.0f);
        SURFACES.drawGlassPanel(context, inspector.x, inspector.y, inspector.width, inspector.height, 12.0f, withAlpha(theme.windowFill, Math.round(217 * contentFade)), withAlpha(theme.windowBorder, 255));

        String categoryLabel = activeTab == TopographyTab.COMBAT ? "MODULE STACK" : "RECORDING STACK";
        LABEL_FONT.draw(context, categoryLabel, layout.cardsViewport.x + 18.0f, layout.cardsViewport.y + 16.0f, theme.textSecondary);
        drawSectionDivider(context, layout.cardsViewport.x + 18.0f, layout.cardsViewport.y + 28.0f, layout.cardsViewport.width - 36.0f, theme.windowBorder);

        Rect clip = getCardsClip(layout.cardsViewport);
        context.enableScissor(Math.round(clip.x), Math.round(clip.y), Math.round(clip.right()), Math.round(clip.bottom()));
        for (CardLayout card : buildCardLayouts(layout.cardsViewport)) {
            drawCard(context, card, theme, mouseX, mouseY, frameDelta, clip);
        }
        context.disableScissor();
        drawScrollIndicator(context, clip, theme, layout.cardsViewport);
        drawModuleInspector(context, new Layout(layout.window, layout.sidebar, layout.header, layout.cardsViewport, inspector), theme, frameDelta);
    }
    private void drawCard(DrawContext context, CardLayout card, Theme theme, int mouseX, int mouseY, float frameDelta, Rect clip) {
        if (card.card.bottom() < clip.y - 12.0f || card.card.y > clip.bottom() + 12.0f) {
            return;
        }

        boolean hovered = card.card.contains(mouseX, mouseY);
        boolean selected = selectedModule == card.module;
        boolean active = TopographyController.isActive(card.module);
        float hover = animate("card-hover-" + card.module.id(), hovered ? 1.0f : 0.0f, frameDelta, 0.18f);
        float select = animate("card-select-" + card.module.id(), selected ? 1.0f : 0.0f, frameDelta, 0.18f);
        float lift = hover * 4.0f;
        Rect panel = card.card.offset(0.0f, -lift);

        int fill = mixColor(theme.cardFill, theme.cardHover, hover * 0.7f + select * 0.45f);
        int border = mixColor(withAlpha(0xFFFFFF, 10), withAlpha(theme.accentStart, 52), Math.max(hover * 0.75f, select));
        SURFACES.drawShadow(context, panel.x, panel.y, panel.width, panel.height, 8.0f, hovered ? 10 : 7, withAlpha(theme.glowColor, hovered ? 28 : 14));
        SURFACES.drawOutlinedRounded(context, panel.x, panel.y, panel.width, panel.height, 8.0f, fill, border, 1.0f);

        if (selected) {
            SURFACES.drawGradientRounded(context, panel.x, panel.y + 10.0f, 3.0f, panel.height - 20.0f, 1.5f, theme.accentStart, theme.accentEnd, 1.0f);
        }

        if (hover > 0.01f) {
            drawCardGlow(context, panel, mouseX, mouseY, theme, hover);
        }

        Rect pill = new Rect(panel.x + 14.0f, panel.y + 14.0f, Math.max(74.0f, BODY_FONT.width(card.module.actionLabel()) + 22.0f), 20.0f);
        SURFACES.drawRounded(context, pill.x, pill.y, pill.width, pill.height, 10.0f, withAlpha(theme.accentStart, 38));
        CAPTION_FONT.drawCentered(context, card.module.actionLabel().toUpperCase(Locale.ROOT), pill.centerX(), pill.y + 6.0f, theme.textPrimary);

        Rect toggleTrack = new Rect(panel.right() - 48.0f, panel.y + 14.0f, 34.0f, 18.0f);
        drawMiniToggle(context, toggleTrack, theme, active, hover * 0.45f);

        TITLE_FONT.draw(context, card.module.title(), panel.x + 14.0f, panel.y + 42.0f, theme.textPrimary);

        List<String> wrapped = BODY_FONT.wrap(card.module.description(), Math.round(panel.width - 28.0f), 2);
        for (int i = 0; i < wrapped.size(); i++) {
            BODY_FONT.draw(context, wrapped.get(i), panel.x + 14.0f, panel.y + 62.0f + i * 13.0f, theme.textSecondary);
        }

        Rect bind = card.bindButton.offset(0.0f, -lift);
        Rect action = card.actionButton.offset(0.0f, -lift);
        drawGhostButton(context, bind, theme, TopographyUiConfig.getBindLabel(card.module), bind.contains(mouseX, mouseY));
        drawPrimaryButton(context, action, theme, active ? "Stop" : "Start", action.contains(mouseX, mouseY), active);
    }

    private void drawModuleInspector(DrawContext context, Layout layout, Theme theme, float frameDelta) {
        if (selectedModule == null) {
            return;
        }

        Rect inner = layout.inspector.inset(18.0f);
        TopographyUiConfig.ModuleSettings settings = TopographyUiConfig.getSettings(selectedModule);
        boolean active = TopographyController.isActive(selectedModule);

        LABEL_FONT.draw(context, "DETAILS", inner.x, inner.y, theme.textSecondary);
        DISPLAY_FONT.draw(context, selectedModule.title(), inner.x, inner.y + 18.0f, theme.textPrimary);

        Rect statusChip = new Rect(inner.right() - 96.0f, inner.y + 12.0f, 96.0f, 24.0f);
        SURFACES.drawOutlinedRounded(context, statusChip.x, statusChip.y, statusChip.width, statusChip.height, 8.0f, withAlpha(active ? 0x14321E : theme.cardFill, 224), withAlpha(active ? theme.successColor : theme.windowBorder, 255), 1.0f);
        SURFACES.drawRounded(context, statusChip.x + 8.0f, statusChip.centerY() - 3.0f, 6.0f, 6.0f, 3.0f, active ? theme.successColor : theme.textDisabled);
        CAPTION_FONT.drawCentered(context, active ? "Running" : "Idle", statusChip.centerX() + 4.0f, statusChip.y + 8.0f, active ? theme.successColor : theme.textSecondary);

        List<String> wrapped = BODY_FONT.wrap(selectedModule.description(), Math.round(inner.width - 18.0f), 2);
        for (int i = 0; i < wrapped.size(); i++) {
            BODY_FONT.draw(context, wrapped.get(i), inner.x, inner.y + 44.0f + i * 13.0f, theme.textSecondary);
        }

        float sectionY = inner.y + 84.0f;
        LABEL_FONT.draw(context, "CONTROLS", inner.x, sectionY, theme.textSecondary);
        drawSectionDivider(context, inner.x, sectionY + 12.0f, inner.width, theme.windowBorder);

        drawCheckboxRow(context, getModuleBindToggle(layout), theme, "ALLOW KEYBIND", "Enable quick start / stop from the assigned key.", TopographyUiConfig.isBindEnabled(selectedModule), frameDelta, "bind-enabled");
        drawBindButton(context, getModuleBindButton(layout), theme, selectedModule, frameDelta);

        float tuningY = getModuleBindButton(layout).bottom() + 26.0f;
        LABEL_FONT.draw(context, "TUNING", inner.x, tuningY, theme.textSecondary);
        drawSectionDivider(context, inner.x, tuningY + 12.0f, inner.width, theme.windowBorder);

        SliderBounds moduleSlider = getModuleResponseSlider(layout);
        drawSliderRow(context, moduleSlider.hitbox, moduleSlider.track, theme, "RESPONSE CURVE", "Smoothness and reaction intensity.", settings.responseCurve, frameDelta, false, draggingModuleSlider, "module-response");
        drawDropdownRow(context, getModuleProfileDropdown(layout), theme, "PROFILE", TopographyUiConfig.getProfile(selectedModule), TopographyUiConfig.MODULE_PROFILES, frameDelta, "module-profile");

        if (selectedModule.usesModelPath()) {
            drawModelInput(context, getModuleModelRow(layout), getModelInputBox(layout), theme, frameDelta);
        }

        Rect actionButton = getModuleActionButton(layout);
        drawPrimaryButton(context, actionButton, theme, active ? "Stop Module" : "Start Module", actionButton.contains(lastMouseX, lastMouseY), active);
    }

    private void drawVisualsCategory(DrawContext context, Layout layout, Theme theme, float frameDelta) {
        Rect preview = layout.cardsViewport;
        Rect inspector = layout.inspector;
        SURFACES.drawGlassPanel(context, preview.x, preview.y, preview.width, preview.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));
        SURFACES.drawGlassPanel(context, inspector.x, inspector.y, inspector.width, inspector.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));

        LABEL_FONT.draw(context, "VISUAL SYSTEM", preview.x + 18.0f, preview.y + 16.0f, theme.textSecondary);
        drawSectionDivider(context, preview.x + 18.0f, preview.y + 28.0f, preview.width - 36.0f, theme.windowBorder);
        TITLE_FONT.draw(context, TopographyUiConfig.getAccentPreset() + " shell", preview.x + 18.0f, preview.y + 40.0f, theme.textPrimary);
        BODY_FONT.draw(context, "Backdrop blur, dark glass panels, primary gradient and soft glow are all configured here.", preview.x + 18.0f, preview.y + 58.0f, theme.textSecondary);

        Rect showcase = new Rect(preview.x + 18.0f, preview.y + 92.0f, preview.width - 36.0f, 180.0f);
        SURFACES.drawOutlinedRounded(context, showcase.x, showcase.y, showcase.width, showcase.height, 8.0f, theme.cardFill, withAlpha(0xFFFFFF, 10), 1.0f);
        SURFACES.drawGradientRounded(context, showcase.x, showcase.y, showcase.width, 38.0f, 8.0f, withAlpha(theme.accentStart, 18), withAlpha(theme.backgroundTop, 0), 1.0f);
        Rect themePill = new Rect(showcase.x + 16.0f, showcase.y + 16.0f, 132.0f, 22.0f);
        SURFACES.drawGradientRounded(context, themePill.x, themePill.y, themePill.width, themePill.height, 11.0f, theme.accentStart, theme.accentEnd, 0.86f);
        CAPTION_FONT.drawCentered(context, TopographyUiConfig.getAccentPreset(), themePill.centerX(), themePill.y + 7.0f, theme.textPrimary);
        TITLE_FONT.draw(context, "Preview Surface", showcase.x + 16.0f, showcase.y + 54.0f, theme.textPrimary);
        BODY_FONT.draw(context, "Cards now use soft borders, left accent rails, badge pills and restrained lighting instead of thick generic blocks.", showcase.x + 16.0f, showcase.y + 76.0f, theme.textSecondary);

        Rect chips = new Rect(preview.x + 18.0f, preview.bottom() - 92.0f, preview.width - 36.0f, 58.0f);
        SURFACES.drawOutlinedRounded(context, chips.x, chips.y, chips.width, chips.height, 8.0f, theme.cardFill, withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, "PALETTE", chips.x + 12.0f, chips.y + 10.0f, theme.textSecondary);
        float chipX = chips.x + 12.0f;
        for (int color : new int[]{theme.accentStart, theme.accentEnd, 0xFFE0E0E6, 0xFF8888A0, theme.backgroundTop}) {
            int renderedColor = color == theme.backgroundTop ? withAlpha(0xFFFFFF, 18) : color;
            SURFACES.drawRounded(context, chipX, chips.y + 28.0f, 28.0f, 14.0f, 7.0f, renderedColor);
            chipX += 38.0f;
        }

        Rect inner = inspector.inset(18.0f);
        LABEL_FONT.draw(context, "SETTINGS", inner.x, inner.y, theme.textSecondary);
        drawSectionDivider(context, inner.x, inner.y + 12.0f, inner.width, theme.windowBorder);
        drawCheckboxRow(context, getVisualBlurToggle(layout), theme, "BACKGROUND BLUR", "Backplate blur under glass panels.", TopographyUiConfig.isBlurEnabled(), frameDelta, "ui-blur");
        drawCheckboxRow(context, getVisualMotionToggle(layout), theme, "EASE MOTION", "Fade, hover and slide transitions.", TopographyUiConfig.isMotionEnabled(), frameDelta, "ui-motion");
        SliderBounds scaleSlider = getVisualScaleSlider(layout);
        drawSliderRow(context, scaleSlider.hitbox, scaleSlider.track, theme, "INTERFACE SCALE", "Shell scale factor.", TopographyUiConfig.getInterfaceScale(), frameDelta, true, draggingScaleSlider, "ui-scale");
        drawDropdownRow(context, getVisualAccentDropdown(layout), theme, "ACCENT PRESET", TopographyUiConfig.getAccentPreset(), TopographyUiConfig.ACCENT_PRESETS, frameDelta, "ui-accent");
    }

    private void drawMovementCategory(DrawContext context, Layout layout, Theme theme) {
        Rect left = layout.cardsViewport;
        Rect right = layout.inspector;
        SURFACES.drawGlassPanel(context, left.x, left.y, left.width, left.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));
        SURFACES.drawGlassPanel(context, right.x, right.y, right.width, right.height, 12.0f, withAlpha(theme.windowFill, 217), withAlpha(theme.windowBorder, 255));

        LABEL_FONT.draw(context, "MOVEMENT", left.x + 18.0f, left.y + 16.0f, theme.textSecondary);
        drawSectionDivider(context, left.x + 18.0f, left.y + 28.0f, left.width - 36.0f, theme.windowBorder);
        TITLE_FONT.draw(context, "Reserved for pathing tools", left.x + 18.0f, left.y + 42.0f, theme.textPrimary);
        BODY_FONT.draw(context, "The new shell already supports cards, flyouts, sliders and toggles, so movement modules can slot in without another renderer rewrite.", left.x + 18.0f, left.y + 60.0f, theme.textSecondary);

        Rect placeholder = new Rect(left.x + 18.0f, left.y + 96.0f, left.width - 36.0f, 156.0f);
        SURFACES.drawOutlinedRounded(context, placeholder.x, placeholder.y, placeholder.width, placeholder.height, 8.0f, theme.cardFill, withAlpha(0xFFFFFF, 10), 1.0f);
        Rect chip = new Rect(placeholder.x + 16.0f, placeholder.y + 16.0f, 92.0f, 20.0f);
        SURFACES.drawGradientRounded(context, chip.x, chip.y, chip.width, chip.height, 10.0f, theme.accentStart, theme.accentEnd, 0.84f);
        CAPTION_FONT.drawCentered(context, "Reserved", chip.centerX(), chip.y + 6.0f, theme.textPrimary);
        TITLE_FONT.draw(context, "Future Modules", placeholder.x + 16.0f, placeholder.y + 52.0f, theme.textPrimary);
        BODY_FONT.draw(context, "Waypoint editing, movement assists and route widgets can drop into this layout without changing the rendering stack again.", placeholder.x + 16.0f, placeholder.y + 74.0f, theme.textSecondary);

        LABEL_FONT.draw(context, "COMPONENTS", right.x + 18.0f, right.y + 16.0f, theme.textSecondary);
        drawSectionDivider(context, right.x + 18.0f, right.y + 28.0f, right.width - 36.0f, theme.windowBorder);
        float chipY = right.y + 44.0f;
        for (String label : List.of("Animated toggles", "Gradient sliders", "Flyout dropdowns", "Glass sections")) {
            SURFACES.drawOutlinedRounded(context, right.x + 18.0f, chipY, right.width - 36.0f, 30.0f, 8.0f, theme.cardFill, withAlpha(0xFFFFFF, 10), 1.0f);
            BODY_FONT.draw(context, label, right.x + 30.0f, chipY + 9.0f, theme.textPrimary);
            chipY += 40.0f;
        }
    }

    private void drawCheckboxRow(DrawContext context, Rect row, Theme theme, String title, String description, boolean value, float frameDelta, String id) {
        float toggle = animate(id + "-toggle", value ? 1.0f : 0.0f, frameDelta, 0.20f);
        SURFACES.drawOutlinedRounded(context, row.x, row.y, row.width, row.height, 8.0f, withAlpha(theme.cardFill, 200), withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, title, row.x + 14.0f, row.y + 10.0f, theme.textSecondary);
        CAPTION_FONT.draw(context, description, row.x + 14.0f, row.y + 24.0f, theme.textSecondary);

        Rect toggleTrack = new Rect(row.right() - 54.0f, row.y + 15.0f, 36.0f, 20.0f);
        int trackFill = mixColor(0xFF2A2A36, withAlpha(theme.accentEnd, 210), toggle);
        SURFACES.drawRounded(context, toggleTrack.x, toggleTrack.y, toggleTrack.width, toggleTrack.height, 10.0f, trackFill);
        float knobX = toggleTrack.x + 2.0f + toggle * 16.0f;
        SURFACES.drawShadow(context, knobX, toggleTrack.y + 2.0f, 16.0f, 16.0f, 8.0f, 6, withAlpha(theme.glowColor, 24));
        SURFACES.drawRounded(context, knobX, toggleTrack.y + 2.0f, 16.0f, 16.0f, 8.0f, 0xFFFFFFFF);
    }

    private void drawBindButton(DrawContext context, Rect row, Theme theme, TopographyModuleDefinition module, float frameDelta) {
        float capture = animate("bind-capture", capturingBind ? 1.0f : 0.0f, frameDelta, 0.20f);
        SURFACES.drawOutlinedRounded(context, row.x, row.y, row.width, row.height, 8.0f, withAlpha(theme.cardFill, 200), withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, "HOTKEY", row.x + 14.0f, row.y + 10.0f, theme.textSecondary);
        CAPTION_FONT.draw(context, capturingBind ? "Press a key. Delete clears the bind." : "Assign a start / stop key for this module.", row.x + 14.0f, row.y + 24.0f, theme.textSecondary);

        Rect chip = new Rect(row.right() - 118.0f, row.y + 12.0f, 104.0f, 28.0f);
        if (capture > 0.01f) {
            SURFACES.drawGradientRounded(context, chip.x, chip.y, chip.width, chip.height, 6.0f, theme.accentStart, theme.accentEnd, 0.76f + capture * 0.24f);
        } else {
            SURFACES.drawOutlinedRounded(context, chip.x, chip.y, chip.width, chip.height, 6.0f, withAlpha(theme.cardFill, 220), withAlpha(theme.textSecondary, 120), 1.0f);
        }
        BODY_FONT.drawCentered(context, capturingBind ? "Press key" : TopographyUiConfig.getBindLabel(module), chip.centerX(), chip.y + 9.0f, theme.textPrimary);
    }

    private void drawSliderRow(DrawContext context, Rect row, Rect track, Theme theme, String title, String description, float value, float frameDelta, boolean uiScale, boolean dragging, String id) {
        boolean hovered = row.contains(lastMouseX, lastMouseY);
        float hover = animate(id + "-hover", hovered || dragging ? 1.0f : 0.0f, frameDelta, 0.18f);
        float progress = uiScale ? normalizeUiScale(value) : normalizeModuleResponse(value);
        float knobSize = 12.0f + hover * 4.0f;
        float centerX = track.x + track.width * progress;
        float knobX = centerX - knobSize / 2.0f;
        float knobY = track.centerY() - knobSize / 2.0f;
        String valueLabel = uiScale
            ? Math.round(value * 100.0f) + "%"
            : String.format(java.util.Locale.ROOT, "%.2f", value);

        SURFACES.drawOutlinedRounded(context, row.x, row.y, row.width, row.height, 8.0f, withAlpha(theme.cardFill, 200), withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, title, row.x + 14.0f, row.y + 10.0f, theme.textSecondary);
        CAPTION_FONT.draw(context, description, row.x + 14.0f, row.y + 24.0f, theme.textSecondary);
        BODY_FONT.drawRight(context, valueLabel, row.right() - 14.0f, row.y + 10.0f, theme.textPrimary);

        SURFACES.drawRounded(context, track.x, track.y, track.width, track.height, 2.0f, withAlpha(0xFFFFFF, 20));
        float fillWidth = Math.max(track.height, track.width * progress);
        SURFACES.drawGradientRounded(context, track.x, track.y, fillWidth, track.height, 2.0f, theme.accentStart, theme.accentEnd, 1.0f);
        SURFACES.drawShadow(context, knobX, knobY, knobSize, knobSize, knobSize / 2.0f, 10, withAlpha(theme.glowColor, 28));
        SURFACES.drawRounded(context, knobX, knobY, knobSize, knobSize, knobSize / 2.0f, 0xFFFFFFFF);
    }

    private void drawDropdownRow(DrawContext context, DropdownBounds dropdown, Theme theme, String title, String value, List<String> options, float frameDelta, String id) {
        Rect row = dropdown.hitbox;
        boolean open = id.equals(openDropdownId);
        float openProgress = animate(id + "-open", open ? 1.0f : 0.0f, frameDelta, 0.18f);

        SURFACES.drawOutlinedRounded(context, row.x, row.y, row.width, row.height, 8.0f, withAlpha(theme.cardFill, 200), withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, title, row.x + 14.0f, row.y + 10.0f, theme.textSecondary);
        CAPTION_FONT.draw(context, "Select stored profile", row.x + 14.0f, row.y + 24.0f, theme.textSecondary);

        Rect chip = new Rect(row.right() - 146.0f, row.y + 12.0f, 132.0f, 28.0f);
        SURFACES.drawOutlinedRounded(context, chip.x, chip.y, chip.width, chip.height, 6.0f, withAlpha(theme.cardFill, 220), withAlpha(theme.textSecondary, 100), 1.0f);
        BODY_FONT.drawCentered(context, BODY_FONT.ellipsize(value, Math.round(chip.width - 34.0f)), chip.centerX() - 8.0f, chip.y + 10.0f, 0xFFFFFFFF);
        BODY_FONT.draw(context, open ? "^" : "v", chip.right() - 18.0f, chip.y + 10.0f, 0xFFE8E8E8);

        if (openProgress <= 0.01f) {
            return;
        }

        Rect menu = dropdown.menu;
        float animatedHeight = menu.height * openProgress;
        Rect visibleMenu = new Rect(menu.x, menu.y, menu.width, animatedHeight);
        SURFACES.drawShadow(context, menu.x, menu.y, menu.width, menu.height, 8.0f, 14, withAlpha(theme.glowColor, 18));
        SURFACES.drawGlassPanel(context, menu.x, menu.y, menu.width, menu.height, 8.0f, withAlpha(theme.cardFill, 230), withAlpha(theme.windowBorder, 255));
        context.enableScissor(Math.round(visibleMenu.x), Math.round(visibleMenu.y), Math.round(visibleMenu.right()), Math.round(visibleMenu.bottom()));
        for (int i = 0; i < options.size(); i++) {
            Rect optionRect = getDropdownOptionRect(dropdown, i);
            boolean selected = options.get(i).equals(value);
            boolean hovered = optionRect.contains(lastMouseX, lastMouseY);
            if (selected) {
                SURFACES.drawGradientRounded(context, optionRect.x, optionRect.y, optionRect.width, optionRect.height, 6.0f, theme.accentStart, theme.accentEnd, 0.76f);
            } else if (hovered) {
                SURFACES.drawRounded(context, optionRect.x, optionRect.y, optionRect.width, optionRect.height, 6.0f, withAlpha(0xFFFFFF, 10));
            }
            BODY_FONT.draw(context, options.get(i), optionRect.x + 12.0f, optionRect.y + 8.0f, theme.textPrimary);
        }
        context.disableScissor();
    }

    private void drawModelInput(DrawContext context, Rect row, Rect inputBox, Theme theme, float frameDelta) {
        boolean focused = editingModelPath;
        float focus = animate("model-input-focus", focused ? 1.0f : 0.0f, frameDelta, 0.18f);
        SURFACES.drawOutlinedRounded(context, row.x, row.y, row.width, row.height, 8.0f, withAlpha(theme.cardFill, 200), withAlpha(0xFFFFFF, 10), 1.0f);
        LABEL_FONT.draw(context, "MODEL PATH", row.x + 14.0f, row.y + 10.0f, theme.textSecondary);
        CAPTION_FONT.draw(context, "Absolute or relative ONNX file path.", row.x + 14.0f, row.y + 24.0f, theme.textSecondary);

        int border = mixColor(withAlpha(0xFFFFFF, 12), withAlpha(theme.accentStart, 178), focus);
        SURFACES.drawOutlinedRounded(context, inputBox.x, inputBox.y, inputBox.width, inputBox.height, 6.0f, withAlpha(theme.windowFill, 180), border, 1.0f);
        String rawValue = editingModelText == null || editingModelText.isBlank() ? selectedModule.defaultModelPath() : editingModelText;
        String visible = BODY_FONT.ellipsize(rawValue, Math.round(inputBox.width - 18.0f));
        BODY_FONT.draw(context, visible, inputBox.x + 10.0f, inputBox.y + 6.0f, theme.textPrimary);

        if (focused && ((System.currentTimeMillis() / 500L) % 2L == 0L)) {
            int visibleCaret = clamp(caretIndex, 0, visible.length());
            float caretX = inputBox.x + 10.0f + BODY_FONT.width(visible.substring(0, visibleCaret));
            SURFACES.drawRounded(context, caretX, inputBox.y + 5.0f, 1.5f, inputBox.height - 10.0f, 0.75f, 0xFFFFFFFF);
        }
    }

    private void drawScrollIndicator(DrawContext context, Rect clip, Theme theme, Rect viewport) {
        float maxScroll = getMaxScroll(viewport);
        if (maxScroll <= 0.0f) {
            return;
        }

        float visibleHeight = clip.height;
        float contentHeight = getCardsContentHeight(viewport);
        float trackHeight = visibleHeight - 10.0f;
        float thumbHeight = Math.max(42.0f, trackHeight * (visibleHeight / contentHeight));
        float travel = Math.max(0.0f, trackHeight - thumbHeight);
        float progress = maxScroll <= 0.0f ? 0.0f : scrollOffset / maxScroll;

        Rect rail = new Rect(clip.right() - 4.0f, clip.y + 5.0f, 4.0f, trackHeight);
        Rect thumb = new Rect(rail.x, rail.y + travel * progress, rail.width, thumbHeight);
        SURFACES.drawRounded(context, rail.x, rail.y, rail.width, rail.height, 2.0f, withAlpha(0xFFFFFF, 10));
        SURFACES.drawGradientRounded(context, thumb.x, thumb.y, thumb.width, thumb.height, 2.0f, theme.accentStart, theme.accentEnd, 0.92f);
    }

    private void drawNoisePattern(DrawContext context, Theme theme, float strength) {
        if (strength <= 0.01f) {
            return;
        }

        int softNoise = withAlpha(0xFFFFFF, Math.max(1, Math.round(8.0f * strength)));
        int accentNoise = withAlpha(theme.accentStart, Math.max(1, Math.round(5.0f * strength)));
        int step = 14;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int hash = x * 734287 + y * 912931 + 17;
                if ((hash & 3) != 0) {
                    continue;
                }

                int pixelX = Math.min(width - 1, x + Math.abs(hash % 3));
                int pixelY = Math.min(height - 1, y + Math.abs((hash >>> 3) % 3));
                context.fill(pixelX, pixelY, pixelX + 1, pixelY + 1, (hash & 8) == 0 ? softNoise : accentNoise);
            }
        }
    }

    private void drawSidebarIcon(DrawContext context, TopographyTab tab, Rect box, Theme theme, float intensity, boolean selected) {
        int iconColor = selected
            ? mixColor(theme.accentStart, theme.accentEnd, 0.52f)
            : mixColor(theme.textDisabled, theme.textPrimary, clamp(intensity, 0.0f, 1.0f));
        float x = box.x;
        float y = box.y;

        switch (tab) {
            case COMBAT -> {
                SURFACES.drawRounded(context, x + 10.0f, y + 2.0f, 4.0f, 10.0f, 2.0f, iconColor);
                SURFACES.drawRounded(context, x + 7.0f, y + 11.0f, 10.0f, 3.0f, 1.5f, iconColor);
                SURFACES.drawRounded(context, x + 10.0f, y + 13.0f, 4.0f, 7.0f, 2.0f, iconColor);
                SURFACES.drawRounded(context, x + 8.0f, y + 20.0f, 8.0f, 2.0f, 1.0f, iconColor);
            }
            case MOVEMENT -> {
                SURFACES.drawRounded(context, x + 4.0f, y + 11.0f, 16.0f, 2.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 15.0f, y + 8.0f, 2.0f, 8.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 6.0f, y + 8.0f, 2.0f, 8.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 4.0f, y + 6.0f, 6.0f, 2.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 14.0f, y + 6.0f, 6.0f, 2.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 4.0f, y + 16.0f, 6.0f, 2.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 14.0f, y + 16.0f, 6.0f, 2.0f, 1.0f, iconColor);
            }
            case VISUALS -> {
                SURFACES.drawRounded(context, x + 3.0f, y + 9.0f, 18.0f, 6.0f, 3.0f, iconColor);
                SURFACES.drawRounded(context, x + 7.0f, y + 10.0f, 10.0f, 4.0f, 2.0f, withAlpha(theme.backgroundTop, 240));
                SURFACES.drawRounded(context, x + 9.0f, y + 9.0f, 6.0f, 6.0f, 3.0f, iconColor);
            }
            case PLAYER -> {
                SURFACES.drawRounded(context, x + 8.0f, y + 3.0f, 8.0f, 8.0f, 4.0f, iconColor);
                SURFACES.drawRounded(context, x + 6.0f, y + 12.0f, 12.0f, 8.0f, 4.0f, iconColor);
                SURFACES.drawRounded(context, x + 5.0f, y + 20.0f, 5.0f, 2.0f, 1.0f, iconColor);
                SURFACES.drawRounded(context, x + 14.0f, y + 20.0f, 5.0f, 2.0f, 1.0f, iconColor);
            }
        }
    }

    private void drawSectionDivider(DrawContext context, float x, float y, float width, int color) {
        SURFACES.drawRounded(context, x, y, width, 1.0f, 0.5f, withAlpha(color, 62));
    }

    private void drawCardGlow(DrawContext context, Rect panel, int mouseX, int mouseY, Theme theme, float strength) {
        float glowCenterX = clamp(mouseX, panel.x + 24.0f, panel.right() - 24.0f);
        float glowCenterY = clamp(mouseY, panel.y + 20.0f, panel.bottom() - 20.0f);
        for (int i = 0; i < 3; i++) {
            float size = 54.0f + i * 26.0f;
            float alpha = Math.max(3.0f, (18.0f - i * 5.0f) * strength);
            SURFACES.drawRounded(
                context,
                glowCenterX - size / 2.0f,
                glowCenterY - size / 3.0f,
                size,
                size * 0.62f,
                16.0f + i * 5.0f,
                withAlpha(i == 0 ? theme.accentEnd : theme.accentStart, Math.round(alpha))
            );
        }
    }

    private void drawMiniToggle(DrawContext context, Rect track, Theme theme, boolean active, float hoverBlend) {
        float progress = active ? 1.0f : 0.0f;
        int fill = mixColor(0xFF2A2A36, withAlpha(theme.accentEnd, 224), progress);
        if (hoverBlend > 0.01f && !active) {
            fill = mixColor(fill, withAlpha(theme.textSecondary, 164), hoverBlend);
        }
        SURFACES.drawRounded(context, track.x, track.y, track.width, track.height, track.height / 2.0f, fill);
        float knobSize = track.height - 4.0f;
        float knobX = track.x + 2.0f + progress * (track.width - knobSize - 4.0f);
        SURFACES.drawShadow(context, knobX, track.y + 2.0f, knobSize, knobSize, knobSize / 2.0f, 5, withAlpha(theme.glowColor, 20 + Math.round(hoverBlend * 18.0f)));
        SURFACES.drawRounded(context, knobX, track.y + 2.0f, knobSize, knobSize, knobSize / 2.0f, 0xFFFFFFFF);
    }

    private void drawGhostButton(DrawContext context, Rect button, Theme theme, String label, boolean hovered) {
        int border = hovered ? withAlpha(theme.accentStart, 138) : withAlpha(theme.textSecondary, 92);
        int fill = hovered ? withAlpha(theme.cardHover, 214) : withAlpha(theme.cardFill, 188);
        if (hovered) {
            SURFACES.drawShadow(context, button.x, button.y, button.width, button.height, 6.0f, 8, withAlpha(theme.glowColor, 14));
        }
        SURFACES.drawOutlinedRounded(context, button.x, button.y, button.width, button.height, 6.0f, fill, border, 1.0f);
        BODY_FONT.drawCentered(context, BODY_FONT.ellipsize(label, Math.round(button.width - 16.0f)), button.centerX(), button.y + 9.0f, hovered ? theme.textPrimary : theme.textSecondary);
    }

    private void drawPrimaryButton(DrawContext context, Rect button, Theme theme, String label, boolean hovered, boolean active) {
        float glow = hovered ? 1.0f : (active ? 0.75f : 0.45f);
        SURFACES.drawShadow(context, button.x, button.y, button.width, button.height, 6.0f, hovered ? 12 : 9, withAlpha(theme.glowColor, Math.round(24.0f * glow)));
        SURFACES.drawGradientRounded(context, button.x, button.y, button.width, button.height, 6.0f, theme.accentStart, theme.accentEnd, hovered ? 1.0f : 0.9f);
        SURFACES.drawOutlinedRounded(context, button.x, button.y, button.width, button.height, 6.0f, withAlpha(0xFFFFFF, 0), withAlpha(0xFFFFFF, hovered ? 36 : 18), 1.0f);
        BODY_FONT.drawCentered(context, label, button.centerX(), button.y + 9.0f, theme.textPrimary);
    }

    private boolean handleThemeSwitchClick(Layout layout, double mouseX, double mouseY) {
        for (int i = 0; i < TopographyUiConfig.ACCENT_PRESETS.size(); i++) {
            Rect option = getThemeSwitchOption(layout, i);
            if (option.contains(mouseX, mouseY)) {
                TopographyUiConfig.setAccentPreset(TopographyUiConfig.ACCENT_PRESETS.get(i));
                return true;
            }
        }
        return false;
    }

    private boolean handleSidebarClick(Layout layout, double mouseX, double mouseY) {
        float itemY = layout.sidebar.y + 76.0f;
        for (TopographyTab tab : TopographyTab.values()) {
            Rect item = new Rect(layout.sidebar.x + 10.0f, itemY, layout.sidebar.width - 20.0f, 46.0f);
            if (item.contains(mouseX, mouseY)) {
                activeTab = tab;
                openDropdownId = null;
                draggingModuleSlider = false;
                draggingScaleSlider = false;
                capturingBind = false;
                editingModelPath = false;
                scrollOffset = 0.0f;
                ensureSelection();
                return true;
            }
            itemY += 54.0f;
        }
        return false;
    }

    private boolean handleModuleInspectorClick(Layout layout, double mouseX, double mouseY) {
        if (selectedModule == null) {
            return false;
        }

        Rect bindToggle = getModuleBindToggle(layout);
        if (bindToggle.contains(mouseX, mouseY)) {
            TopographyUiConfig.setBindEnabled(selectedModule, !TopographyUiConfig.isBindEnabled(selectedModule));
            openDropdownId = null;
            return true;
        }

        Rect bindButton = getModuleBindButton(layout);
        if (bindButton.contains(mouseX, mouseY)) {
            capturingBind = !capturingBind;
            editingModelPath = false;
            openDropdownId = null;
            return true;
        }

        SliderBounds responseSlider = getModuleResponseSlider(layout);
        if (responseSlider.hitbox.contains(mouseX, mouseY)) {
            draggingModuleSlider = true;
            updateModuleResponseFromMouse(selectedModule, responseSlider.track, mouseX);
            openDropdownId = null;
            return true;
        }

        if (handleDropdownClick(
            getModuleProfileDropdown(layout),
            "module-profile",
            TopographyUiConfig.MODULE_PROFILES,
            value -> TopographyUiConfig.setProfile(selectedModule, value),
            mouseX,
            mouseY
        )) {
            editingModelPath = false;
            return true;
        }

        if (selectedModule.usesModelPath()) {
            Rect row = getModuleModelRow(layout);
            Rect inputBox = getModelInputBox(layout);
            if (row.contains(mouseX, mouseY)) {
                editingModelPath = inputBox.contains(mouseX, mouseY);
                if (editingModelPath) {
                    editingModelText = TopographyUiConfig.getModelPath(selectedModule);
                    caretIndex = editingModelText.length();
                }
                openDropdownId = null;
                capturingBind = false;
                return true;
            }
        }

        Rect actionButton = getModuleActionButton(layout);
        if (actionButton.contains(mouseX, mouseY)) {
            TopographyController.toggle(selectedModule);
            return true;
        }

        if (layout.inspector.contains(mouseX, mouseY)) {
            openDropdownId = null;
            editingModelPath = false;
            capturingBind = false;
            return true;
        }

        return false;
    }

    private boolean handleVisualsClick(Layout layout, double mouseX, double mouseY) {
        if (getVisualBlurToggle(layout).contains(mouseX, mouseY)) {
            TopographyUiConfig.setBlurEnabled(!TopographyUiConfig.isBlurEnabled());
            openDropdownId = null;
            return true;
        }

        if (getVisualMotionToggle(layout).contains(mouseX, mouseY)) {
            TopographyUiConfig.setMotionEnabled(!TopographyUiConfig.isMotionEnabled());
            openDropdownId = null;
            return true;
        }

        SliderBounds scaleSlider = getVisualScaleSlider(layout);
        if (scaleSlider.hitbox.contains(mouseX, mouseY)) {
            draggingScaleSlider = true;
            updateUiScaleFromMouse(scaleSlider.track, mouseX);
            openDropdownId = null;
            return true;
        }

        if (handleDropdownClick(
            getVisualAccentDropdown(layout),
            "ui-accent",
            TopographyUiConfig.ACCENT_PRESETS,
            TopographyUiConfig::setAccentPreset,
            mouseX,
            mouseY
        )) {
            return true;
        }

        if (layout.window.contains(mouseX, mouseY)) {
            openDropdownId = null;
            return true;
        }

        return false;
    }

    private boolean handleDropdownClick(DropdownBounds dropdown, String id, List<String> options, Consumer<String> setter, double mouseX, double mouseY) {
        if (dropdown.hitbox.contains(mouseX, mouseY)) {
            openDropdownId = id.equals(openDropdownId) ? null : id;
            return true;
        }

        if (!id.equals(openDropdownId)) {
            return false;
        }

        for (int i = 0; i < options.size(); i++) {
            Rect optionRect = getDropdownOptionRect(dropdown, i);
            if (optionRect.contains(mouseX, mouseY)) {
                setter.accept(options.get(i));
                openDropdownId = null;
                return true;
            }
        }

        if (dropdown.menu.contains(mouseX, mouseY)) {
            return true;
        }

        openDropdownId = null;
        return false;
    }

    private boolean handleModelInputKeys(KeyInput input) {
        int keyCode = input.key();

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
            editingModelPath = false;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            caretIndex = Math.max(0, caretIndex - 1);
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            caretIndex = Math.min(editingModelText.length(), caretIndex + 1);
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_HOME) {
            caretIndex = 0;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_END) {
            caretIndex = editingModelText.length();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (caretIndex > 0 && !editingModelText.isEmpty()) {
                editingModelText = editingModelText.substring(0, caretIndex - 1) + editingModelText.substring(caretIndex);
                caretIndex--;
                TopographyUiConfig.setModelPath(selectedModule, editingModelText);
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (caretIndex < editingModelText.length()) {
                editingModelText = editingModelText.substring(0, caretIndex) + editingModelText.substring(caretIndex + 1);
                TopographyUiConfig.setModelPath(selectedModule, editingModelText);
            }
            return true;
        }

        return false;
    }

    private void updateModuleResponseFromMouse(TopographyModuleDefinition module, Rect track, double mouseX) {
        float progress = clamp((float) ((mouseX - track.x) / track.width), 0.0f, 1.0f);
        float value = MODULE_RESPONSE_MIN + progress * (MODULE_RESPONSE_MAX - MODULE_RESPONSE_MIN);
        TopographyUiConfig.setResponseCurve(module, value);
    }

    private void updateUiScaleFromMouse(Rect track, double mouseX) {
        float progress = clamp((float) ((mouseX - track.x) / track.width), 0.0f, 1.0f);
        float value = UI_SCALE_MIN + progress * (UI_SCALE_MAX - UI_SCALE_MIN);
        TopographyUiConfig.setInterfaceScale(value);
    }

    private boolean isModuleCategory() {
        return activeTab == TopographyTab.COMBAT || activeTab == TopographyTab.PLAYER;
    }

    private void ensureSelection() {
        if (!isModuleCategory()) {
            return;
        }

        List<TopographyModuleDefinition> modules = TopographyModuleDefinition.forTab(activeTab);
        if (modules.isEmpty()) {
            selectedModule = null;
            return;
        }

        if (selectedModule == null || selectedModule.tab() != activeTab) {
            selectedModule = modules.getFirst();
            syncModelEditor();
        }
    }

    private void selectModule(TopographyModuleDefinition module) {
        selectedModule = module;
        capturingBind = false;
        editingModelPath = false;
        openDropdownId = null;
        syncModelEditor();
    }

    private void syncModelEditor() {
        if (selectedModule == null || !selectedModule.usesModelPath()) {
            editingModelText = "";
            caretIndex = 0;
            return;
        }

        editingModelText = TopographyUiConfig.getModelPath(selectedModule);
        caretIndex = editingModelText.length();
    }

    private List<CardLayout> buildCardLayouts(Rect viewport) {
        List<TopographyModuleDefinition> modules = TopographyModuleDefinition.forTab(activeTab);
        List<CardLayout> layouts = new ArrayList<>(modules.size());
        if (modules.isEmpty()) {
            return layouts;
        }

        float innerWidth = viewport.width - 36.0f;
        int columns = innerWidth >= 500.0f ? 2 : 1;
        float cardWidth = columns == 1 ? innerWidth : (innerWidth - CARD_GAP) / 2.0f;
        float contentTop = getCardsClip(viewport).y + 8.0f - scrollOffset;
        float startX = viewport.x + 18.0f;

        for (int index = 0; index < modules.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            float x = startX + column * (cardWidth + CARD_GAP);
            float y = contentTop + row * (CARD_HEIGHT + CARD_GAP);
            Rect card = new Rect(x, y, cardWidth, CARD_HEIGHT);
            float buttonWidth = Math.max(84.0f, Math.min(104.0f, (cardWidth - 42.0f) / 2.0f));
            Rect bindButton = new Rect(card.x + 14.0f, card.bottom() - 46.0f, buttonWidth, 32.0f);
            Rect actionButton = new Rect(card.right() - 14.0f - buttonWidth, card.bottom() - 46.0f, buttonWidth, 32.0f);
            layouts.add(new CardLayout(modules.get(index), card, bindButton, actionButton));
        }

        return layouts;
    }

    private float getCardsContentHeight(Rect viewport) {
        List<TopographyModuleDefinition> modules = TopographyModuleDefinition.forTab(activeTab);
        if (modules.isEmpty()) {
            return 0.0f;
        }

        float innerWidth = viewport.width - 36.0f;
        int columns = innerWidth >= 500.0f ? 2 : 1;
        int rows = (int) Math.ceil(modules.size() / (double) columns);
        return rows * CARD_HEIGHT + Math.max(0, rows - 1) * CARD_GAP + 8.0f;
    }

    private float getMaxScroll(Rect viewport) {
        float visible = getCardsClip(viewport).height;
        float content = getCardsContentHeight(viewport);
        return Math.max(0.0f, content - visible);
    }

    private Rect getCardsClip(Rect viewport) {
        return new Rect(viewport.x + 16.0f, viewport.y + 40.0f, viewport.width - 32.0f, viewport.height - 56.0f);
    }

    private Layout getLayout() {
        float scale = TopographyUiConfig.getInterfaceScale();
        float windowWidth = Math.min(width - 64.0f, 1060.0f * scale);
        float windowHeight = Math.min(height - 56.0f, 636.0f * scale);
        float windowX = (width - windowWidth) / 2.0f;
        float windowY = (height - windowHeight) / 2.0f;

        Rect window = new Rect(windowX, windowY, windowWidth, windowHeight);
        Rect sidebar = new Rect(windowX + 20.0f, windowY + 20.0f, 64.0f, windowHeight - 40.0f);
        float contentX = sidebar.right() + 22.0f;
        float contentWidth = window.right() - contentX - 20.0f;
        Rect header = new Rect(contentX, windowY + 20.0f, contentWidth, 64.0f);
        float contentY = header.bottom() + 18.0f;
        float contentHeight = window.bottom() - contentY - 20.0f;

        float inspectorWidth = clamp(contentWidth * 0.36f, 300.0f, 356.0f);
        float minCardsWidth = 352.0f;
        if (contentWidth - inspectorWidth - 18.0f < minCardsWidth) {
            inspectorWidth = Math.max(280.0f, contentWidth - minCardsWidth - 18.0f);
        }

        float cardsWidth = contentWidth - inspectorWidth - 18.0f;
        Rect cardsViewport = new Rect(contentX, contentY, cardsWidth, contentHeight);
        Rect inspector = new Rect(cardsViewport.right() + 18.0f, contentY, inspectorWidth, contentHeight);
        return new Layout(window, sidebar, header, cardsViewport, inspector);
    }

    private Rect getThemeSwitchContainer(Layout layout) {
        return new Rect(layout.header.right() - 214.0f, layout.header.y + 8.0f, 214.0f, 30.0f);
    }

    private Rect getThemeSwitchOption(Layout layout, int index) {
        Rect container = getThemeSwitchContainer(layout);
        float gap = 4.0f;
        float inset = 2.0f;
        float optionWidth = (container.width - inset * 2.0f - gap) / 2.0f;
        return new Rect(container.x + inset + index * (optionWidth + gap), container.y + inset, optionWidth, container.height - inset * 2.0f);
    }

    private Rect getModuleBindToggle(Layout layout) {
        Rect inner = layout.inspector.inset(20.0f);
        return new Rect(inner.x, inner.y + 104.0f, inner.width, 58.0f);
    }

    private Rect getModuleBindButton(Layout layout) {
        Rect previous = getModuleBindToggle(layout);
        return new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 58.0f);
    }

    private SliderBounds getModuleResponseSlider(Layout layout) {
        Rect previous = getModuleBindButton(layout);
        Rect hitbox = new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 66.0f);
        Rect track = new Rect(hitbox.x + 16.0f, hitbox.bottom() - 18.0f, hitbox.width - 32.0f, 4.0f);
        return new SliderBounds(hitbox, track);
    }

    private DropdownBounds getModuleProfileDropdown(Layout layout) {
        Rect previous = getModuleResponseSlider(layout).hitbox;
        Rect hitbox = new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 58.0f);
        Rect menu = new Rect(hitbox.x, hitbox.bottom() + 8.0f, hitbox.width, 12.0f + TopographyUiConfig.MODULE_PROFILES.size() * 36.0f);
        return new DropdownBounds(hitbox, menu);
    }

    private Rect getModuleModelRow(Layout layout) {
        Rect previous = getModuleProfileDropdown(layout).hitbox;
        return new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 74.0f);
    }

    private Rect getModelInputBox(Layout layout) {
        Rect row = getModuleModelRow(layout);
        return new Rect(row.x + 16.0f, row.y + 42.0f, row.width - 32.0f, 24.0f);
    }

    private Rect getModuleActionButton(Layout layout) {
        Rect inner = layout.inspector.inset(20.0f);
        return new Rect(inner.x, inner.bottom() - 48.0f, inner.width, 48.0f);
    }

    private Rect getVisualBlurToggle(Layout layout) {
        Rect inner = layout.inspector.inset(20.0f);
        return new Rect(inner.x, inner.y + 104.0f, inner.width, 58.0f);
    }

    private Rect getVisualMotionToggle(Layout layout) {
        Rect previous = getVisualBlurToggle(layout);
        return new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 58.0f);
    }

    private SliderBounds getVisualScaleSlider(Layout layout) {
        Rect previous = getVisualMotionToggle(layout);
        Rect hitbox = new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 66.0f);
        Rect track = new Rect(hitbox.x + 16.0f, hitbox.bottom() - 18.0f, hitbox.width - 32.0f, 4.0f);
        return new SliderBounds(hitbox, track);
    }

    private DropdownBounds getVisualAccentDropdown(Layout layout) {
        Rect previous = getVisualScaleSlider(layout).hitbox;
        Rect hitbox = new Rect(previous.x, previous.bottom() + 14.0f, previous.width, 58.0f);
        Rect menu = new Rect(hitbox.x, hitbox.bottom() + 8.0f, hitbox.width, 12.0f + TopographyUiConfig.ACCENT_PRESETS.size() * 36.0f);
        return new DropdownBounds(hitbox, menu);
    }

    private Rect getDropdownOptionRect(DropdownBounds dropdown, int index) {
        return new Rect(dropdown.menu.x + 8.0f, dropdown.menu.y + 8.0f + index * 36.0f, dropdown.menu.width - 16.0f, 28.0f);
    }

    private Theme resolveTheme() {
        String preset = TopographyUiConfig.getAccentPreset();
        boolean flatGlass = "Flat Glass".equals(preset);

        int accentStart = flatGlass ? 0xFF8470FF : 0xFF9B59FF;
        int accentEnd = flatGlass ? 0xFFA78BFA : 0xFFC084FC;
        int backgroundBottom = flatGlass ? 0xFF10121A : 0xFF11111A;
        int windowFill = flatGlass ? 0xFF141722 : 0xFF13131A;
        int sidebarFill = flatGlass ? 0xFF151823 : 0xFF13131A;
        int cardFill = flatGlass ? 0xFF1C1F2B : 0xFF1A1A24;
        int cardHover = flatGlass ? 0xFF232737 : 0xFF1E1E2A;
        int cardStrongFill = flatGlass ? 0xFF252A3B : 0xFF20202C;
        int glowColor = flatGlass ? 0xFF8C85FF : 0xFF9B59FF;

        return new Theme(
            0xFF0D0D12,
            backgroundBottom,
            windowFill,
            0xFF2C2C38,
            sidebarFill,
            cardFill,
            cardHover,
            cardStrongFill,
            accentStart,
            accentEnd,
            0xFFE0E0E6,
            0xFF8888A0,
            0xFF4A4A5A,
            glowColor,
            0xFF67D58C
        );
    }

    private float computeFrameDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0f / 60.0f;
        }

        float delta = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        return clamp(delta, 1.0f / 240.0f, 0.05f);
    }

    private float animate(String key, float target, float frameDelta, float speed) {
        if (!TopographyUiConfig.isMotionEnabled()) {
            animations.put(key, target);
            return target;
        }

        float current = animations.getOrDefault(key, 0.0f);
        float factor = 1.0f - (float) Math.pow(1.0f - Math.min(speed, 0.96f), frameDelta * 60.0f);
        current += (target - current) * factor;
        animations.put(key, current);
        return easeInOut(current);
    }

    private static float easeInOut(float value) {
        float clamped = clamp(value, 0.0f, 1.0f);
        if (clamped < 0.5f) {
            return 2.0f * clamped * clamped;
        }
        return 1.0f - (float) Math.pow(-2.0f * clamped + 2.0f, 2.0f) / 2.0f;
    }

    private static float normalizeModuleResponse(float value) {
        return clamp((value - MODULE_RESPONSE_MIN) / (MODULE_RESPONSE_MAX - MODULE_RESPONSE_MIN), 0.0f, 1.0f);
    }

    private static float normalizeUiScale(float value) {
        return clamp((value - UI_SCALE_MIN) / (UI_SCALE_MAX - UI_SCALE_MIN), 0.0f, 1.0f);
    }

    private static int withAlpha(int color, int alpha) {
        return TopographySurfaceRenderer.withAlpha(color, alpha);
    }

    private static int mixColor(int start, int end, float progress) {
        float clamped = clamp(progress, 0.0f, 1.0f);
        int a = Math.round(((start >>> 24) & 0xFF) + (((end >>> 24) & 0xFF) - ((start >>> 24) & 0xFF)) * clamped);
        int r = Math.round(((start >>> 16) & 0xFF) + (((end >>> 16) & 0xFF) - ((start >>> 16) & 0xFF)) * clamped);
        int g = Math.round(((start >>> 8) & 0xFF) + (((end >>> 8) & 0xFF) - ((start >>> 8) & 0xFF)) * clamped);
        int b = Math.round((start & 0xFF) + ((end & 0xFF) - (start & 0xFF)) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Layout(Rect window, Rect sidebar, Rect header, Rect cardsViewport, Rect inspector) {
        private Layout offset(float dx, float dy) {
            return new Layout(
                window.offset(dx, dy),
                sidebar.offset(dx, dy),
                header.offset(dx, dy),
                cardsViewport.offset(dx, dy),
                inspector.offset(dx, dy)
            );
        }
    }

    private record SliderBounds(Rect hitbox, Rect track) {
    }

    private record DropdownBounds(Rect hitbox, Rect menu) {
    }

    private record CardLayout(TopographyModuleDefinition module, Rect card, Rect bindButton, Rect actionButton) {
    }

    private record Theme(
        int backgroundTop,
        int backgroundBottom,
        int windowFill,
        int windowBorder,
        int sidebarFill,
        int cardFill,
        int cardHover,
        int cardStrongFill,
        int accentStart,
        int accentEnd,
        int textPrimary,
        int textSecondary,
        int textDisabled,
        int glowColor,
        int successColor
    ) {
    }

    private record Rect(float x, float y, float width, float height) {
        private boolean contains(double px, double py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }

        private float right() {
            return x + width;
        }

        private float bottom() {
            return y + height;
        }

        private float centerX() {
            return x + width / 2.0f;
        }

        private float centerY() {
            return y + height / 2.0f;
        }

        private Rect inset(float amount) {
            return new Rect(x + amount, y + amount, width - amount * 2.0f, height - amount * 2.0f);
        }

        private Rect offset(float dx, float dy) {
            return new Rect(x + dx, y + dy, width, height);
        }
    }
}











