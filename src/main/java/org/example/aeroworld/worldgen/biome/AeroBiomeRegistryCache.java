package org.example.aeroworld.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.util.Optional;

/**
 * Кеш полного динамического реестра биомов (включая наши клоны aeroworld:*).
 *
 * ЗАЧЕМ ЭТО НУЖНО:
 * {@code AeroBiomeSource.delegate} — это {@code MultiNoiseBiomeSource}, построенный
 * из пресета "minecraft:overworld". Его {@code possibleBiomes()} содержит ТОЛЬКО
 * ванильные биомы этого пресета — наши клонированные {@code aeroworld:plains},
 * {@code aeroworld:forest} и т.д. там не появляются, поскольку они не участвуют
 * в ванильном multi-noise пресете.
 *
 * Поэтому чтобы {@code AeroBiomeSource.findBiome} мог находить наши клоны (у которых
 * NeoForge biome modifier {@code remove_ores.json} уже вырезал все руды из
 * generation settings ещё на этапе загрузки датапака/реестра — то есть "у корня",
 * до какой-либо генерации чанков), нам нужен прямой доступ к полному
 * {@code Registry<Biome>}, а не только к подмножеству из пресета.
 *
 * Регистрируется в {@code AeroWorld} через {@code NeoForge.EVENT_BUS.addListener(...)}.
 * К моменту генерации любого чанка сервер уже полностью стартовал, так что кеш
 * гарантированно заполнен.
 */
public final class AeroBiomeRegistryCache {

    private static volatile Registry<Biome> registry;

    private AeroBiomeRegistryCache() {}

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        registry = event.getServer().registryAccess().registryOrThrow(Registries.BIOME);
    }

    /** Ищет биом по id в полном реестре. Пусто, если реестр ещё не заполнен или id не найден. */
    public static Optional<Holder<Biome>> get(ResourceLocation id) {
        Registry<Biome> reg = registry;
        if (reg == null) return Optional.empty();
        return reg.getHolder(ResourceKey.create(Registries.BIOME, id)).map(h -> (Holder<Biome>) h);
    }
}
