package macro.topography.mixin.client;

import macro.topography.ManaTracker;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class ActionBarMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void onActionBarMixin(Text message, boolean tinted, CallbackInfo ci) {
        if (message == null) return;

        String raw = message.getString();
        String clean = raw.replaceAll("\u00a7.", "");
        if (!clean.contains("\u270e Mana")) return;

        try {
            int manaIdx = clean.indexOf("\u270e Mana");
            String be4Mana = clean.substring(0, manaIdx).trim();
            int lastSpace = be4Mana.lastIndexOf(" ");
            String manaPart = be4Mana.substring(lastSpace + 1);
            String[] parts = manaPart.split("/");
            if (parts.length < 2) return;

            ManaTracker.currentMana = Integer.parseInt(parts[0].replace(",", ""));
            ManaTracker.maxMana = Integer.parseInt(parts[1].replace(",", ""));
        } catch (Exception ignored) {}
    }
}
