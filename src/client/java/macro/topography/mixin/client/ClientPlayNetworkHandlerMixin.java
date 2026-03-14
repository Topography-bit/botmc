package macro.topography.mixin.client;

import macro.topography.Autopilot;
import macro.topography.ReconnectManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (packet.overlay()) return; // skip action bar
        String raw = packet.content().getString().replaceAll("\u00a7.", "");
        ReconnectManager.onChatMessage(raw);
        Autopilot.onChatMessage(raw);
    }
}
