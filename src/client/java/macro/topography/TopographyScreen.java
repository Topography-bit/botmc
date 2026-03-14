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
    private static final TopographySmoothTextRenderer LOGO_FONT      = new TopographySmoothTextRenderer("Inter", 20, Font.BOLD);
    private static final TopographySmoothTextRenderer TITLE_FONT     = new TopographySmoothTextRenderer("Inter", 14, Font.PLAIN);
    private static final TopographySmoothTextRenderer SMALL_FONT     = new TopographySmoothTextRenderer("Inter", 10, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL_FONT     = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);
    private static final TopographySmoothTextRenderer SECTION_FONT   = new TopographySmoothTextRenderer("Inter", 11, Font.BOLD);

    // ── Dark Crystal palette ─────────────────────────────────────
    private static final int BG_BASE         = 0xF00C0C10;
    private static final int BG_ELEVATED     = 0xFF141419;

    private static final int BORDER_DEFAULT  = 0xFF1E1E28;
    private static final int BORDER_SUBTLE   = 0xFF16161E;

    private static final int ACCENT          = 0xFF818CF8;
    private static final int ACCENT_DIM      = 0xFF5C65C7;

    private static final int TEXT_PRIMARY    = 0xFFE8E8F0;
    private static final int TEXT_SECONDARY  = 0xFF6B6F80;
    private static final int TEXT_DIM        = 0xFF3E4150;

    private static final int HIGHLIGHT_TOP   = 0x14FFFFFF;
    private static final int HIGHLIGHT_EDGE  = 0x0FFFFFFF;
    private static final int SHADOW_CONTACT  = 0x66000000;
    private static final int SHADOW_MID      = 0x40000000;
    private static final int SHADOW_AMBIENT  = 0x1F000000;

    // ── Layout constants ─────────────────────────────────────────
    private static final float PANEL_W_RATIO   = 0.78f;
    private static final float PANEL_H_RATIO   = 0.80f;
    private static final float PANEL_RADIUS    = 14f;
    private static final float HEADER_H        = 50f;
    private static final float PANEL_PAD       = 32f;
    private static final float CARD_GAP        = 20f;
    private static final float SETTINGS_H      = 80f;

    // ── Computed layout ──────────────────────────────────────────
    private float panelW, panelH;
    private float modesHeaderY, settingsHeaderY;

    private final Screen parent;
    private long openTimeMs;

    // ── Widgets ──────────────────────────────────────────────────
    private WidgetContainer root;
    private final List<ModeCardWidget> modeCards = new ArrayList<>();
    private CardWidget settingsCard;
    private LabelWidget proxyLabel;
    private LabelWidget proxyValue;
    private ClickableLabel configBtn;
    private ToggleWidget pathToggle;
    private ToggleWidget mobToggle;
    private ClickableLabel closeLabel;

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

        // ── Mode cards ───────────────────────────────────────────
        int cardIndex = 0;
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
            card.setPadding(20);
            card.setStaggerIndex(cardIndex++);
            root.add(card);
            modeCards.add(card);
        }

        // ── Settings card ────────────────────────────────────────
        settingsCard = new CardWidget();
        settingsCard.setPadding(20);

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

        // ── Close × ─────────────────────────────────────────────
        closeLabel = new ClickableLabel(LABEL_FONT, "\u00D7", TEXT_SECONDARY, TEXT_PRIMARY);
        closeLabel.setOnClick(this::close);
        root.add(closeLabel);

        computeLayout();
    }

    // ═══════════════════════════════════════════════════════════════
    //  LAYOUT
    // ═══════════════════════════════════════════════════════════════

    private void computeLayout() {
        panelW = Math.max(400, Math.round(width * PANEL_W_RATIO));
        panelH = Math.max(300, Math.round(height * PANEL_H_RATIO));
    }

    private void layout(float px, float py) {
        root.setBounds(px, py, panelW, panelH);

        float pad = PANEL_PAD;
        float contentX = px + pad;
        float contentW = panelW - pad * 2;

        // ── Header: logo left, close right ──────────────────────
        float headerBaseY = py + (HEADER_H - LOGO_FONT.lineHeight()) / 2f;
        float closeW = LABEL_FONT.width("\u00D7") + 16;
        closeLabel.setBounds(px + panelW - pad - closeW + 8, py + (HEADER_H - LABEL_FONT.lineHeight() - 8) / 2f,
                closeW, LABEL_FONT.lineHeight() + 8);

        // ── Content area below header ───────────────────────────
        float contentTop = py + HEADER_H + 16;
        float contentBottom = py + panelH - pad;

        // Settings card at bottom
        float sectionHeaderH = SECTION_FONT.lineHeight() + 10;
        float settingsBlockH = sectionHeaderH + SETTINGS_H;
        float settingsTopY = contentBottom - settingsBlockH;
        settingsHeaderY = settingsTopY;
        float sCardY = settingsTopY + sectionHeaderH;

        // Mode cards fill remaining space
        modesHeaderY = contentTop;
        float cardsY = contentTop + sectionHeaderH;
        float cardH = settingsTopY - cardsY - 24; // 24px gap before settings
        cardH = Math.max(120, cardH);

        int cardCount = modeCards.size();
        float cardW = (contentW - CARD_GAP * (cardCount - 1)) / cardCount;

        for (int i = 0; i < modeCards.size(); i++) {
            modeCards.get(i).setBounds(contentX + i * (cardW + CARD_GAP), cardsY, cardW, cardH);
        }

        // Settings card
        float sPad = 20;
        settingsCard.setBounds(contentX, sCardY, contentW, SETTINGS_H);

        float sy = sCardY + sPad;
        float proxyLabelW = LABEL_FONT.width("Proxy:");
        proxyLabel.setBounds(contentX + sPad, sy, proxyLabelW, LABEL_FONT.lineHeight());

        float configW = SMALL_FONT.width("[Configure]");
        float proxyValueX = contentX + sPad + proxyLabelW + 8;
        float proxyValueMaxW = contentW - sPad * 2 - proxyLabelW - 8 - configW - 12;
        proxyValue.setBounds(proxyValueX, sy, proxyValueMaxW, LABEL_FONT.lineHeight());

        configBtn.setBounds(contentX + contentW - sPad - configW, sy,
                configW, SMALL_FONT.lineHeight() + 4);

        // Toggles
        float toggleY = sCardY + sPad + LABEL_FONT.lineHeight() + 10;
        float toggleX = contentX + sPad;
        float pillW = 26;
        float pathW = LABEL_FONT.width("Path Render: ") + 4 + pillW;
        pathToggle.setBounds(toggleX, toggleY, pathW, LABEL_FONT.lineHeight() + 4);

        float toggleGap = 40;
        float mobX = toggleX + pathW + toggleGap;
        float mobW = LABEL_FONT.width("Mob ESP: ") + 4 + pillW;
        mobToggle.setBounds(mobX, toggleY, mobW, LABEL_FONT.lineHeight() + 4);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RENDER
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float elapsed = (System.currentTimeMillis() - openTimeMs) / 350f;
        float ease = Math.min(1f, elapsed);
        ease = 1f - (1f - ease) * (1f - ease);
        int alpha = (int) (ease * 255);

        // ── Background overlay (multi-layer fake frosted glass) ──
        ctx.fill(0, 0, width, height, applyAlpha(0xFF000000, (int) (alpha * 0.60f)));

        computeLayout();

        float px = (width - panelW) / 2f;
        float py = (height - panelH) / 2f + (1f - ease) * 25f;
        layout(px, py);

        // ── Panel shadow (3 layers) ─────────────────────────────
        S.drawShadow(ctx, px, py, panelW, panelH, PANEL_RADIUS, 4,
                applyAlpha(SHADOW_CONTACT, alpha));
        S.drawShadow(ctx, px, py, panelW, panelH, PANEL_RADIUS, 14,
                applyAlpha(SHADOW_MID, alpha));
        S.drawShadow(ctx, px, py, panelW, panelH, PANEL_RADIUS, 32,
                applyAlpha(SHADOW_AMBIENT, alpha));

        // ── Panel background ─────────────────────────────────────
        S.drawOutlinedRounded(ctx, px, py, panelW, panelH, PANEL_RADIUS,
                applyAlpha(BG_BASE, alpha),
                applyAlpha(BORDER_DEFAULT, alpha), 1f);

        // ── Top edge highlight ───────────────────────────────────
        S.drawRounded(ctx, px + PANEL_RADIUS, py + 1, panelW - PANEL_RADIUS * 2, 1, 0,
                applyAlpha(HIGHLIGHT_TOP, alpha));

        // ── Side highlights ──────────────────────────────────────
        S.drawRounded(ctx, px + 1, py + PANEL_RADIUS, 1, panelH - PANEL_RADIUS * 2, 0,
                applyAlpha(HIGHLIGHT_EDGE, alpha));
        S.drawRounded(ctx, px + panelW - 2, py + PANEL_RADIUS, 1, panelH - PANEL_RADIUS * 2, 0,
                TopographySurfaceRenderer.withAlpha(0xFFFFFF, (int) (3 * alpha / 255f)));

        // ── Inner gradient ───────────────────────────────────────
        S.drawGradientRounded(ctx, px + 1, py + 2, panelW - 2, panelH * 0.10f,
                PANEL_RADIUS - 1,
                applyAlpha(HIGHLIGHT_TOP, alpha),
                TopographySurfaceRenderer.withAlpha(0xFFFFFF, 0), 1f);

        float pad = PANEL_PAD;
        float contentX = px + pad;
        float contentW = panelW - pad * 2;

        // ── Header bar ──────────────────────────────────────────
        float logoY = py + (HEADER_H - LOGO_FONT.lineHeight()) / 2f;

        // Logo with tracking
        LOGO_FONT.drawWithTracking(ctx, "TOPOGRAPHY", contentX, logoY,
                applyAlpha(TEXT_PRIMARY, alpha), 3f);

        // Breadcrumb: "Modules / Dashboard"
        float breadcrumbX = contentX + LOGO_FONT.widthWithTracking("TOPOGRAPHY", 3f) + 20;
        TITLE_FONT.draw(ctx, "Modules", breadcrumbX, logoY + 3,
                applyAlpha(TEXT_DIM, alpha));
        float modulesW = TITLE_FONT.width("Modules");
        SMALL_FONT.draw(ctx, " / ", breadcrumbX + modulesW, logoY + 5,
                applyAlpha(TEXT_DIM, alpha));
        float slashW = SMALL_FONT.width(" / ");
        SMALL_FONT.draw(ctx, "Dashboard", breadcrumbX + modulesW + slashW, logoY + 5,
                applyAlpha(TEXT_SECONDARY, alpha));

        // Header separator line (full width)
        float sepY = py + HEADER_H;
        S.drawRounded(ctx, contentX, sepY, contentW, 1, 0,
                applyAlpha(BORDER_DEFAULT, alpha));

        // Subtle accent glow on header line
        float accentLineW = Math.min(120, contentW * 0.15f);
        S.drawRounded(ctx, contentX, sepY, accentLineW, 1, 0,
                applyAlpha(ACCENT_DIM, (int) (alpha * 0.5f)));

        // ── Section headers ──────────────────────────────────────
        renderSectionHeader(ctx, "MODES", contentX, modesHeaderY, contentW, alpha);
        renderSectionHeader(ctx, "SETTINGS", contentX, settingsHeaderY, contentW, alpha);

        // ── Status ribbon at bottom ─────────────────────────────
        float ribbonH = 2f;
        float ribbonInset = 18;
        float ribbonY = py + panelH - ribbonH - 3;
        float ribbonX = px + ribbonInset;
        float ribbonW = panelW - ribbonInset * 2;

        if (TopographyController.isAnyActive()) {
            float pulse = (float) (0.7 + 0.3 * Math.sin(System.currentTimeMillis() / 1200.0));
            int green = 0xFF34D399;
            int cA = (int) (alpha * 0.7f * pulse);
            int eA = (int) (alpha * 0.15f * pulse);
            float seg = ribbonW / 3f;
            S.drawGradientRounded(ctx, ribbonX, ribbonY, seg, ribbonH, 1,
                    TopographySurfaceRenderer.withAlpha(green, eA),
                    TopographySurfaceRenderer.withAlpha(green, cA), 1f);
            S.drawRounded(ctx, ribbonX + seg, ribbonY, seg, ribbonH, 0,
                    TopographySurfaceRenderer.withAlpha(green, cA));
            S.drawGradientRounded(ctx, ribbonX + seg * 2, ribbonY, seg, ribbonH, 1,
                    TopographySurfaceRenderer.withAlpha(green, cA),
                    TopographySurfaceRenderer.withAlpha(green, eA), 1f);
        } else {
            int segs = 7;
            float rsw = ribbonW / segs;
            float center = segs / 2f;
            for (int i = 0; i < segs; i++) {
                float dist = Math.abs(i + 0.5f - center) / center;
                float fade = 1f - dist * dist;
                int sa = (int) (alpha * 0.35f * fade);
                S.drawRounded(ctx, ribbonX + i * rsw, ribbonY, rsw + 1, ribbonH,
                        (i == 0 || i == segs - 1) ? 1 : 0,
                        TopographySurfaceRenderer.withAlpha(ACCENT_DIM, sa));
            }
        }

        // ── Render widgets ───────────────────────────────────────
        root.updateHover(mouseX, mouseY);
        root.render(ctx, mouseX, mouseY, alpha);
    }

    private void renderSectionHeader(DrawContext ctx, String text, float hx, float hy,
                                     float maxW, int alpha) {
        SECTION_FONT.draw(ctx, text, hx, hy, applyAlpha(TEXT_DIM, alpha));

        // Full-width line from text end to right edge
        float textEnd = hx + SECTION_FONT.width(text) + 12;
        float fadeLineY = hy + SECTION_FONT.lineHeight() / 2f;
        float lineLen = hx + maxW - textEnd;
        if (lineLen <= 0) return;

        // Solid line that fades out
        int segments = 8;
        float segW = lineLen / segments;
        for (int i = 0; i < segments; i++) {
            float frac = 1f - (float) i / (segments - 1);
            int segAlpha = (int) (alpha * frac * 0.12f);
            S.drawRounded(ctx, textEnd + i * segW, fadeLineY, segW + 1, 1, 0,
                    TopographySurfaceRenderer.withAlpha(0xFFFFFF, segAlpha));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  INPUT
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.buttonInfo().button() != 0) return super.mouseClicked(click, bl);

        double mx = click.x();
        double my = click.y();

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
