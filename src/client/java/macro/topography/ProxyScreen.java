package macro.topography;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.ChannelHandler;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

public class ProxyScreen extends Screen {

    private static final String TEST_HOST = "mc.hypixel.net";
    private static final int TEST_PORT = 25565;
    private static final int TEST_TIMEOUT_MS = 5000;

    private final Screen parent;
    private int selectedIndex = -1;
    private String testResult = "";
    private int testResultColor = 0xAAAAAA;

    // detail fields state
    private String fName = "";
    private String fHost = "";
    private String fPort = "8000";
    private String fUser = "";
    private String fPass = "";

    public ProxyScreen(Screen parent) {
        super(Text.literal("Proxy Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        int leftW = 130;
        int leftX = 10;
        int rightX = leftX + leftW + 10;
        int rightW = this.width - rightX - 10;
        String myUuid = getMyUuid();

        // === LEFT PANEL: proxy list ===
        for (int i = 0; i < proxies.size(); i++) {
            final int idx = i;
            ProxyConfig.ProxyEntry e = proxies.get(idx);
            boolean mine = myUuid != null && myUuid.equals(e.linkedAccountUuid);
            String label = e.name;
            if (label.length() > 14) label = label.substring(0, 14);
            if (mine && e.enabled) label = "\u00a7a" + label;
            else if (mine) label = "\u00a76" + label;

            addDrawableChild(ButtonWidget.builder(Text.literal(label), btn -> {
                selectedIndex = idx;
                loadFromEntry(proxies.get(idx));
                testResult = "";
                rebuild();
            }).dimensions(leftX, 38 + i * 22, leftW, 20).build());
        }

        // Add proxy button at bottom of list
        int addY = 38 + proxies.size() * 22 + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Proxy"), btn -> {
            ProxyConfig.addProxy("Proxy " + (proxies.size() + 1), "", 8000, "", "");
            ProxyConfig.save();
            selectedIndex = ProxyConfig.getProxies().size() - 1;
            loadFromEntry(ProxyConfig.getProxies().get(selectedIndex));
            testResult = "";
            rebuild();
        }).dimensions(leftX, addY, leftW, 20).build());

        // === RIGHT PANEL: details (only if something selected) ===
        if (selectedIndex >= 0 && selectedIndex < proxies.size()) {
            int fy = 38;
            int fieldW = rightW - 80;
            int smallFieldW = 60;

            // Name
            TextFieldWidget nameField = new TextFieldWidget(textRenderer, rightX + 70, fy, fieldW, 18, Text.empty());
            nameField.setMaxLength(32);
            nameField.setText(fName);
            nameField.setChangedListener(t -> fName = t);
            addDrawableChild(nameField);
            fy += 24;

            // Host + Port on same row
            int hostW = fieldW - smallFieldW - 6;
            TextFieldWidget hostField = new TextFieldWidget(textRenderer, rightX + 70, fy, hostW, 18, Text.empty());
            hostField.setMaxLength(64);
            hostField.setText(fHost);
            hostField.setPlaceholder(Text.literal("127.0.0.1"));
            hostField.setChangedListener(t -> fHost = t);
            addDrawableChild(hostField);

            TextFieldWidget portField = new TextFieldWidget(textRenderer, rightX + 70 + hostW + 6, fy, smallFieldW, 18, Text.empty());
            portField.setMaxLength(5);
            portField.setText(fPort);
            portField.setPlaceholder(Text.literal("8000"));
            portField.setChangedListener(t -> fPort = t);
            addDrawableChild(portField);
            fy += 30;

            // Username
            TextFieldWidget userField = new TextFieldWidget(textRenderer, rightX + 70, fy, fieldW, 18, Text.empty());
            userField.setMaxLength(64);
            userField.setText(fUser);
            userField.setPlaceholder(Text.literal("(optional)"));
            userField.setChangedListener(t -> fUser = t);
            addDrawableChild(userField);
            fy += 24;

            // Password
            TextFieldWidget passField = new TextFieldWidget(textRenderer, rightX + 70, fy, fieldW, 18, Text.empty());
            passField.setMaxLength(64);
            passField.setText(fPass);
            passField.setPlaceholder(Text.literal("(optional)"));
            passField.setChangedListener(t -> fPass = t);
            addDrawableChild(passField);
            fy += 32;

            // Buttons row
            int btnGap = 4;
            int btnW = Math.max(40, (rightW - btnGap * 3) / 4);
            int bx = rightX;

            addDrawableChild(ButtonWidget.builder(Text.literal("Save"), btn -> {
                saveToEntry(selectedIndex);
                rebuild();
            }).dimensions(bx, fy, btnW, 20).build());
            bx += btnW + btnGap;

            addDrawableChild(ButtonWidget.builder(Text.literal("Select"), btn -> {
                saveToEntry(selectedIndex);
                ProxyConfig.linkCurrentAccountTo(selectedIndex);
                ProxyConfig.save();
                rebuild();
            }).dimensions(bx, fy, btnW, 20).build());
            bx += btnW + btnGap;

            addDrawableChild(ButtonWidget.builder(Text.literal("Test"), btn -> {
                saveToEntry(selectedIndex);
                List<ProxyConfig.ProxyEntry> p = ProxyConfig.getProxies();
                if (selectedIndex < 0 || selectedIndex >= p.size()) return;
                ProxyConfig.ProxyEntry te = p.get(selectedIndex);
                if (te.host == null || te.host.isEmpty()) {
                    testResult = "Enter host first!";
                    testResultColor = 0xFF5555;
                    return;
                }
                testResult = "Testing " + te.host + ":" + te.port + "...";
                testResultColor = 0xFFFF55;
                runPingTest(selectedIndex);
            }).dimensions(bx, fy, btnW, 20).build());
            bx += btnW + btnGap;

            addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7cDelete"), btn -> {
                ProxyConfig.removeProxy(selectedIndex);
                ProxyConfig.save();
                selectedIndex = -1;
                testResult = "";
                rebuild();
            }).dimensions(bx, fy, btnW, 20).build());
        }

        // === BOTTOM: toggle + close ===
        int bottomY = this.height - 28;
        ProxyConfig.ProxyEntry linked = ProxyConfig.getLinkedProxy();
        boolean isOn = linked != null && linked.enabled;

        addDrawableChild(ButtonWidget.builder(
            Text.literal(isOn ? "\u00a7aON" : "\u00a7cOFF"),
            btn -> {
                if (isOn) ProxyConfig.disableForCurrentAccount();
                else ProxyConfig.enableForCurrentAccount();
                ProxyConfig.save();
                rebuild();
            }
        ).dimensions(10, bottomY, 50, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> close())
                .dimensions(this.width - 70, bottomY, 60, 20).build());
    }

    private void loadFromEntry(ProxyConfig.ProxyEntry e) {
        fName = e.name != null ? e.name : "";
        fHost = e.host != null ? e.host : "";
        fPort = String.valueOf(e.port);
        fUser = e.username != null ? e.username : "";
        fPass = e.password != null ? e.password : "";
    }

    private void saveToEntry(int index) {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        if (index < 0 || index >= proxies.size()) return;
        ProxyConfig.ProxyEntry e = proxies.get(index);
        e.name = fName.trim();
        e.host = fHost.trim();
        try { e.port = Integer.parseInt(fPort.trim()); } catch (NumberFormatException ignored) { e.port = 8000; }
        e.username = fUser.trim();
        e.password = fPass.trim();
        ProxyConfig.save();
    }

    private void runPingTest(int idx) {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        if (idx < 0 || idx >= proxies.size()) return;
        ProxyConfig.ProxyEntry entry = proxies.get(idx);
        String host = entry.host;
        int port = entry.port;
        String user = entry.username;
        String pass = entry.password;
        boolean hasAuth = entry.hasAuth();

        Thread t = new Thread(() -> {
            String result;
            int color;
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                InetSocketAddress proxyAddr = new InetSocketAddress(host, port);
                Class<?> clazz = Class.forName("io.netty.handler.proxy.Socks5ProxyHandler");
                ChannelHandler proxyHandler;
                if (hasAuth) {
                    Constructor<?> ctor = clazz.getConstructor(SocketAddress.class, String.class, String.class);
                    proxyHandler = (ChannelHandler) ctor.newInstance(proxyAddr, user, pass);
                } else {
                    Constructor<?> ctor = clazz.getConstructor(SocketAddress.class);
                    proxyHandler = (ChannelHandler) ctor.newInstance(proxyAddr);
                }
                clazz.getMethod("setConnectTimeoutMillis", long.class).invoke(proxyHandler, (long) TEST_TIMEOUT_MS);

                long t0 = System.currentTimeMillis();
                ChannelFuture cf = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addFirst("socks5_proxy", proxyHandler);
                        }
                    })
                    .connect(TEST_HOST, TEST_PORT)
                    .sync();

                long ping = System.currentTimeMillis() - t0;
                cf.channel().close().sync();
                result = "OK: " + ping + "ms";
                color = 0x55FF55;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage();
                if (msg == null) msg = cause.getClass().getSimpleName();
                if (msg.length() > 40) msg = msg.substring(0, 40);
                result = "FAIL: " + msg;
                color = 0xFF5555;
            } finally {
                group.shutdownGracefully();
            }
            String finalResult = result;
            int finalColor = color;
            this.client.execute(() -> {
                testResult = finalResult;
                testResultColor = finalColor;
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        int leftW = 130;
        int leftX = 10;
        int rightX = leftX + leftW + 10;
        int rightW = this.width - rightX - 10;
        String myUuid = getMyUuid();

        // Left panel background
        ctx.fill(leftX - 4, 20, leftX + leftW + 4, this.height - 36, 0x88000000);

        // Right panel background
        if (selectedIndex >= 0 && selectedIndex < proxies.size()) {
            ctx.fill(rightX - 4, 20, rightX + rightW + 4, this.height - 36, 0x88000000);
        }

        // Title
        ctx.drawTextWithShadow(textRenderer, Text.literal("Proxies"), leftX, 26, 0xFFFFFF);

        // Active indicator on left
        ProxyConfig.ProxyEntry linked = ProxyConfig.getLinkedProxy();
        if (linked != null) {
            String tag = linked.enabled ? "\u00a7aActive" : "\u00a76Paused";
            ctx.drawTextWithShadow(textRenderer, Text.literal(tag), leftX + leftW - 30, 26, 0xFFFFFF);
        }

        // Proxy addresses under buttons
        for (int i = 0; i < proxies.size(); i++) {
            ProxyConfig.ProxyEntry e = proxies.get(i);
            boolean mine = myUuid != null && myUuid.equals(e.linkedAccountUuid);
            int addrColor = mine ? (e.enabled ? 0x55FF55 : 0xFFAA00) : 0x777777;
            String addr = e.host + ":" + e.port;
            if (addr.length() > 20) addr = addr.substring(0, 20);
            // Don't draw address text - it's already in the button
        }

        // Right panel content
        if (selectedIndex >= 0 && selectedIndex < proxies.size()) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Details"), rightX, 26, 0xFFFFFF);

            int fy = 38;
            ctx.drawTextWithShadow(textRenderer, Text.literal("Name"), rightX, fy + 5, 0xAAAAAA);
            fy += 24;
            ctx.drawTextWithShadow(textRenderer, Text.literal("Host"), rightX, fy + 5, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Port"), rightX + 70 + (rightW - 80 - 60 - 6) + 6, fy - 7, 0xAAAAAA);
            fy += 30;
            ctx.drawTextWithShadow(textRenderer, Text.literal("User"), rightX, fy + 5, 0xAAAAAA);
            fy += 24;
            ctx.drawTextWithShadow(textRenderer, Text.literal("Pass"), rightX, fy + 5, 0xAAAAAA);
            fy += 32;

            // Test result
            if (!testResult.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(testResult), rightX, fy + 26, testResultColor);
            }

            // Selected proxy info
            ProxyConfig.ProxyEntry sel = proxies.get(selectedIndex);
            boolean isMine = myUuid != null && myUuid.equals(sel.linkedAccountUuid);
            if (isMine) {
                String state = sel.enabled ? "\u00a7aBound to this account [ON]" : "\u00a76Bound to this account [OFF]";
                ctx.drawTextWithShadow(textRenderer, Text.literal(state), rightX, fy + 40, 0xFFFFFF);
            }
        } else {
            // No selection hint
            int cx = rightX + rightW / 2;
            int cy = this.height / 2;
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Select a proxy or add one"), cx, cy, 0x555555);
        }

        // Bottom status bar
        int bottomY = this.height - 28;
        if (linked != null) {
            String status = linked.enabled
                ? "SOCKS5: " + linked.host + ":" + linked.port
                : "Proxy paused";
            ctx.drawTextWithShadow(textRenderer, Text.literal(status), 68, bottomY + 6, 0xAAAAAA);
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("No proxy"), 68, bottomY + 6, 0x555555);
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private String getMyUuid() {
        if (this.client == null || this.client.getSession() == null) return null;
        var uuid = this.client.getSession().getUuidOrNull();
        return uuid != null ? uuid.toString() : null;
    }
}
