package macro.topography.mixin.client;

import macro.topography.DataCollector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void onServerTeleport(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        DataCollector.serverPosEvent = true;
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"))
    private void onServerVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && packet.getEntityId() == client.player.getId()) {
            DataCollector.serverVelEvent = true;
        }
    }
}
