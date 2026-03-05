package macro.topography;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class DataCollector {
    public static double rawDX = 0;
    public static double rawDY = 0;
    public static int currentMana = 0;
    public static int maxMana = 0;

    public static boolean isRecording = false;
    private static int targetMode = 0;
    public static int currentMode = 0;

    public static volatile boolean serverPosEvent = false;
    public static volatile boolean serverVelEvent = false;
    public static float currentStress = 0f;
    public static int aoteGraceTicks = 0;

    private static PrintWriter csvWriter = null;
    private static int tickCounter = 0;
    private static final int FLUSH_EVERY = 100;

    private static final String CSV_HEADER =
        "active_slot,on_ground,is_sprinting,is_jumping,yaw,pitch," +
        "key_w,key_s,key_a,key_d,slot_to_press,l_click,r_click," +
        "delta_yaw,delta_pitch,vel_x,vel_y,vel_z,accel_x,accel_y,accel_z," +
        "dist_to_wall,dist_wall_right,dist_wall_left," +
        "dist_to_block,block_height,dist_to_block_left,block_height_left," +
        "dist_to_block_right,block_height_right," +
        "vertical_clearance,mana,mana_cost_aotev,slot_aotev," +
        "time_to_collision,current_mode,target_mode," +
        "ground_near,ground_mid,ground_far," +
        "e0_rel_x,e0_rel_y,e0_rel_z,e0_type,e0_visible,e0_yaw_diff,e0_comp_count,e0_comp_min_dist,e0_comp_intent," +
        "e1_rel_x,e1_rel_y,e1_rel_z,e1_type,e1_visible,e1_yaw_diff,e1_comp_count,e1_comp_min_dist,e1_comp_intent," +
        "e2_rel_x,e2_rel_y,e2_rel_z,e2_type,e2_visible,e2_yaw_diff,e2_comp_count,e2_comp_min_dist,e2_comp_intent," +
        "e3_rel_x,e3_rel_y,e3_rel_z,e3_type,e3_visible,e3_yaw_diff,e3_comp_count,e3_comp_min_dist,e3_comp_intent," +
        "e4_rel_x,e4_rel_y,e4_rel_z,e4_type,e4_visible,e4_yaw_diff,e4_comp_count,e4_comp_min_dist,e4_comp_intent," +
        "rot_anomaly,pos_anomaly,vel_anomaly,stress_level," +
        "path_rel_x,path_rel_y,path_rel_z";

    private static Vec3d prevVelocity = Vec3d.ZERO;
    private static Vec3d prevPos = null;
    private static float prevYaw = 0f;
    private static float prevPitch = 0f;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player == null) return;

            rawDX = 0;
            rawDY = 0;

            Vec3d vel = player.getVelocity();
            Vec3d pos = player.getEntityPos();
            float yawRaw = player.getYaw();
            float pitchRaw = player.getPitch();

            if (!isRecording) {
                prevVelocity = vel;
                prevPos = pos;
                prevYaw = yawRaw;
                prevPitch = pitchRaw;
                return;
            }

            // [0,1]: yaw 0-360 -> 0-1, pitch -90..90 -> 0-1
            float yaw = ((yawRaw % 360f + 360) % 360f) / 360f;
            float pitch = (pitchRaw + 90) / 180f;

            // [0,1]: slot 0-8 -> 0-1
            float activeSlot = player.getInventory().getSelectedSlot() / 8.0f;
            int on_ground = player.isOnGround() ? 1 : 0;
            int is_sprinting = player.isSprinting() ? 1 : 0;

            GameOptions opt = client.options;
            int is_jumping = opt.jumpKey.isPressed() ? 1 : 0;
            int key_W = opt.forwardKey.isPressed() ? 1 : 0;
            int key_S = opt.backKey.isPressed() ? 1 : 0;
            int key_A = opt.leftKey.isPressed() ? 1 : 0;
            int key_D = opt.rightKey.isPressed() ? 1 : 0;
            // [0,1]: 0=not pressed, 1/9..9/9=slot index
            float pressedSlot = getPressedSlotKey(client);
            int leftClick = opt.attackKey.isPressed() ? 1 : 0;
            int rightClick = opt.useKey.isPressed() ? 1 : 0;

            if (aoteGraceTicks > 0) {
                aoteGraceTicks--;
            }

            ItemStack heldItem = player.getMainHandStack();
            if (heldItem != null) {
                String itemName = heldItem.getName().getString().replaceAll("§.", "");
                if ((itemName.contains("Aspect of the End") || itemName.contains("Aspect of the Void")) && rightClick == 1) {
                    aoteGraceTicks = 60;
                }
            }

            // [0,1]: delta yaw in [-30,30] -> 0-1, delta pitch in [-30,30] -> 0-1
            float deltaYaw = clamp01((normalizeAngle(yawRaw - prevYaw) + 30f) / 60f);
            float deltaPitch = clamp01((pitchRaw - prevPitch + 30f) / 60f);

            // [0,1]: velocity assumed in [-1,1]
            float velX = clamp01((float) ((vel.x + 1) / 2.0));
            float velY = clamp01((float) ((vel.y + 1) / 2.0));
            float velZ = clamp01((float) ((vel.z + 1) / 2.0));
            // [0,1]: accel delta assumed in [-0.5,0.5]
            float accelX = clamp01((float) ((vel.x - prevVelocity.x + 0.5) / 1.0));
            float accelY = clamp01((float) ((vel.y - prevVelocity.y + 0.5) / 1.0));
            float accelZ = clamp01((float) ((vel.z - prevVelocity.z + 0.5) / 1.0));

            // [0,1]: distance/10, capped at 1.0
            float distWall = RaycastHelper.calcDistToWallOrBlock(client, player, 0f, 1.6f);
            float distWallRight = RaycastHelper.calcDistToWallOrBlock(client, player, 45f, 1.6f);
            float distWallLeft = RaycastHelper.calcDistToWallOrBlock(client, player, -45f, 1.6f);

            // [0,1]: dist in [0,1], blockHeight in [0,1]
            float[] bStraight = RaycastHelper.calcDistToBlock(client, player, 0f);
            float[] bLeft = RaycastHelper.calcDistToBlock(client, player, -45f);
            float[] bRight = RaycastHelper.calcDistToBlock(client, player, 45f);

            // [0,1]
            float vertClear = RaycastHelper.calcVerticalClearance(client, player);

            // [0,1]
            float mana = maxMana > 0 ? (float) currentMana / maxMana : 0f;
            // [0,1]: AOTEV mana cost 50 normalized by maxMana
            float manaCostAotev = maxMana > 0 ? clamp01(50.0f / maxMana) : 0.05f;
            // [0,1]: slot 1-9 -> 1/9..1, 0=not found
            float aotevSlot = getAotevSlot(player);

            double velHz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            // [0,1]
            float timeToCollision = velHz > 0.01
                ? clamp01((float) ((distWall * 10.0) / velHz / 20.0))
                : 1.0f;

            currentMode = detectMode(client, player);
            // [0,1]: mode 0,1,2 -> 0, 0.5, 1.0
            float currentModeNorm = currentMode / 2.0f;
            // [0,1]: target 1,2 -> 0.5, 1.0
            float targetModeNorm = targetMode / 2.0f;

            // === GROUND PROBES (terrain ahead) ===
            float groundNear = RaycastHelper.calcGroundProbe(client, player, 1.5f);
            float groundMid  = RaycastHelper.calcGroundProbe(client, player, 3.0f);
            float groundFar  = RaycastHelper.calcGroundProbe(client, player, 4.5f);

            // === ENTITY BLOCK ===
            List<LivingEntity> mobs = getNearestMobs(client, player);
            float[][] eData = new float[5][9];
            for (int i = 0; i < 5; i++) {
                if (i < mobs.size()) {
                    LivingEntity mob = mobs.get(i);
                    Vec3d rel = mob.getEntityPos().subtract(pos);
                    // [0,1]: rel in [-50,50] -> 0-1 via (rel/50+1)/2
                    eData[i][0] = clamp01((float) ((rel.x / 50.0 + 1) / 2.0));
                    // [0,1]: rel.y in [-20,20]
                    eData[i][1] = clamp01((float) ((rel.y / 20.0 + 1) / 2.0));
                    eData[i][2] = clamp01((float) ((rel.z / 50.0 + 1) / 2.0));
                    // [0,1]: type 0=Zealot, 1=Bruiser, 2=Special -> /2
                    eData[i][3] = getEntityType(mob) / 2.0f;
                    // {0,1}
                    eData[i][4] = isVisible(client, player, mob) ? 1f : 0f;
                    // [0,1]: angle/PI
                    eData[i][5] = getYawDiff(player, mob);
                    float[] comp = getCompetitorData(client, player, mob);
                    // [0,1]: count/10
                    eData[i][6] = comp[0];
                    // [0,1]: dist/50
                    eData[i][7] = comp[1];
                    // [0,1]: (dot+1)/2
                    eData[i][8] = comp[2];
                } else {
                    // sentinel: no entity present
                    eData[i] = new float[]{0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 1f, 0.5f};
                }
            }

            // === ANOMALIES (mixin gates + magnitude math) ===
            // pos_anomaly: mixin разрешает, math считает силу телепорта
            float posAnomaly = 0f;
            if (serverPosEvent && prevPos != null) {
                if (aoteGraceTicks <= 0) {
                    double actualMove = pos.distanceTo(prevPos);
                    double expectedMove = vel.length();
                    posAnomaly = clamp01((float) (Math.abs(actualMove - expectedMove) / 25.0));
                }
                serverPosEvent = false;
            } else if (serverPosEvent) {
                serverPosEvent = false;
            }

            // vel_anomaly: mixin разрешает, math считает силу толчка
            float velAnomaly = 0f;
            if (serverVelEvent) {
                float curVelMag = (float) vel.length();
                float prevVelMag = (float) prevVelocity.length();
                velAnomaly = clamp01(Math.abs(curVelMag - prevVelMag) / 2.0f);
                serverVelEvent = false;
            }

            // rot_anomaly: каждый тик (сервер не корректирует ротацию на Hypixel)
            float yawDiffAnomaly = Math.abs(normalizeAngle(yawRaw - prevYaw)) / 180f;
            float pitchDiffAnomaly = Math.abs(pitchRaw - prevPitch) / 90f;
            float rotAnomaly = clamp01((yawDiffAnomaly + pitchDiffAnomaly) / 2f);

            // stress: затухающий — резко вверх, плавно вниз за ~1 сек (20 тиков)
            float tickStress = clamp01(rotAnomaly * 0.35f + posAnomaly * 0.35f + velAnomaly * 0.3f);
            if (tickStress > currentStress) {
                currentStress = tickStress;
            } else {
                currentStress = Math.max(0f, currentStress - 0.05f);
            }

            prevVelocity = vel;
            prevPos = pos;
            prevYaw = yawRaw;
            prevPitch = pitchRaw;

            // === WRITE CSV ===
            StringBuilder sb = new StringBuilder(512);
            sb.append(String.format(Locale.ROOT,
                "%.4f,%d,%d,%d,%.4f,%.4f," +
                "%d,%d,%d,%d,%.4f,%d,%d," +
                "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f," +
                "%.4f,%.4f,%.4f,",
                activeSlot, on_ground, is_sprinting, is_jumping, yaw, pitch,
                key_W, key_S, key_A, key_D, pressedSlot, leftClick, rightClick,
                deltaYaw, deltaPitch, velX, velY, velZ, accelX, accelY, accelZ,
                distWall, distWallRight, distWallLeft,
                bStraight[0], bStraight[1], bLeft[0], bLeft[1], bRight[0], bRight[1],
                vertClear, mana, manaCostAotev, aotevSlot,
                timeToCollision, currentModeNorm, targetModeNorm,
                groundNear, groundMid, groundFar
            ));

            for (int i = 0; i < 5; i++) {
                float[] e = eData[i];
                sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,",
                    e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7], e[8]));
            }

            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,",
                rotAnomaly, posAnomaly, velAnomaly, currentStress));

            // === PATHFINDER ===
            Pathfinder.update(client, player, targetMode, currentMode);
            float[] pathRel = Pathfinder.getPathRelative(pos);
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f",
                pathRel[0], pathRel[1], pathRel[2]));
            sb.append(System.lineSeparator());

            csvWriter.print(sb);

            if (++tickCounter >= FLUSH_EVERY) {
                csvWriter.flush();
                tickCounter = 0;
            }
        });
    }

    private static List<LivingEntity> getNearestMobs(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) return Collections.emptyList();
        Box searchBox = player.getBoundingBox().expand(50);
        List<LivingEntity> mobs = new ArrayList<>(client.world.getEntitiesByClass(
            LivingEntity.class, searchBox,
            e -> {
                if (e == player) return false;
                String name = getMobName(e);
                if (name.isEmpty()) return false;
                if (targetMode == 1) return name.contains("Zealot") && !name.contains("Bruiser");
                if (targetMode == 2) return name.contains("Bruiser");
                return name.contains("Zealot") || name.contains("Bruiser");
            }
        ));
        Vec3d pPos = player.getEntityPos();
        mobs.sort(Comparator.comparingDouble(e -> e.getEntityPos().distanceTo(pPos)));
        if (mobs.size() > 5) return mobs.subList(0, 5);
        return mobs;
    }

    /** Get mob name from custom name or display name, stripping § color codes. */
    private static String getMobName(LivingEntity e) {
        if (e.hasCustomName() && e.getCustomName() != null) {
            return e.getCustomName().getString().replaceAll("§.", "");
        }
        return e.getName().getString().replaceAll("§.", "");
    }

    private static int getEntityType(LivingEntity e) {
        String name = getMobName(e);
        if (name.contains("Bruiser")) return 1;
        if (name.contains("Special")) return 2;
        return 0;
    }

    private static boolean isVisible(MinecraftClient client, ClientPlayerEntity player, LivingEntity mob) {
        if (client.world == null) return false;
        Vec3d start = player.getEyePos();
        Vec3d end = mob.getEntityPos().add(0, mob.getHeight() / 2.0, 0);
        RaycastContext ctx = new RaycastContext(
            start, end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        );
        BlockHitResult hit = client.world.raycast(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }

    private static float getYawDiff(ClientPlayerEntity player, LivingEntity mob) {
        Vec3d toMob = mob.getEntityPos().subtract(player.getEntityPos());
        double len = Math.sqrt(toMob.x * toMob.x + toMob.z * toMob.z);
        if (len < 0.001) return 0.5f;
        Vec3d look = Vec3d.fromPolar(0, player.getYaw());
        double dot = (look.x * toMob.x + look.z * toMob.z) / len;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        // [0,1]: 0 = смотрим прямо на моба, 1 = в противоположную сторону
        return clamp01((float) (angle / Math.PI));
    }

    private static float[] getCompetitorData(MinecraftClient client, ClientPlayerEntity player, LivingEntity mob) {
        if (client.world == null) return new float[]{0f, 1f, 0.5f};
        Vec3d mobPos = mob.getEntityPos();
        double myDist = player.getEntityPos().distanceTo(mobPos);
        int compCount = 0;
        double compMinDist = Double.MAX_VALUE;
        float compIntent = 0.5f;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player) continue;
            double dist = other.getEntityPos().distanceTo(mobPos);
            if (dist < myDist) {
                compCount++;
                if (dist < compMinDist) {
                    compMinDist = dist;
                    Vec3d otherVel = other.getVelocity();
                    Vec3d toMob = mobPos.subtract(other.getEntityPos()).normalize();
                    double intent = otherVel.dotProduct(toMob);
                    // [0,1]: dot in [-1,1] -> (dot+1)/2
                    compIntent = clamp01((float) ((intent + 1) / 2.0));
                }
            }
        }
        return new float[]{
            clamp01(compCount / 10f),
            compMinDist == Double.MAX_VALUE ? 1f : clamp01((float) (compMinDist / 50f)),
            compIntent
        };
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private static int detectMode(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || targetMode == 0) return 0;
        Box searchBox = player.getBoundingBox().expand(20);
        List<LivingEntity> nearby = client.world.getEntitiesByClass(
            LivingEntity.class, searchBox,
            e -> {
                if (e == player) return false;
                String name = getMobName(e);
                if (name.isEmpty()) return false;
                if (targetMode == 1) return name.contains("Zealot") && !name.contains("Bruiser");
                if (targetMode == 2) return name.contains("Bruiser");
                return false;
            }
        );
        return nearby.isEmpty() ? 0 : targetMode;
    }

    public static void startCollecting(int mode) {
        targetMode = mode;
        currentMode = 0;
        tickCounter = 0;
        Pathfinder.reset();

        try { Files.createDirectories(Paths.get("datasets")); }
        catch (IOException ignored) {}

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String modeName = mode == 1 ? "zealots" : "bruisers";
        String filename = "datasets/" + modeName + "_" + ts + ".csv";

        try {
            csvWriter = new PrintWriter(new BufferedWriter(new FileWriter(filename)));
            csvWriter.println(CSV_HEADER);
            isRecording = true;
        } catch (IOException e) {
            System.err.println("[Topography] Не удалось открыть файл: " + e.getMessage());
            csvWriter = null;
            isRecording = false;
        }
    }

    public static void stopCollecting() {
        isRecording = false;
        Pathfinder.stop();
        if (csvWriter != null) {
            csvWriter.flush();
            csvWriter.close();
            csvWriter = null;
        }
    }

    private static float getPressedSlotKey(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            if (client.options.hotbarKeys[i].isPressed()) return (i + 1) / 9f;
        }
        return 0f;
    }

    private static float getAotevSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getStack(i);
            if (item.isEmpty()) continue;
            String name = item.getName().getString().replaceAll("§.", "");
            if (name.contains("Aspect of the End") || name.contains("Aspect of the Void")) {
                return (i + 1) / 9f;
            }
        }
        return 0f;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
