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
import java.util.stream.Collectors;


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

    private static final int BORDER_DEFAULT  = 0xFF1A1A24;
    private static final int BORDER_SUBTLE   = 0xFF16161E;

    private static final int ACCENT          = 0xFF818CF8;
    private static final int ACCENT_DIM      = 0xFF5C65C7;

    private static final int TEXT_PRIMARY    = 0xFFE8E8F0;
    private static final int TEXT_SECONDARY  = 0xFF6B6F80;
    private static final int TEXT_DIM        = 0xFF6E7388;

    private static final int HIGHLIGHT_TOP   = 0x14FFFFFF;
    private static final int SHADOW_AMBIENT  = 0x1F000000;

    // ── Layout constants (base values, scale down for small windows) ──
    private static final float PANEL_RADIUS    = 18f;

    // ── Computed layout ──────────────────────────────────────────
    private float panelW, panelH;
    private float pad, headerH, cardGap, bottomCardH;
    private float modesHeaderY, settingsHeaderY, miscHeaderY;

    private final Screen parent;
    private long openTimeNanos;
    private long closeTimeNanos = 0;
    private boolean closing = false;
    private static final float OPEN_DURATION_MS = 350f;
    private static final float CLOSE_DURATION_MS = 250f;

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

    // Misc card
    private CardWidget miscCard;
    private ToggleWidget autoJoinToggle;
    private DropdownWidget autoJoinModeSelector;

    public TopographyScreen(Screen parent) {
        super(Text.literal("Topography"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        openTimeNanos = 0; // deferred to first render
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

        // ── Misc card ─────────────────────────────────────────────
        miscCard = new CardWidget();
        miscCard.setPadding(20);

        autoJoinToggle = new ToggleWidget(LABEL_FONT, "Auto-Join: ",
                TopographyUiConfig::isAutoJoinEnabled, TopographyUiConfig::setAutoJoinEnabled);
        miscCard.add(autoJoinToggle);

        List<String> modeNames = new ArrayList<>();
        modeNames.add("None");
        modeNames.addAll(ModeRegistry.getModes().stream()
                .map(Mode::getName).collect(Collectors.toList()));

        autoJoinModeSelector = new DropdownWidget(LABEL_FONT, "Mode: ",
                modeNames,
                () -> {
                    int id = TopographyUiConfig.getAutoJoinModeId();
                    // id 0 = none (index 0), id 1 = first mode (index 1), etc.
                    for (int i = 0; i < ModeRegistry.getModes().size(); i++) {
                        if (ModeRegistry.getModes().get(i).getId() == id) return i + 1;
                    }
                    return 0;
                },
                idx -> {
                    if (idx == 0) {
                        TopographyUiConfig.setAutoJoinModeId(0);
                    } else {
                        TopographyUiConfig.setAutoJoinModeId(
                                ModeRegistry.getModes().get(idx - 1).getId());
                    }
                });
        miscCard.add(autoJoinModeSelector);

        root.add(miscCard);

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
        // Adaptive: use more space at small windows, less at large
        boolean compact = height < 350;
        boolean medium = height < 500;

        float wRatio = width < 600 ? 0.92f : (width < 900 ? 0.85f : 0.78f);
        float hRatio = height < 400 ? 0.92f : (height < 600 ? 0.87f : 0.80f);

        panelW = Math.max(320, Math.round(width * wRatio));
        panelH = Math.max(240, Math.round(height * hRatio));

        pad = compact ? 16 : (medium ? 22 : 32);
        headerH = compact ? 34 : (medium ? 42 : 50);
        cardGap = compact ? 10 : (medium ? 14 : 20);
        bottomCardH = compact ? 60 : (medium ? 70 : 80);
    }

    private void layout(float px, float py) {
        root.setBounds(px, py, panelW, panelH);

        float contentX = px + pad;
        float contentW = panelW - pad * 2;

        // ── Header: logo left, close right ──────────────────────
        float closeW = LABEL_FONT.width("\u00D7") + 16;
        closeLabel.setBounds(px + panelW - pad - closeW + 8, py + (headerH - LABEL_FONT.lineHeight() - 8) / 2f,
                closeW, LABEL_FONT.lineHeight() + 8);

        // ── Content area below header ───────────────────────────
        float headerGap = height < 400 ? 8 : 16;
        float contentTop = py + headerH + headerGap;
        float contentBottom = py + panelH - pad;

        // Bottom area: SETTINGS (left) and MISC (right) side by side
        float sectionHeaderH = SECTION_FONT.lineHeight() + (height < 400 ? 6 : 10);
        float bottomBlockH = sectionHeaderH + bottomCardH;
        float bottomTopY = contentBottom - bottomBlockH;
        float bottomCardY = bottomTopY + sectionHeaderH;

        // Split bottom into two columns
        float bottomGap = cardGap;
        float settingsW = contentW * 0.55f - bottomGap / 2f;
        float miscW = contentW - settingsW - bottomGap;

        settingsHeaderY = bottomTopY;
        miscHeaderY = bottomTopY;

        // Mode cards fill remaining space
        modesHeaderY = contentTop;
        float cardsY = contentTop + sectionHeaderH;
        float cardSettingsGap = height < 400 ? 12 : 24;
        float cardH = bottomTopY - cardsY - cardSettingsGap;
        cardH = Math.max(80, cardH);

        int cardCount = modeCards.size();
        float cardW = (contentW - cardGap * (cardCount - 1)) / cardCount;

        for (int i = 0; i < modeCards.size(); i++) {
            modeCards.get(i).setBounds(contentX + i * (cardW + cardGap), cardsY, cardW, cardH);
        }

        // ── Settings card (left) ──────────────────────────────────
        float sPad = Math.min(20, pad);
        settingsCard.setBounds(contentX, bottomCardY, settingsW, bottomCardH);

        float sy = bottomCardY + sPad;
        float proxyLabelW = LABEL_FONT.width("Proxy:");
        proxyLabel.setBounds(contentX + sPad, sy, proxyLabelW, LABEL_FONT.lineHeight());

        float configW = SMALL_FONT.width("[Configure]");
        float proxyValueX = contentX + sPad + proxyLabelW + 8;
        float proxyValueMaxW = settingsW - sPad * 2 - proxyLabelW - 8 - configW - 12;
        proxyValue.setBounds(proxyValueX, sy, proxyValueMaxW, LABEL_FONT.lineHeight());

        configBtn.setBounds(contentX + settingsW - sPad - configW, sy,
                configW, SMALL_FONT.lineHeight() + 4);

        // Toggles row
        float toggleY = bottomCardY + sPad + LABEL_FONT.lineHeight() + (height < 400 ? 6 : 10);
        float toggleX = contentX + sPad;
        float pillW = 26;
        float pathW = LABEL_FONT.width("Path Render: ") + 4 + pillW;
        pathToggle.setBounds(toggleX, toggleY, pathW, LABEL_FONT.lineHeight() + 4);

        float toggleGap = 40;
        float mobX = toggleX + pathW + toggleGap;
        float mobW = LABEL_FONT.width("Mob ESP: ") + 4 + pillW;
        mobToggle.setBounds(mobX, toggleY, mobW, LABEL_FONT.lineHeight() + 4);

        // ── Misc card (right) ─────────────────────────────────────
        float miscX = contentX + settingsW + bottomGap;
        miscCard.setBounds(miscX, bottomCardY, miscW, bottomCardH);

        float my = bottomCardY + sPad;
        float autoJoinW = LABEL_FONT.width("Auto-Join: ") + 4 + pillW;
        autoJoinToggle.setBounds(miscX + sPad, my, autoJoinW, LABEL_FONT.lineHeight() + 4);

        float selectorY = bottomCardY + sPad + LABEL_FONT.lineHeight() + (height < 400 ? 6 : 10);
        autoJoinModeSelector.setBounds(miscX + sPad, selectorY, miscW - sPad * 2, LABEL_FONT.lineHeight() + 4);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RENDER
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // ── Defer open timestamp to first actual render (avoids AWT init stutter) ──
        if (openTimeNanos == 0) openTimeNanos = System.nanoTime();

        // ── Open animation (nanoTime for smooth interpolation) ────
        float openElapsed = Math.min(1f, (System.nanoTime() - openTimeNanos) / (OPEN_DURATION_MS * 1_000_000f));
        float openInv = 1f - openElapsed;
        float openEase = 1f - openInv * openInv * openInv;  // cubic ease-out

        // ── Close animation ───────────────────────────────────────
        float closeFactor = 1f;
        if (closing) {
            float closeElapsed = (System.nanoTime() - closeTimeNanos) / (CLOSE_DURATION_MS * 1_000_000f);
            if (closeElapsed >= 1f) {
                client.setScreen(parent);
                return;
            }
            float closeEase = closeElapsed * closeElapsed;  // quadratic ease-in
            closeFactor = 1f - closeEase;
        }

        float ease = openEase * closeFactor;
        int alpha = (int) (ease * 255);

        // ── Background overlay (near-opaque — prevents bleed at rounded corners) ──
        ctx.fill(0, 0, width, height, applyAlpha(0xFF000000, (int) (alpha * 0.92f)));

        computeLayout();

        float px = (width - panelW) / 2f;
        float py = (height - panelH) / 2f + (1f - ease) * 25f;
        layout(px, py);

        // ── Panel shadow (soft ambient) ─────────────────────────
        S.drawShadow(ctx, px, py, panelW, panelH, PANEL_RADIUS, 8,
                applyAlpha(SHADOW_AMBIENT, alpha));

        // ── Panel background with thin visible border ────────────
        S.drawOutlinedRounded(ctx, px, py, panelW, panelH, PANEL_RADIUS,
                applyAlpha(BG_BASE, alpha),
                applyAlpha(0xFF2A2A36, alpha), 1f);

        // ── Subtle inner gradient (top only) ───────────────────
        S.drawGradientRounded(ctx, px + 1, py + 2, panelW - 2, panelH * 0.08f,
                PANEL_RADIUS - 1,
                applyAlpha(HIGHLIGHT_TOP, alpha),
                TopographySurfaceRenderer.withAlpha(0xFFFFFF, 0), 1f);

        float contentX = px + pad;
        float contentW = panelW - pad * 2;

        // ── Header bar ──────────────────────────────────────────
        float logoY = py + (headerH - LOGO_FONT.lineHeight()) / 2f;

        // Logo with tracking
        LOGO_FONT.drawWithTracking(ctx, "TOPOGRAPHY", contentX, logoY,
                applyAlpha(TEXT_PRIMARY, alpha), 3f);

        // Breadcrumb: "Modules / Dashboard" — baseline-aligned with logo bottom
        float breadcrumbX = contentX + LOGO_FONT.widthWithTracking("TOPOGRAPHY", 3f) + 20;
        float logoBaseline = logoY + LOGO_FONT.ascent();
        float breadcrumbY = logoBaseline - TITLE_FONT.ascent();
        TITLE_FONT.draw(ctx, "Modules", breadcrumbX, breadcrumbY,
                applyAlpha(TEXT_DIM, alpha));
        float modulesW = TITLE_FONT.width("Modules");
        TITLE_FONT.draw(ctx, " / ", breadcrumbX + modulesW, breadcrumbY,
                applyAlpha(TEXT_DIM, alpha));
        float slashW = TITLE_FONT.width(" / ");
        TITLE_FONT.draw(ctx, "Dashboard", breadcrumbX + modulesW + slashW, breadcrumbY,
                applyAlpha(TEXT_SECONDARY, alpha));

        // Header separator line (single clean line, no segments)
        float sepY = py + headerH;
        S.drawRounded(ctx, contentX, sepY, contentW, 1, 0,
                applyAlpha(BORDER_DEFAULT, (int) (alpha * 0.7f)));

        // ── Section headers ──────────────────────────────────────
        float bottomGap = cardGap;
        float settingsW = contentW * 0.55f - bottomGap / 2f;
        float miscW = contentW - settingsW - bottomGap;
        float miscX = contentX + settingsW + bottomGap;

        renderSectionHeader(ctx, "MODES", contentX, modesHeaderY, contentW, alpha);
        renderSectionHeader(ctx, "SETTINGS", contentX, settingsHeaderY, settingsW, alpha);
        renderSectionHeader(ctx, "MISC", miscX, miscHeaderY, miscW, alpha);

        // ── Status ribbon at bottom ─────────────────────────────
        float ribbonH = 2f;
        float ribbonInset = 18;
        float ribbonY = py + panelH - ribbonH - 3;
        float ribbonX = px + ribbonInset;
        float ribbonW = panelW - ribbonInset * 2;

        if (TopographyController.isAnyActive()) {
            int green = 0xFF34D399;
            // Base ribbon (dim)
            int baseA = (int) (alpha * 0.3f);
            S.drawRounded(ctx, ribbonX, ribbonY, ribbonW, ribbonH, 1,
                    TopographySurfaceRenderer.withAlpha(green, baseA));
            // Bright spot traveling left-to-right (single overlay, no segments)
            float wavePos = (System.currentTimeMillis() % 2000L) / 2000f;
            float spotW = ribbonW * 0.25f;
            float spotX = ribbonX + wavePos * ribbonW - spotW / 2f;
            // Clamp to ribbon bounds
            float drawX = Math.max(ribbonX, spotX);
            float drawEnd = Math.min(ribbonX + ribbonW, spotX + spotW);
            if (drawEnd > drawX) {
                int spotA = (int) (alpha * 0.5f);
                S.drawRounded(ctx, drawX, ribbonY, drawEnd - drawX, ribbonH, 1,
                        TopographySurfaceRenderer.withAlpha(green, spotA));
            }
        } else {
            int rA = (int) (alpha * 0.2f);
            S.drawRounded(ctx, ribbonX, ribbonY, ribbonW, ribbonH, 1,
                    TopographySurfaceRenderer.withAlpha(ACCENT_DIM, rA));
        }

        // ── Render widgets ───────────────────────────────────────
        root.updateHover(mouseX, mouseY);
        root.render(ctx, mouseX, mouseY, alpha);

        // ── Dropdown overlay (renders on top of everything) ───
        autoJoinModeSelector.renderOverlay(ctx, mouseX, mouseY, alpha);
    }

    private void renderSectionHeader(DrawContext ctx, String text, float hx, float hy,
                                     float maxW, int alpha) {
        SECTION_FONT.draw(ctx, text, hx, hy, applyAlpha(TEXT_DIM, alpha));

        // Clean single line from text end to right edge
        float textEnd = hx + SECTION_FONT.width(text) + 12;
        float fadeLineY = hy + SECTION_FONT.lineHeight() / 2f;
        float lineLen = hx + maxW - textEnd;
        if (lineLen <= 0) return;

        S.drawRounded(ctx, textEnd, fadeLineY, lineLen, 1, 0,
                applyAlpha(BORDER_DEFAULT, (int) (alpha * 0.5f)));
    }

    // ═══════════════════════════════════════════════════════════════
    //  INPUT
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.buttonInfo().button() != 0) return super.mouseClicked(click, bl);

        double mx = click.x();
        double my = click.y();

        // Dropdown gets priority when open
        if (autoJoinModeSelector.isOpen()) {
            autoJoinModeSelector.mouseClicked(mx, my, 0);
            return true;
        }

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
        if (!closing) {
            closing = true;
            closeTimeNanos = System.nanoTime();
        }
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
