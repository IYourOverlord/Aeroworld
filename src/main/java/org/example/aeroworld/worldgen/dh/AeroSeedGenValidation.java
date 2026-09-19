package org.example.aeroworld.worldgen.dh;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.column.AeroColumnModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Dev-валидация аналитического генератора DH SeedGen (п. 3.8 ТЗ).
 * Сравнивает вывод {@link AeroColumnModel#buildSpans} с {@link AeroWorldChunkGenerator#getBaseColumn}
 * для N случайных XZ по всем слоям, фиксируя расхождения.
 */
public class AeroSeedGenValidation {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeroSeedGenValidation.class);
    private static final BlockState BS_AIR = Blocks.AIR.defaultBlockState();

    public static int runValidation(CommandContext<CommandSourceStack> ctx, int sampleCount) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        ChunkGenerator chunkGen = level.getChunkSource().getGenerator();
        if (!(chunkGen instanceof AeroWorldChunkGenerator aeroGen)) {
            source.sendFailure(Component.literal("Validation failed: Current dimension generator is not AeroWorldChunkGenerator."));
            return 0;
        }

        if (!aeroGen.isSeedInitialized()) {
            aeroGen.initializeWithSeed(level.getSeed());
        }

        source.sendSuccess(() -> Component.literal(String.format("Starting SeedGen validation for %d columns...", sampleCount)), true);

        int minY = aeroGen.getMinY();
        int height = aeroGen.getGenDepth();
        int maxY = minY + height - 1;

        LevelHeightAccessor heightAccessor = new LevelHeightAccessor() {
            @Override public int getHeight() { return height; }
            @Override public int getMinBuildHeight() { return minY; }
        };

        Random rng = new Random(level.getSeed() ^ 0xFEEDFACECAFEL);
        int mismatches = 0;
        int checked = 0;

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < sampleCount; i++) {
            int x = rng.nextInt(200000) - 100000;
            int z = rng.nextInt(200000) - 100000;

            // 1. getBaseColumn
            var noiseCol = aeroGen.getBaseColumn(x, z, heightAccessor, level.getChunkSource().randomState());

            // 2. buildSpans
            List<AeroColumnModel.Span> spans = AeroColumnModel.buildSpans(
                    x, z, minY, maxY,
                    aeroGen.getLayer1Terrain(),
                    aeroGen.getLowerIslands(),
                    aeroGen.getHighIslands(),
                    aeroGen.getUpperIslands(),
                    aeroGen.getAeroBiomeSource(),
                    false
            );

            BlockState[] modelStates = new BlockState[height];
            Arrays.fill(modelStates, BS_AIR);
            for (AeroColumnModel.Span span : spans) {
                int start = Math.max(0, span.bottomY() - minY);
                int end = Math.min(height - 1, span.topY() - minY);
                for (int yIdx = start; yIdx <= end; yIdx++) {
                    modelStates[yIdx] = span.state();
                }
            }

            // 3. Compare column
            boolean colMismatch = false;
            for (int yIdx = 0; yIdx < height; yIdx++) {
                BlockState expected = noiseCol.getBlock(minY + yIdx);
                BlockState actual = modelStates[yIdx];
                if (!expected.equals(actual)) {
                    colMismatch = true;
                    if (mismatches < 20) {
                        LOGGER.warn("[SeedGenValidation] Mismatch at ({}, {}, {}): expected {} but got {}",
                                x, minY + yIdx, z, expected, actual);
                    }
                    break;
                }
            }

            if (colMismatch) {
                mismatches++;
            }
            checked++;
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        final int finalMismatches = mismatches;
        final int finalChecked = checked;

        LOGGER.info("[SeedGenValidation] Validation completed in {} ms: {} columns checked, {} mismatches found.",
                elapsedMs, finalChecked, finalMismatches);

        source.sendSuccess(() -> Component.literal(String.format(
                "Validation completed in %d ms: %d columns checked, %d mismatches found (%.3f%% mismatch rate).",
                elapsedMs, finalChecked, finalMismatches, (finalMismatches * 100.0 / finalChecked)
        )), true);

        return finalMismatches == 0 ? 1 : 0;
    }
}
