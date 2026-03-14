package macro.topography;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Font;

public class TopographyScreen extends Screen {

    private static final TopographySurfaceRenderer S = new TopographySurfaceRenderer();
    private static final TopographySmoothTextRenderer LOGO_FONT    = new TopographySmoothTextRenderer("Inter", 22, Font.BOLD);
    private static final TopographySmoothTextRenderer HEADING_FONT = new TopographySmoothTextRenderer("Inter", 14, Font.BOLD);
    private static final TopographySmoothTextRenderer BODY_FONT    = new TopographySmoothTextRenderer("Inter", 12, Font.PLAIN);
    private static final TopographySmoothTextRenderer SMALL_FONT   = new TopographySmoothTextRenderer("Inter", 10, Font.PLAIN);
    private static final TopographySmoothTextRenderer LABEL_FONT   = new TopographySmoothTextRenderer("Inter", 10, Font.BOLD);

    // ── Colors ────────────────────────────────────────────────────
    private static final int BG          = 0xF20D0D0F;  // 95% opacity near-black
    private static final int CARD_BG     = 0xFF141418;
    private static final int CARD_BORDER = 0xFF1E1E24;
    private static final int ACCENT      = 0xFF00C8FF;
    private static final int ACCENT_DARK = 0xFF0090CC;
    private static final int GREEN       = 0xFF49D28B;
    private static final int RED         = 0xFFFF4455;
    private static final int YELLOW      = 0xFFD6953E;
    private static final int TEXT_PRIMARY   = 0xFFEAEAEF;
    private static final int TEXT_SECONDARY = 0xFF6B6F80;
    private static final int TEXT_DIM       = 0xFF43464F;
    private static final int SEPARATOR      = 0xFF1A1A20;

    // ── Layout ────────────────────────────────────────────────────
    private static final float PANEL_W       = 480;
    private static final float PANEL_PADDING = 24;
    private static final float CARD_W        = (PANEL_W - PANEL_PADDING * 2 - 16) / 2; // two cards + gap
    private static final float CARD_H        = 190;
    private static final float CARD_RADIUS   = 8;
    private static final float BTN_H         = 30;
    private static final float BTN_RADIUS    = 6;
    private static final float SETTINGS_H    = 60;

    private final Screen parent;
    private long openTimeMs;
    private float animProgress = 0f;

    // Keybind capture state
    private int capturingBind = 0; // 0=none, 1=zealots, 2=bruisers

    // Button hover state
    private boolean hoverZealotBtn, hoverBruiserBtn;
    private boolean hoverZealotBind, hoverBruiserBind;
    private boolean hoverProxy, hoverPathToggle, hoverMobToggle;
    private boolean hoverClose;

    public TopographyScreen(Screen parent) {
        super(Text.literal("Topography"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        openTimeMs = System.currentTimeMillis();
    }

    public boolean isCapturingBind() {
        return capturingBind != 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  RENDER
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Animate open
        float elapsed = (System.currentTimeMillis() - openTimeMs) / 300f;
        animProgress = Math.min(1f, elapsed);
        float ease = 1f - (1f - animProgress) * (1f - animProgress);

        // Dim background
        ctx.fill(0, 0, width, height, 0xA0000000);

        // Panel dimensions
        float panelH = PANEL_PADDING * 2 + 40 + 12 + CARD_H + 16 + SETTINGS_H + 16 + BTN_H;
        float px = (width - PANEL_W) / 2f;
        float py = (height - panelH) / 2f + (1f - ease) * 20f;
        int alpha = (int)(ease * 255);

        // Main panel background
        S.drawShadow(ctx, px, py, PANEL_W, panelH, 12, 20, TopographySurfaceRenderer.withAlpha(0x000000, alpha / 3));
        S.drawOutlinedRounded(ctx, px, py, PANEL_W, panelH, 12,
            TopographySurfaceRenderer.withAlpha(BG & 0x00FFFFFF, alpha),
            TopographySurfaceRenderer.withAlpha(CARD_BORDER & 0x00FFFFFF, alpha), 1f);

        // ── Logo ──────────────────────────────────────────────────
        float logoY = py + PANEL_PADDING;
        LOGO_FONT.drawCentered(ctx, "TOPOGRAPHY", px + PANEL_W / 2, logoY, applyAlpha(TEXT_PRIMARY, alpha));

        // Accent line under logo
        float lineW = 60;
        float lineY = logoY + LOGO_FONT.lineHeight() + 4;
        S.drawRounded(ctx, px + PANEL_W / 2 - lineW / 2, lineY, lineW, 2, 1, applyAlpha(ACCENT, alpha));

        // ── Cards ─────────────────────────────────────────────────
        float cardsY = lineY + 14;
        float cardLeft  = px + PANEL_PADDING;
        float cardRight = cardLeft + CARD_W + 16;

        // Update hover states
        hoverZealotBtn  = false; hoverBruiserBtn  = false;
        hoverZealotBind = false; hoverBruiserBind = false;
        hoverProxy = false; hoverPathToggle = false; hoverMobToggle = false;
        hoverClose = false;

        drawModeCard(ctx, cardLeft, cardsY, CARD_W, CARD_H, "ZEALOTS", "Dragon's Nest",
            1, TopographyController.isZealotsActive(), mouseX, mouseY, alpha);
        drawModeCard(ctx, cardRight, cardsY, CARD_W, CARD_H, "BRUISERS", "Bruiser Hideout",
            2, TopographyController.isBruisersActive(), mouseX, mouseY, alpha);

        // ── Settings section ──────────────────────────────────────
        float settingsY = cardsY + CARD_H + 16;
        float settingsW = PANEL_W - PANEL_PADDING * 2;
        S.drawOutlinedRounded(ctx, cardLeft, settingsY, settingsW, SETTINGS_H, 8,
            applyAlpha(CARD_BG, alpha), applyAlpha(CARD_BORDER, alpha), 1f);

        float settingsPad = 12;
        float sy = settingsY + settingsPad;

        // Proxy row
        String proxyLabel = "Proxy:";
        String proxyValue = getProxyDisplayText();
        LABEL_FONT.draw(ctx, proxyLabel, cardLeft + settingsPad, sy, applyAlpha(TEXT_SECONDARY, alpha));
        float proxyTextX = cardLeft + settingsPad + LABEL_FONT.width(proxyLabel) + 6;
        SMALL_FONT.draw(ctx, proxyValue, proxyTextX, sy, applyAlpha(TEXT_PRIMARY, alpha));

        // [Configure] button
        String configLabel = "[Configure]";
        float configX = cardLeft + settingsW - settingsPad - SMALL_FONT.width(configLabel);
        hoverProxy = isInside(mouseX, mouseY, configX, sy - 2, SMALL_FONT.width(configLabel), SMALL_FONT.lineHeight() + 4);
        SMALL_FONT.draw(ctx, configLabel, configX, sy, applyAlpha(hoverProxy ? ACCENT : TEXT_SECONDARY, alpha));

        // Toggles row
        float toggleY = sy + 20;
        float toggleX = cardLeft + settingsPad;

        // Path Render toggle
        boolean pathOn = TopographyUiConfig.isPathRenderEnabled();
        String pathLabel = "Path Render: ";
        String pathValue = pathOn ? "[ON]" : "[OFF]";
        LABEL_FONT.draw(ctx, pathLabel, toggleX, toggleY, applyAlpha(TEXT_SECONDARY, alpha));
        float pathBtnX = toggleX + LABEL_FONT.width(pathLabel);
        hoverPathToggle = isInside(mouseX, mouseY, pathBtnX, toggleY - 2, LABEL_FONT.width(pathValue), LABEL_FONT.lineHeight() + 4);
        LABEL_FONT.draw(ctx, pathValue, pathBtnX, toggleY, applyAlpha(pathOn ? GREEN : RED, alpha));

        // Mob ESP toggle
        boolean mobOn = TopographyUiConfig.isMobEspEnabled();
        String mobLabel = "Mob ESP: ";
        String mobValue = mobOn ? "[ON]" : "[OFF]";
        float mobX = toggleX + 170;
        LABEL_FONT.draw(ctx, mobLabel, mobX, toggleY, applyAlpha(TEXT_SECONDARY, alpha));
        float mobBtnX = mobX + LABEL_FONT.width(mobLabel);
        hoverMobToggle = isInside(mouseX, mouseY, mobBtnX, toggleY - 2, LABEL_FONT.width(mobValue), LABEL_FONT.lineHeight() + 4);
        LABEL_FONT.draw(ctx, mobValue, mobBtnX, toggleY, applyAlpha(mobOn ? GREEN : RED, alpha));

        // ── Close button ──────────────────────────────────────────
        float closeBtnW = 100;
        float closeBtnH = BTN_H;
        float closeBtnX = px + (PANEL_W - closeBtnW) / 2;
        float closeBtnY = settingsY + SETTINGS_H + 16;
        hoverClose = isInside(mouseX, mouseY, closeBtnX, closeBtnY, closeBtnW, closeBtnH);

        S.drawOutlinedRounded(ctx, closeBtnX, closeBtnY, closeBtnW, closeBtnH, BTN_RADIUS,
            applyAlpha(hoverClose ? 0xFF1E1E24 : CARD_BG, alpha),
            applyAlpha(CARD_BORDER, alpha), 1f);
        LABEL_FONT.drawCentered(ctx, "CLOSE", closeBtnX + closeBtnW / 2, closeBtnY + (closeBtnH - LABEL_FONT.lineHeight()) / 2,
            applyAlpha(TEXT_SECONDARY, alpha));
    }

    // ═══════════════════════════════════════════════════════════════
    //  MODE CARD
    // ═══════════════════════════════════════════════════════════════

    private void drawModeCard(DrawContext ctx, float x, float y, float w, float h,
                              String title, String subtitle, int mode, boolean active,
                              int mouseX, int mouseY, int alpha) {
        boolean otherBusy = TopographyController.isAnyActive() && !active;

        // Card border glow when active
        int borderColor = active ? GREEN : CARD_BORDER;
        S.drawOutlinedRounded(ctx, x, y, w, h, CARD_RADIUS,
            applyAlpha(CARD_BG, alpha), applyAlpha(borderColor, alpha), active ? 1.5f : 1f);

        float pad = 14;
        float cy = y + pad;

        // Title
        HEADING_FONT.draw(ctx, title, x + pad, cy, applyAlpha(TEXT_PRIMARY, alpha));
        cy += HEADING_FONT.lineHeight() + 2;

        // Subtitle (location)
        SMALL_FONT.draw(ctx, subtitle, x + pad, cy, applyAlpha(TEXT_DIM, alpha));
        cy += SMALL_FONT.lineHeight() + 10;

        // Separator
        S.drawRounded(ctx, x + pad, cy, w - pad * 2, 1, 0, applyAlpha(SEPARATOR, alpha));
        cy += 9;

        // Status
        int statusColor = active ? GREEN : (otherBusy ? YELLOW : TEXT_SECONDARY);
        String statusText = active ? "Running" : (otherBusy ? "Busy" : "Idle");

        // Pulsing dot when running
        int dotAlpha = alpha;
        if (active) {
            double pulse = (Math.sin(System.currentTimeMillis() / 400.0) + 1.0) / 2.0;
            dotAlpha = (int)(alpha * (0.4 + 0.6 * pulse));
        }
        S.drawRounded(ctx, x + pad, cy + 3, 6, 6, 3, applyAlpha(statusColor, dotAlpha));
        BODY_FONT.draw(ctx, "Status: " + statusText, x + pad + 12, cy, applyAlpha(statusColor, alpha));
        cy += BODY_FONT.lineHeight() + 3;

        // Stats (only when running)
        if (active) {
            SMALL_FONT.draw(ctx, "Uptime: " + TopographyController.getUptime(), x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
            cy += SMALL_FONT.lineHeight() + 2;
            SMALL_FONT.draw(ctx, "Kills: " + TopographyController.getKillCount(), x + pad, cy, applyAlpha(TEXT_SECONDARY, alpha));
            cy += SMALL_FONT.lineHeight() + 2;
        } else {
            cy += (SMALL_FONT.lineHeight() + 2) * 2;
        }

        // ── Launch / Stop button ──────────────────────────────────
        float btnW = w - pad * 2;
        float btnX = x + pad;
        float btnY = y + h - pad - BTN_H - SMALL_FONT.lineHeight() - 6;

        boolean isHover = isInside(mouseX, mouseY, btnX, btnY, btnW, BTN_H);
        if (mode == 1) hoverZealotBtn = isHover; else hoverBruiserBtn = isHover;

        if (active) {
            // STOP button — red
            int btnBg = isHover ? 0xFFCC3344 : RED;
            S.drawRounded(ctx, btnX, btnY, btnW, BTN_H, BTN_RADIUS, applyAlpha(btnBg, alpha));
            LABEL_FONT.drawCentered(ctx, "STOP", btnX + btnW / 2, btnY + (BTN_H - LABEL_FONT.lineHeight()) / 2,
                applyAlpha(0xFFFFFFFF, alpha));
        } else if (otherBusy) {
            // Disabled grey button
            S.drawOutlinedRounded(ctx, btnX, btnY, btnW, BTN_H, BTN_RADIUS,
                applyAlpha(0xFF1A1A1E, alpha), applyAlpha(CARD_BORDER, alpha), 1f);
            LABEL_FONT.drawCentered(ctx, "LAUNCH", btnX + btnW / 2, btnY + (BTN_H - LABEL_FONT.lineHeight()) / 2,
                applyAlpha(TEXT_DIM, alpha));
        } else {
            // LAUNCH button — accent gradient
            int btnBg = isHover ? 0xFF00DAFF : ACCENT;
            S.drawRounded(ctx, btnX, btnY, btnW, BTN_H, BTN_RADIUS, applyAlpha(btnBg, alpha));
            LABEL_FONT.drawCentered(ctx, "LAUNCH", btnX + btnW / 2, btnY + (BTN_H - LABEL_FONT.lineHeight()) / 2,
                applyAlpha(0xFF0D0D0F, alpha));
        }

        // ── Keybind row ──────────────────────────────────────────
        float bindY = btnY + BTN_H + 6;
        int keyCode = (mode == 1) ? TopographyUiConfig.getZealotsKeyCode() : TopographyUiConfig.getBruisersKeyCode();
        boolean capturing = capturingBind == mode;
        String bindText = capturing ? "[...]" : "[" + TopographyUiConfig.getKeyLabel(keyCode) + "]";
        String bindLabel = "bind: " + bindText;

        float bindW = SMALL_FONT.width(bindLabel);
        float bindX = x + pad;
        boolean bindHover = isInside(mouseX, mouseY, bindX, bindY - 2, bindW, SMALL_FONT.lineHeight() + 4);
        if (mode == 1) hoverZealotBind = bindHover; else hoverBruiserBind = bindHover;

        int bindColor = capturing ? ACCENT : (bindHover ? TEXT_PRIMARY : TEXT_SECONDARY);
        SMALL_FONT.draw(ctx, bindLabel, bindX, bindY, applyAlpha(bindColor, alpha));
    }

    // ═══════════════════════════════════════════════════════════════
    //  INPUT
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (click.buttonInfo().button() != 0) return super.mouseClicked(click, bl);

        // Keybind capture click
        if (hoverZealotBind) {
            capturingBind = (capturingBind == 1) ? 0 : 1;
            return true;
        }
        if (hoverBruiserBind) {
            capturingBind = (capturingBind == 2) ? 0 : 2;
            return true;
        }

        // Cancel capture if clicking elsewhere
        if (capturingBind != 0 && !hoverZealotBind && !hoverBruiserBind) {
            capturingBind = 0;
        }

        // Launch/Stop buttons
        if (hoverZealotBtn) {
            TopographyController.toggleZealots();
            return true;
        }
        if (hoverBruiserBtn) {
            TopographyController.toggleBruisers();
            return true;
        }

        // Settings
        if (hoverProxy) {
            MinecraftClient.getInstance().setScreen(new ProxyScreen(this));
            return true;
        }
        if (hoverPathToggle) {
            TopographyUiConfig.setPathRenderEnabled(!TopographyUiConfig.isPathRenderEnabled());
            return true;
        }
        if (hoverMobToggle) {
            TopographyUiConfig.setMobEspEnabled(!TopographyUiConfig.isMobEspEnabled());
            return true;
        }

        // Close
        if (hoverClose) {
            close();
            return true;
        }

        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        // Capturing keybind
        if (capturingBind != 0) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // Unbind
                if (capturingBind == 1) TopographyUiConfig.setZealotsKeyCode(GLFW.GLFW_KEY_UNKNOWN);
                else TopographyUiConfig.setBruisersKeyCode(GLFW.GLFW_KEY_UNKNOWN);
            } else {
                if (capturingBind == 1) TopographyUiConfig.setZealotsKeyCode(keyCode);
                else TopographyUiConfig.setBruisersKeyCode(keyCode);
            }
            capturingBind = 0;
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

    private static boolean isInside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int applyAlpha(int color, int alpha) {
        int origAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (origAlpha * alpha) / 255;
        return TopographySurfaceRenderer.withAlpha(color, newAlpha);
    }
}
