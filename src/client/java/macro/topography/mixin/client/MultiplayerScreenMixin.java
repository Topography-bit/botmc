package macro.topography.mixin.client;

import macro.topography.ProxyScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addProxyButton(CallbackInfo ci) {
        addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Прокси"),
                btn -> this.client.setScreen(new ProxyScreen(this))
            ).dimensions(this.width - 88, 4, 84, 18).build()
        );
    }
}
