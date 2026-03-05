package macro.topography.mixin.client;

import macro.topography.DataCollector;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseDeltaMixin {
	@Shadow private double cursorDeltaX;
	@Shadow private double cursorDeltaY;

	@Inject(method = "updateMouse", at = @At("HEAD"))
	private void captureDelta(CallbackInfo info) {
		DataCollector.rawDX += this.cursorDeltaX;
		DataCollector.rawDY += this.cursorDeltaY;
	}
}