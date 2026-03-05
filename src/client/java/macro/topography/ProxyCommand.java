package macro.topography;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ProxyCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("proxy")
                .then(literal("add")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("host", StringArgumentType.word())
                            .executes(ctx -> add(ctx,
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "host"),
                                8000))
                            .then(argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> add(ctx,
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "host"),
                                    IntegerArgumentType.getInteger(ctx, "port")))))))
                .then(literal("remove")
                    .then(argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> remove(ctx, IntegerArgumentType.getInteger(ctx, "index")))))
                .then(literal("list")
                    .executes(ProxyCommand::list))
                .then(literal("select")
                    .then(argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> select(ctx, IntegerArgumentType.getInteger(ctx, "index")))))
                .then(literal("on")
                    .executes(ProxyCommand::on))
                .then(literal("off")
                    .executes(ProxyCommand::off))
                .then(literal("status")
                    .executes(ProxyCommand::status))
            )
        );
    }

    private static int add(CommandContext<FabricClientCommandSource> ctx, String name, String host, int port) {
        ProxyConfig.addProxy(name, host, port, "", "");
        ProxyConfig.save();
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Добавлен: " + name + " -> " + host + ":" + port));
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Username/password можно задать через GUI (кнопка Прокси)"));
        return 1;
    }

    private static int remove(CommandContext<FabricClientCommandSource> ctx, int index) {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        int i = index - 1;
        if (i < 0 || i >= proxies.size()) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Неверный индекс: " + index));
            return 0;
        }
        String name = proxies.get(i).name;
        ProxyConfig.removeProxy(i);
        ProxyConfig.save();
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Удалён: " + name));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        if (proxies.isEmpty()) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Список пуст. Добавь: /proxy add <name> <host> [port]"));
            return 1;
        }
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Список прокси:"));
        for (int i = 0; i < proxies.size(); i++) {
            ProxyConfig.ProxyEntry e = proxies.get(i);
            String linked = e.linkedAccountUuid != null ? " [привязан]" : "";
            String status = e.enabled ? " [ON]" : " [OFF]";
            ctx.getSource().sendFeedback(Text.literal(
                "  " + (i + 1) + ". " + e.name + " - " + e.host + ":" + e.port + linked + status
            ));
        }
        return 1;
    }

    private static int select(CommandContext<FabricClientCommandSource> ctx, int index) {
        List<ProxyConfig.ProxyEntry> proxies = ProxyConfig.getProxies();
        int i = index - 1;
        if (i < 0 || i >= proxies.size()) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Неверный индекс: " + index));
            return 0;
        }
        ProxyConfig.linkCurrentAccountTo(i);
        ProxyConfig.save();
        ProxyConfig.ProxyEntry e = proxies.get(i);
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Выбран: " + e.name + " (" + e.host + ":" + e.port + ")"));
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Перезайди на сервер для применения."));
        return 1;
    }

    private static int on(CommandContext<FabricClientCommandSource> ctx) {
        boolean ok = ProxyConfig.enableForCurrentAccount();
        if (!ok) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Нет привязанного прокси. Сначала: /proxy select <index>"));
            return 0;
        }
        ProxyConfig.save();
        ProxyConfig.ProxyEntry e = ProxyConfig.getLinkedProxy();
        String name = e != null ? e.name : "?";
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Включён: " + name + ". Перезайди на сервер."));
        return 1;
    }

    private static int off(CommandContext<FabricClientCommandSource> ctx) {
        boolean ok = ProxyConfig.disableForCurrentAccount();
        if (!ok) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Нет привязанного прокси."));
            return 0;
        }
        ProxyConfig.save();
        ctx.getSource().sendFeedback(Text.literal("[Proxy] Выключен. Перезайди на сервер."));
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        ProxyConfig.ProxyEntry e = ProxyConfig.getLinkedProxy();
        if (e == null) {
            ctx.getSource().sendFeedback(Text.literal("[Proxy] Нет привязки к текущему аккаунту."));
        } else {
            String state = e.enabled ? "ON" : "OFF";
            ctx.getSource().sendFeedback(Text.literal(
                "[Proxy] " + e.name + " -> " + e.host + ":" + e.port + " [" + state + "]"
            ));
        }
        if (ProxyConfig.lastConnectionUsedProxy) {
            ctx.getSource().sendFeedback(Text.literal(
                "[Proxy] Текущее соединение через SOCKS5: " + ProxyConfig.lastProxyAddress
            ));
        } else {
            ctx.getSource().sendFeedback(Text.literal(
                "[Proxy] Текущее соединение: НАПРЯМУЮ (без прокси)"
            ));
        }
        return 1;
    }
}