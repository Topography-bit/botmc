package macro.topography;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.Map;

public final class TopographyController {

    private static final Map<TopographyModuleDefinition, Boolean> KEY_STATE = new EnumMap<>(TopographyModuleDefinition.class);
    private static boolean screenOpenScheduled;

    // Zone transition tracking
    private static boolean wasInZealotZone = false;
    private static boolean wasInBruiserZone = false;

    private TopographyController() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(TopographyController::onClientTick);
    }

    public static void openScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        screenOpenScheduled = true;
    }

    public static boolean toggle(TopographyModuleDefinition module) {
        return switch (module.type()) {
            case RECORDING -> toggleRecording(module);
            case COMBAT -> toggleCombat(module);
        };
    }

    public static boolean isActive(TopographyModuleDefinition module) {
        return switch (module.type()) {
            case RECORDING -> DataCollector.isRecording && DataCollector.getTargetMode() == module.mode();
            case COMBAT -> ActionExecutor.active && ActionExecutor.getTargetMode() == module.mode();
        };
    }

    public static String getStatusLabel(TopographyModuleDefinition module) {
        if (isActive(module)) {
            return module.activeBadge();
        }

        return switch (module.type()) {
            case RECORDING -> DataCollector.isRecording ? "Busy" : "Idle";
            case COMBAT -> ActionExecutor.active ? "Busy" : "Idle";
        };
    }

    public static int getStatusColor(TopographyModuleDefinition module) {
        if (isActive(module)) {
            return module.type() == TopographyModuleType.RECORDING ? 0xFFD68E3E : 0xFF49D28B;
        }

        return switch (module.type()) {
            case RECORDING -> DataCollector.isRecording ? 0xFFB77C57 : 0xFF7C879B;
            case COMBAT -> ActionExecutor.active ? 0xFFB77C57 : 0xFF7C879B;
        };
    }

    private static void onClientTick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        if (screenOpenScheduled) {
            screenOpenScheduled = false;
            Screen parent = client.currentScreen;
            System.out.println("[Topography] Opening menu screen");
            client.setScreen(new TopographyScreen(parent));
            return;
        }
        if (client.currentScreen instanceof TopographyScreen screen && screen.isCapturingBind()) {
            return;
        }

        checkZoneTransitions(client);

        for (TopographyModuleDefinition module : TopographyModuleDefinition.values()) {
            if (!TopographyUiConfig.isBindEnabled(module)) {
                KEY_STATE.put(module, false);
                continue;
            }

            int keyCode = TopographyUiConfig.getKeyCode(module);
            boolean pressed = keyCode != InputUtil.UNKNOWN_KEY.getCode() && InputUtil.isKeyPressed(client.getWindow(), keyCode);
            boolean wasPressed = KEY_STATE.getOrDefault(module, false);

            if (pressed && !wasPressed) {
                toggle(module);
            }

            KEY_STATE.put(module, pressed);
        }
    }

    private static void checkZoneTransitions(MinecraftClient client) {
        if (client.player == null || client.inGameHud == null) return;
        net.minecraft.util.math.Vec3d pos = client.player.getEntityPos();
        int x = (int) pos.x, y = (int) pos.y, z = (int) pos.z;

        boolean inZealot  = Pathfinder.isInFarmZone(pos, 1);
        boolean inBruiser = Pathfinder.isInFarmZone(pos, 2);

        if (inZealot && !wasInZealotZone) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§a[ВОШЁЛ] Zealot zone | " + x + " " + y + " " + z));
        } else if (!inZealot && wasInZealotZone) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§c[ВЫШЕЛ] Zealot zone | " + x + " " + y + " " + z));
        }
        wasInZealotZone = inZealot;

        if (inBruiser && !wasInBruiserZone) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§a[ВОШЁЛ] Bruiser zone | " + x + " " + y + " " + z));
        } else if (!inBruiser && wasInBruiserZone) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§c[ВЫШЕЛ] Bruiser zone | " + x + " " + y + " " + z));
        }
        wasInBruiserZone = inBruiser;
    }

    private static boolean toggleRecording(TopographyModuleDefinition module) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            notify("Join a world before starting recording.");
            return false;
        }

        if (isActive(module)) {
            DataCollector.stopCollecting();
            notify(module.title() + " stopped.");
            return true;
        }

        if (DataCollector.isRecording) {
            DataCollector.stopCollecting();
        }

        DataCollector.startCollecting(module.mode());
        if (!DataCollector.isRecording) {
            notify("Failed to start " + module.title() + ".");
            return false;
        }

        notify(module.title() + " started.");
        return true;
    }

    private static boolean toggleCombat(TopographyModuleDefinition module) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            notify("Join a world before starting a bot.");
            return false;
        }

        String modelPath = TopographyUiConfig.getModelPath(module);

        if (isActive(module)) {
            ActionExecutor.stop();
            notify(module.title() + " stopped.");
            return true;
        }

        if (ActionExecutor.active) {
            ActionExecutor.stop();
        }

        if (!ActionExecutor.loadModel(modelPath)) {
            notify("Model not found: " + modelPath);
            return false;
        }

        ActionExecutor.start(module.mode());
        notify(module.title() + " started with " + modelPath + ".");
        return true;
    }

    private static void notify(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(Text.literal("[Topography] " + message));
            } else {
                System.out.println("[Topography] " + message);
            }
        });
    }
}
