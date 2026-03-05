package macro.topography.mixin.client;

import io.netty.channel.Channel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import macro.topography.ProxyConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

@Mixin(targets = "net.minecraft.network.ClientConnection$1")
public class ClientConnectionMixin {

    @Inject(method = "initChannel", at = @At("HEAD"))
    private void injectSocks5Proxy(Channel channel, CallbackInfo ci) {
        ProxyConfig.lastConnectionUsedProxy = false;
        ProxyConfig.lastProxyAddress = "";

        if (!ProxyConfig.isEnabled()) return;

        ProxyConfig.ProxyEntry entry = ProxyConfig.getActiveEntry();
        if (entry == null) return;

        InetSocketAddress proxyAddr = new InetSocketAddress(entry.host, entry.port);
        Socks5ProxyHandler handler;

        if (entry.hasAuth()) {
            handler = new Socks5ProxyHandler(proxyAddr, entry.username, entry.password);
        } else {
            handler = new Socks5ProxyHandler(proxyAddr);
        }

        channel.pipeline().addFirst("socks5_proxy", handler);

        String addr = entry.host + ":" + entry.port;
        ProxyConfig.lastConnectionUsedProxy = true;
        ProxyConfig.lastProxyAddress = addr;
        System.out.println("[TopographyProxy] SOCKS5 injected -> " + addr
                + (entry.hasAuth() ? " (with auth)" : " (no auth)"));
    }
}
