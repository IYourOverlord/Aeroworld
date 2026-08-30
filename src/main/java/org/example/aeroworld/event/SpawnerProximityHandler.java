package org.example.aeroworld.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.example.aeroworld.AeroWorld;

import java.util.ArrayList;
import java.util.List;

public final class SpawnerProximityHandler {

    private static final ResourceLocation SPAWNER_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("physical_structures", "structure_spawner");

    private static final double TRIGGER_DISTANCE = 10.0;
    private static final int TICK_INTERVAL = 20;
    private static final int CHUNK_SEARCH_RADIUS = 2;
    private static final int LAYER2_MIN_Y = 250;
    private static final int LAYER2_MAX_Y = 450;

    private Block spawnerBlockCache = null;

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().location().getNamespace().equals(AeroWorld.MOD_ID)) return;
        if (level.getGameTime() % TICK_INTERVAL != 0) return;

        for (ServerPlayer player : level.players()) {
            checkPlayerProximity(level, player);
        }
    }

    private void checkPlayerProximity(ServerLevel level, ServerPlayer player) {
        double playerY = player.getY();
        if (playerY < LAYER2_MIN_Y || playerY > LAYER2_MAX_Y) return;

        Block spawnerBlock = getSpawnerBlock();
        if (spawnerBlock == null) return;

        int playerChunkX = player.chunkPosition().x;
        int playerChunkZ = player.chunkPosition().z;

        // Предвычисляем SQ-порог — убираем sqrt из distanceTo
        final double TRIGGER_DISTANCE_SQ = TRIGGER_DISTANCE * TRIGGER_DISTANCE;
        final double px = player.getX(), py = player.getY(), pz = player.getZ();

        List<BlockPos> toTrigger = new ArrayList<>();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(); // один объект на весь поиск

        for (int dcx = -CHUNK_SEARCH_RADIUS; dcx <= CHUNK_SEARCH_RADIUS; dcx++) {
            for (int dcz = -CHUNK_SEARCH_RADIUS; dcz <= CHUNK_SEARCH_RADIUS; dcz++) {
                int cx = playerChunkX + dcx;
                int cz = playerChunkZ + dcz;
                if (!level.hasChunk(cx, cz)) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int wx = baseX + lx;
                        int wz = baseZ + lz;

                        // XZ-reject до Y-цикла — экономим все 200 Y-итераций,
                        // если игрок слишком далеко по горизонтали
                        double dxz2 = (px - wx - 0.5) * (px - wx - 0.5)
                                    + (pz - wz - 0.5) * (pz - wz - 0.5);
                        if (dxz2 > TRIGGER_DISTANCE_SQ) continue;

                        for (int wy = LAYER2_MIN_Y; wy <= LAYER2_MAX_Y; wy++) {
                            mpos.set(wx, wy, wz);
                            if (level.getBlockState(mpos).getBlock() != spawnerBlock) continue;

                            double dy = py - wy - 0.5;
                            if (dxz2 + dy * dy <= TRIGGER_DISTANCE_SQ) {
                                toTrigger.add(mpos.immutable()); // immutable() только для найденных
                            }
                        }
                    }
                }
            }
        }

        for (BlockPos pos : toTrigger) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != spawnerBlock) continue;
            triggerSpawner(level, pos, player.getName().getString());
        }
    }

    private void triggerSpawner(ServerLevel level, BlockPos pos, String playerName) {
        try {
            Block block = level.getBlockState(pos).getBlock();
            BlockEntity be = level.getBlockEntity(pos);

            String structureId = "unknown";
            if (be != null) {
                net.minecraft.nbt.CompoundTag tag = be.getUpdateTag(level.registryAccess());
                structureId = tag.getString("structure_id");
            }


            java.lang.reflect.Method trigger = block.getClass()
                    .getMethod("trigger", ServerLevel.class, BlockPos.class,
                            net.minecraft.world.entity.player.Player.class);

            boolean success = (boolean) trigger.invoke(block, level, pos, null);

            if (success) {
            } else {
            }

        } catch (NoSuchMethodException e) {
        } catch (Exception e) {
        }
    }

    private Block getSpawnerBlock() {
        if (spawnerBlockCache != null) return spawnerBlockCache;

        Block b = BuiltInRegistries.BLOCK.get(SPAWNER_BLOCK_ID);
        if (b == null || b == Blocks.AIR) {
            return null;
        }

        spawnerBlockCache = b;
        return b;
    }
}