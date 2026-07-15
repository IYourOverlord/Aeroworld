package org.example.aeroworld.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.TickContainerAccess;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Враппер передаёт ванильные высоты (-64, 384) noise-сэмплеру,
 * но все секции и setBlockState делегирует напрямую в real-чанк.
 *
 * Ключевое изменение: super() вызывается с теми же секциями что у real
 * (через null — ChunkAccess создаёт секции под VANILLA_HEIGHT),
 * но setBlockState пишет ТОЛЬКО в real, минуя super полностью.
 * Noise-движок вызывает getSection() — мы возвращаем секции real.
 */
public final class VanillaHeightChunkWrapper extends ChunkAccess {

    private final ChunkAccess real;

    private static final LevelHeightAccessor VANILLA_HEIGHT = new LevelHeightAccessor() {
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight()         { return 384; }
    };

    public VanillaHeightChunkWrapper(ChunkAccess real, Registry<Biome> biomeRegistry) {
        super(real.getPos(),
                real.getUpgradeData(),
                VANILLA_HEIGHT,
                biomeRegistry,
                real.getInhabitedTime(),
                null,
                real.getBlendingData());
        this.real = real;
    }

    @Override
    public LevelHeightAccessor getHeightAccessorForGeneration() {
        return VANILLA_HEIGHT;
    }

    // Возвращаем секции real — noise-движок читает/пишет в них напрямую
    @Override
    public LevelChunkSection[] getSections() {
        return real.getSections();
    }

    @Override
    public LevelChunkSection getSection(int index) {
        return real.getSection(index);
    }

    @Override
    public int getMinBuildHeight() { return -64; }

    @Override
    public int getHeight() { return 384; }

    // getSectionIndex должен работать относительно VANILLA_HEIGHT (-64)
    // ChunkAccess реализует это через (y - getMinBuildHeight()) >> 4
    // Но getMinBuildHeight() у нас -64 — совпадает с real, всё корректно.

    @Override
    @Nullable
    public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved) {
        // Пишем ТОЛЬКО в real — его секции мы и возвращаем через getSection()
        return real.setBlockState(pos, state, moved);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) { return real.getBlockState(pos); }

    @Override
    public FluidState getFluidState(BlockPos pos) { return real.getFluidState(pos); }

    @Override
    public void setBlockEntity(BlockEntity be) { real.setBlockEntity(be); }

    @Override
    public void addEntity(Entity entity) { real.addEntity(entity); }

    @Override @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) { return real.getBlockEntity(pos); }

    @Override
    public Set<BlockPos> getBlockEntitiesPos() { return real.getBlockEntitiesPos(); }

    @Override @Nullable
    public CompoundTag getBlockEntityNbt(BlockPos pos) { return real.getBlockEntityNbt(pos); }

    @Override @Nullable
    public CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider provider) {
        return real.getBlockEntityNbtForSaving(pos, provider);
    }

    @Override
    public void removeBlockEntity(BlockPos pos) { real.removeBlockEntity(pos); }

    @Override
    public ChunkStatus getPersistedStatus() { return real.getPersistedStatus(); }

    @Override
    public TickContainerAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return real.getBlockTicks();
    }

    @Override
    public TickContainerAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return real.getFluidTicks();
    }

    @Override
    public TicksToSave getTicksForSerialization() { return real.getTicksForSerialization(); }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) { return real.getNoiseBiome(x, y, z); }

    @Override public ChunkPos getPos()                             { return real.getPos(); }
    @Override public UpgradeData getUpgradeData()                  { return real.getUpgradeData(); }
    @Override @Nullable public BlendingData getBlendingData()      { return real.getBlendingData(); }
    @Override public long getInhabitedTime()                       { return real.getInhabitedTime(); }
    @Override public void incrementInhabitedTime(long time)        { real.incrementInhabitedTime(time); }
    @Override public void setInhabitedTime(long time)              { real.setInhabitedTime(time); }
    @Override public boolean isUnsaved()                           { return real.isUnsaved(); }
    @Override public void setUnsaved(boolean unsaved)              { real.setUnsaved(unsaved); }
    @Override public void setLightCorrect(boolean lc)              { real.setLightCorrect(lc); }
    @Override public boolean isOldNoiseGeneration()                { return real.isOldNoiseGeneration(); }
    @Override public void markPosForPostprocessing(BlockPos pos)   { real.markPosForPostprocessing(pos); }
    @Override public void addPackedPostProcess(short p, int index) { real.addPackedPostProcess(p, index); }
    @Override public Heightmap getOrCreateHeightmapUnprimed(Heightmap.Types t) {
        return real.getOrCreateHeightmapUnprimed(t);
    }
}