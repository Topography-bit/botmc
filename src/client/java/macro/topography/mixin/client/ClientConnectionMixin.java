package macro.topography.mixin.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import macro.topography.ProxyConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

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
        ChannelHandler handler;

        try {
            Class<?> clazz = Class.forName("io.netty.handler.proxy.Socks5ProxyHandler");
            if (entry.hasAuth()) {
                Constructor<?> ctor = clazz.getConstructor(SocketAddress.class, String.class, String.class);
                handler = (ChannelHandler) ctor.newInstance(proxyAddr, entry.username, entry.password);
            } else {
                Constructor<?> ctor = clazz.getConstructor(SocketAddress.class);
                handler = (ChannelHandler) ctor.newInstance(proxyAddr);
            }
        } catch (ClassNotFoundException e) {
            System.err.println("[TopographyProxy] netty-handler-proxy not found on classpath, SOCKS5 proxy unavailable");
            return;
        } catch (Exception e) {
            System.err.println("[TopographyProxy] Failed to create Socks5ProxyHandler: " + e.getMessage());
            return;
        }

        channel.pipeline().addFirst("socks5_proxy", handler);

        String addr = entry.host + ":" + entry.port;
        ProxyConfig.lastConnectionUsedProxy = true;
        ProxyConfig.lastProxyAddress = addr;
        System.out.println("[TopographyProxy] SOCKS5 injected -> " + addr
                + (entry.hasAuth() ? " (with auth)" : " (no auth)"));
    }
}
