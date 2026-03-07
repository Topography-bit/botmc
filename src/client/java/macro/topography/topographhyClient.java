package macro.topography;

import net.fabricmc.api.ClientModInitializer;

public class topographhyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ProxyConfig.load();
		TopographyUiConfig.load();
		DataCollector.register();
		CollectCommand.register();
		ProxyCommand.register();
		ActionExecutor.register();
		BotCommand.register();
		ZoneCommand.register();
		PosCommand.register();
		PathRenderer.register();
		TopographyController.register();
		TopographyCommand.register();
	}
}

