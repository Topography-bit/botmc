package macro.topography.mixin.client;

import macro.topography.TopographyController;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void topography$interceptCommand(String chatText, boolean addToHistory, CallbackInfo ci) {
        if (chatText == null) {
            return;
        }

        String normalized = chatText.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("/topography") || normalized.equals("/topography open")) {
            System.out.println("[Topography] Intercepted command: " + normalized);
            TopographyController.openScreen();
            ci.cancel();
        }
    }
}
