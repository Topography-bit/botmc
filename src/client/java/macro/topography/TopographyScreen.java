package macro.topography;

import macro.topography.ui.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

public class TopographyScreen extends Screen {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();
    private static final TopographySmoothTextRenderer LOGO_FONT  = new TopographySmoothTextRenderer("Inter", 22, Font.BOLD);
    private static final TopographySmoothTextRenderer SMALL_FONT = new TopographySmoothTextRenderer("Inter", 10, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL_FONT = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);

    // ── Colors ────────────────────────────────────────────────────
    private static final int BG          = 0xF20D0D0F;
    private static final int CARD_BG     = 0xFF141418;
    private static final int CARD_BORDER = 0xFF1E1E24;
    private static final int ACCENT      = 0xFF00C8FF;
    private static final int TEXT_PRIMARY   = 0xFFEAEAEF;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;

    // ── Layout (base values, scaled at runtime) ────────────────
    private static final float BASE_PANEL_W       = 480;
    private static final float BASE_PANEL_PADDING = 24;
    private static final float BASE_CARD_GAP      = 16;
    private static final float BASE_CARD_H        = 190;
    private static final float BASE_BTN_H         = 30;
    private static final float BASE_SETTINGS_H    = 60;

    // ── Computed layout values (updated each frame) ──────────
    private float panelW, panelPad, cardGap, cardH, btnH, settingsH;

    private final Screen parent;
    private long openTimeMs;

    // ── Widgets (stored as fields for clean relayout) ────────────
    private WidgetContainer root;
    private final List<ModeCardWidget> modeCards = new ArrayList<>();
    private CardWidget settingsCard;
    private LabelWidget proxyLabel;
    private LabelWidget proxyValue;
    private ClickableLabel configBtn;
    private ToggleWidget pathToggle;
    private ToggleWidget mobToggle;
    private ButtonWidget closeBtn;

    public TopographyScreen(Screen parent) {
        super(Text.literal("Topography"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        openTimeMs = System.currentTimeMillis();
        buildWidgetTree();
    }

    public boolean isCapturingBind() {
        return modeCards.stream().anyMatch(c -> c.getKeybindWidget().isCapturing());
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUILD
    // ═══════════════════════════════════════════════════════════════

    private void buildWidgetTree() {
        root = new WidgetContainer();
        modeCards.clear();

        // ── Mode cards (from registry) ────────────────────────────
        for (Mode mode : ModeRegistry.getModes()) {
            int id = mode.getId();
            KeybindWidget bind = new KeybindWidget(SMALL_FONT,
                    () -> TopographyUiConfig.getModeKeyCode(id),
                    code -> TopographyUiConfig.setModeKeyCode(id, code));
            ModeCardWidget card = new ModeCardWidget(
                    mode.getName(), mode.getSubtitle(),
                    mode::isActive,
                    () -> TopographyController.toggleMode(id),
                    TopographyController::getUptime,
                    () -> String.valueOf(TopographyController.getKillCount()),
                    bind);
            card.setPadding(14);
            root.add(card);
            modeCards.add(card);
        }

        // ── Settings card ───────────────────────────────────────
        settingsCard = new CardWidget();
        settingsCard.setPadding(12);

        proxyLabel = new LabelWidget(LABEL_FONT, "Proxy:", TEXT_SECONDARY);
        settingsCard.add(proxyLabel);

        proxyValue = new LabelWidget(LABEL_FONT,
                TopographyScreen::getProxyDisplayText, () -> TEXT_PRIMARY);
        proxyValue.setEllipsis(true);
        settingsCard.add(proxyValue);

        configBtn = new ClickableLabel(SMALL_FONT,
                "[Configure]", TEXT_SECONDARY, ACCENT);
        configBtn.setOnClick(() -> MinecraftClient.getInstance().setScreen(new ProxyScreen(this)));
        settingsCard.add(configBtn);

        pathToggle = new ToggleWidget(LABEL_FONT, "Path Render: ",
                TopographyUiConfig::isPathRenderEnabled, TopographyUiConfig::setPathRenderEnabled);
        settingsCard.add(pathToggle);

        mobToggle = new ToggleWidget(LABEL_FONT, "Mob ESP: ",
                TopographyUiConfig::isMobEspEnabled, TopographyUiConfig::setMobEspEnabled);
        settingsCard.add(mobToggle);

        root.add(settingsCard);

        // ── Close button ────────────────────────────────────────
        closeBtn = new ButtonWidget(LABEL_FONT, "CLOSE", ButtonWidget.Style.OUTLINED);
        closeBtn.setColors(CARD_BG, 0xFF1E1E24, TEXT_SECONDARY);
        closeBtn.setBorderColor(CARD_BORDER);
        closeBtn.setOnClick(this::close);
        root.add(closeBtn);

        // Initial layout
        computeScaledSizes();
        layout((height - computePanelH()) / 2f);
    }

    private void computeScaledSizes() {
        // Scale panel to fit window — cap at base size, shrink on small screens
        panelW = Math.min(BASE_PANEL_W, width * 0.65f);
        float scale = panelW / BASE_PANEL_W;
        panelPad = BASE_PANEL_PADDING * scale;
        cardGap = BASE_CARD_GAP * scale;
        cardH = BASE_CARD_H * scale;
        btnH = Math.max(22, BASE_BTN_H * scale);
        settingsH = BASE_SETTINGS_H * scale;
    }

    private float computePanelH() {
        return panelPad * 2 + 40 + 12 + cardH + 16 + settingsH + 16 + btnH;
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYOUT — all bounds in one place
    // ═══════════════════════════════════════════════════════════════

    private void layout(float py) {
        float px = (width - panelW) / 2f;
        float pH = computePanelH();
        root.setBounds(px, py, panelW, pH);

        // Cards
        float lineY = py + panelPad + LOGO_FONT.lineHeight() + 4;
        float cardsY = lineY + 14;
        float cardLeft = px + panelPad;
        int cardCount = modeCards.size();
        float cardW = (panelW - panelPad * 2 - cardGap * (cardCount - 1)) / cardCount;

        for (int i = 0; i < modeCards.size(); i++) {
            modeCards.get(i).setBounds(cardLeft + i * (cardW + cardGap), cardsY, cardW, cardH);
        }

        // Settings
        float sGap = 16;
        float sY = cardsY + cardH + sGap;
        float sW = panelW - panelPad * 2;
        float sPad = 12;
        settingsCard.setBounds(cardLeft, sY, sW, settingsH);

        float sy = sY + sPad;
        float proxyLabelW = LABEL_FONT.width("Proxy:");
        proxyLabel.setBounds(cardLeft + sPad, sy, proxyLabelW, LABEL_FONT.lineHeight());

        // Proxy value gets remaining space between label and [Configure]
        float configW = SMALL_FONT.width("[Configure]");
        float proxyValueX = cardLeft + sPad + proxyLabelW + 6;
        float proxyValueMaxW = sW - sPad * 2 - proxyLabelW - 6 - configW - 8;
        proxyValue.setBounds(proxyValueX, sy, proxyValueMaxW, LABEL_FONT.lineHeight());

        configBtn.setBounds(cardLeft + sW - sPad - configW, sy,
                configW, SMALL_FONT.lineHeight() + 4);

        // Toggles — positioned by actual text width, no magic numbers
        float toggleY = sY + sPad + LABEL_FONT.lineHeight() + 8;
        float toggleX = cardLeft + sPad;
        float pathW = LABEL_FONT.width("Path Render: ") + LABEL_FONT.width("[OFF]");
        pathToggle.setBounds(toggleX, toggleY, pathW, LABEL_FONT.lineHeight() + 4);

        float toggleGap = 24;
        float mobX = toggleX + pathW + toggleGap;
        float mobW = LABEL_FONT.width("Mob ESP: ") + LABEL_FONT.width("[OFF]");
        mobToggle.setBounds(mobX, toggleY, mobW, LABEL_FONT.lineHeight() + 4);

        // Close
        float closeBtnW = Math.min(100, panelW * 0.25f);
        closeBtn.setBounds(px + (panelW - closeBtnW) / 2, sY + settingsH + sGap, closeBtnW, btnH);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RENDER
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float elapsed = (System.currentTimeMillis() - openTimeMs) / 300f;
        float ease = Math.min(1f, elapsed);
        ease = 1f - (1f - ease) * (1f - ease);
        int alpha = (int)(ease * 255);

        ctx.fill(0, 0, width, height, 0xA0000000);

        // Recompute sizes (handles window resize)
        computeScaledSizes();

        // Animated panel Y
        float pH = computePanelH();
        float py = (height - pH) / 2f + (1f - ease) * 20f;
        layout(py);

        float px = root.getX();

        // Panel background
        S.drawShadow(ctx, px, py, panelW, pH, 12, 20,
                TopographySurfaceRenderer.withAlpha(0x000000, alpha / 3));
        S.drawOutlinedRounded(ctx, px, py, panelW, pH, 12,
                TopographySurfaceRenderer.withAlpha(BG & 0x00FFFFFF, alpha),
                TopographySurfaceRenderer.withAlpha(CARD_BORDER & 0x00FFFFFF, alpha), 1f);

        // Logo + accent line
        float logoY = py + panelPad;
        LOGO_FONT.drawCentered(ctx, "TOPOGRAPHY", px + panelW / 2, logoY,
                applyAlpha(TEXT_PRIMARY, alpha));
        float lineW = 60;
        float lineY = logoY + LOGO_FONT.lineHeight() + 4;
        S.drawRounded(ctx, px + panelW / 2 - lineW / 2, lineY, lineW, 2, 1,
                applyAlpha(ACCENT, alpha));

        // Render all widgets
        root.updateHover(mouseX, mouseY);
        root.render(ctx, mouseX, mouseY, alpha);
    }

    // ═══════════════════════════════════════════════════════════════
    //  INPUT
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.buttonInfo().button() != 0) return super.mouseClicked(click, bl);

        double mx = click.x();
        double my = click.y();

        // Cancel keybind capture if clicking outside keybind widgets
        boolean clickedKeybind = false;
        for (ModeCardWidget card : modeCards) {
            if (card.getKeybindWidget().containsPoint(mx, my)) {
                clickedKeybind = true;
                break;
            }
        }
        if (!clickedKeybind) {
            for (ModeCardWidget card : modeCards) {
                card.getKeybindWidget().cancelCapture();
            }
        }

        if (root.mouseClicked(mx, my, 0)) {
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();

        if (root.keyPressed(keyCode, 0, 0)) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private static String getProxyDisplayText() {
        try {
            var proxy = ProxyConfig.getLinkedProxy();
            if (proxy != null) return proxy.host + ":" + proxy.port;
        } catch (Exception ignored) {}
        return "No proxy";
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
