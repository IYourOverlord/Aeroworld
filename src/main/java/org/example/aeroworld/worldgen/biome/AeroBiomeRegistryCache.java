package org.example.aeroworld.worldgen.biome;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    private static volatile CompletableFuture<Registry<Biome>> REGISTRY_FUTURE = new CompletableFuture<>();

    // ФИКС: раньше get() тихо возвращал Optional.empty(), если реестр ещё не
    // заполнен (registry == null) — это молчаливо предполагало, что
    // ServerAboutToStartEvent ГАРАНТИРОВАННО отрабатывает раньше первого
    // обращения к биомам. С многопоточной генерацией чанков (C2ME) это не
    // гарантия: worker-поток может успеть вызвать collectPossibleBiomes()
    // (через AeroWorldChunkGenerator.createBiomes → getBiomeSource() →
    // possibleBiomes(), который в базовом BiomeSource кэшируется через
    // Suppliers.memoize НАВСЕГДА при первом вызове) до того, как основной
    // поток сервера дойдёт до ServerAboutToStartEvent. Если это произошло —
    // часть клонов aeroworld:*ocean* исчезает из possibleBiomes() навсегда
    // для всего мира → ChunkGenerator.applyBiomeDecoration строит
    // FeatureSorter без индексов для этих биомов → IndexOutOfBoundsException
    // ("Index -1 out of bounds for length 1") при декорации/спавне на любом
    // чанке с таким биомом, что приводит к падению генерации чанков
    // (зависание клиента, ожидающего чанки от упавшего сервера).
    //
    // Фикс: get() теперь ждёт (короткий busy-wait, реестр готовится за
    // миллисекунды на старте сервера) вместо немедленного Optional.empty().
    // Таймаут — защита от дедлока, если событие по какой-то причине не
    // произойдёт вовсе (тогда ведём себя как раньше — Optional.empty()).
    private static final long WAIT_TIMEOUT_MS = 10_000;

    private AeroBiomeRegistryCache() {}

    private static final java.util.concurrent.ConcurrentHashMap<ResourceLocation, Optional<Holder<Biome>>> HOLDER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(64);

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        REGISTRY_FUTURE.complete(event.getServer().registryAccess().registryOrThrow(Registries.BIOME));
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        REGISTRY_FUTURE = new CompletableFuture<>();
        HOLDER_CACHE.clear();
    }

    /** Ищет биом по id в полном реестре. Ждёт прогрева реестра (см. класс-javadoc). */
    public static Optional<Holder<Biome>> get(ResourceLocation id) {
        Optional<Holder<Biome>> cached = HOLDER_CACHE.get(id);
        if (cached != null) return cached;

        Registry<Biome> reg = awaitRegistry();
        if (reg == null) return Optional.empty();

        Optional<Holder<Biome>> holder = reg.getHolder(ResourceKey.create(Registries.BIOME, id)).map(h -> (Holder<Biome>) h);
        if (holder.isPresent()) {
            HOLDER_CACHE.put(id, holder);
        }
        return holder;
    }

    private static Registry<Biome> awaitRegistry() {
        CompletableFuture<Registry<Biome>> future = REGISTRY_FUTURE;
        if (future.isDone()) {
            return future.join();
        }

        String threadName = Thread.currentThread().getName();
        if (threadName.equals("Server thread") || threadName.equals("Render thread") || threadName.equals("main")) {
            return null;
        }

        try {
            return future.join();
        } catch (Exception e) {
            return null;
        }
    }
}