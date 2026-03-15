package macro.topography;

import net.fabricmc.api.ClientModInitializer;

public class topographhyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModeRegistry.init();
		ProxyConfig.load();
		TopographyUiConfig.load();
		ProxyCommand.register();
		Autopilot.register();
		ZoneCommand.register();
		PosCommand.register();
		PathRenderer.register();
		CameraDebugHud.register();
		AutopilotDebugHud.register();
		TopographyController.register();
		ReconnectManager.register();
		AutoJoinManager.register();
		TopographyCommand.register();
	}
}
