package org.example.aeroworld;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.exampl.physical_structures.api.PhysicalStructureDefinition;
import org.exampl.physical_structures.api.PhysicalStructures;
import org.example.aeroworld.config.AeroWorldConfig;
import org.example.aeroworld.command.AeroWorldCommands;
import org.example.aeroworld.event.AeroStructureListener;
import org.example.aeroworld.event.ProximityTriggerHandler;
import org.example.aeroworld.registry.AeroRegistries;
import org.example.aeroworld.structure.IslandStructureScheduler;
import org.example.aeroworld.structure.StructureSizeCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;

/**
 * AeroWorld — Multi-layer world generation mod.
 *
 * Layer structure:
 *  Layer 1 (Y -64 →   50) : Surface world, vanilla-like terrain with ravines
 *  Layer 2 (Y 300 →  400) : Lower sky islands  ← танки, орудия
 *  Layer 3 (Y 1000→ 1100) : High sky islands   (в разработке)
 *  Layer 4 (Y 2000→ 2100) : Upper sky islands  (в разработке)
 */
@Mod(AeroWorld.MOD_ID)
public class AeroWorld {

    public static final String MOD_ID = "aeroworld";
    public static final Logger LOGGER  = LogUtils.getLogger();

    /**
     * Планировщик структур Layer 2. Singleton — живёт на всё время работы мода.
     * Seed прокидывается позже через {@code AeroWorldChunkGenerator.initializeWithSeed()}.
     * Static — chunk generator (создаётся через codec) получает ссылку без инъекции.
     */
    public static IslandStructureScheduler structureScheduler;

    public AeroWorld(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[AeroWorld] Initializing mod...");

        AeroWorldConfig.register(modContainer);
        AeroRegistries.register(modEventBus);

        // Scheduler создаётся сразу; реальный seed будет задан в AeroWorldChunkGenerator
        structureScheduler = new IslandStructureScheduler();

        // Регистрация структур в PhysicalStructures после полной загрузки модов
        modEventBus.addListener(this::onLoadComplete);

        // Инвалидация size-кеша при /reload (смена датапаков)
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);

        // Game-bus слушатели
        NeoForge.EVENT_BUS.register(new ProximityTriggerHandler());
        NeoForge.EVENT_BUS.register(AeroStructureListener.class);

        // /aeroworld forcePlacePending — принудительный спавн всех structures в очереди
        // (нужно вызывать после прегенерации Chunky/C2ME и ДО импорта в Voxy/любой LOD-рендерер)
        NeoForge.EVENT_BUS.addListener(AeroWorldCommands::register);

        LOGGER.info("[AeroWorld] Registration complete. World generation ready.");
    }

    // ── Регистрация структур в PhysicalStructures ─────────────────────────────

    /**
     * Вызывается после того как все моды завершили инициализацию.
     * К этому моменту PhysicalStructures уже зарегистрировал свои JSON-структуры,
     * и мы можем безопасно добавить/переопределить наши.
     */
    private void onLoadComplete(FMLLoadCompleteEvent event) {
        registerAeroStructures();
    }

    /**
     * Регистрирует все физические структуры AeroWorld в PhysicalStructures.
     *
     * <p>Используем {@link PhysicalStructures#registerStructure} с явным
     * {@code assembleDelayTicks=40} — это даёт Sable 2 секунды на инициализацию
     * sub-level после того как блоки поставлены в мир. Без задержки Sable иногда
     * не успевает обработать чанк и assembly завершается с пустым sub-level.</p>
     */
    private static void registerAeroStructures() {
        // tank_11 — бронетанк на островах Layer 2 (обычная vanilla NBT-структура)
        safeRegister(new PhysicalStructureDefinition(
                ResourceLocation.fromNamespaceAndPath("physical_structures", "tank21"),
                ResourceLocation.fromNamespaceAndPath("physical_structures", "structures/tank21.nbt"),
                Rotation.NONE,
                20 // 1 секунда задержки перед Sable-сборкой
        ));

        // HAUL-01 — грузовой модуль на высотных островах Layer 3.
        //
        // ВАЖНО: HAUL-01.excraft — это НЕ ванильная NBT-структура (StructureTemplate),
        // хотя технически тоже является gzip-NBT. Внутри — снимок Sable sub-level'а
        // (теги toolgun_constraints/root_sublevel/sublevels/plot/chunks/...), который
        // умеет разворачивать только сам Toolgun. Поэтому:
        //   - НЕЛЬЗЯ регистрировать через PhysicalStructures.registerStructure*()
        //     или JSON с nbt_location — StructurePlacer.loadTemplate() прочитает файл
        //     как обычный StructureTemplate и молча "соберёт" почти пустую структуру
        //     (на практике — 1 блок), без ошибки, но и без реального результата.
        //   - НУЖНО использовать excraft:-namespace, который physical_structures уже
        //     поддерживает через StructureSourceProviderRegistry (ExcraftCompat →
        //     ExcraftStructureHandler → ToolgunPlacementBridge → команда Toolgun'а
        //     "/aerotoolgun print_blueprint"). Всё размещение делегируется Toolgun'у,
        //     который сам понимает формат .excraft.
        //
        // Layer3StructurePlacer должен ставить в очередь id excraft:HAUL-01 (не
        // aeroworld:haul_01), а файл должен физически лежать в
        // <gamedir>/blueprints/HAUL-01.excraft (ExcraftStructureHandler ищет именно там).
        //
        // Здесь мы только проверяем окружение и даём внятную диагностику в лог —
        // сама регистрация id не нужна, excraft: id вообще не идёт через
        // PhysicalStructureRegistry.
        java.nio.file.Path excraftFile = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("blueprints").resolve("HAUL-01.excraft");

        boolean toolgunLoaded = net.neoforged.fml.ModList.get().isLoaded("create_aeronautics_toolgun");
        if (!toolgunLoaded) {
            LOGGER.error("[AeroWorld] create_aeronautics_toolgun не установлен — " +
                    "excraft:HAUL-01 не сможет разместиться на Layer 3 (нет провайдера excraft:).");
        } else if (!java.nio.file.Files.exists(excraftFile)) {
            LOGGER.error("[AeroWorld] Файл не найден: {}. Положите HAUL-01.excraft именно " +
                    "в эту папку (а не в ресурсы датапака) — так его ищет ExcraftStructureHandler.",
                    excraftFile);
        } else {
            LOGGER.info("[AeroWorld] HAUL-01.excraft найден в {} — excraft:HAUL-01 готов к размещению " +
                    "через Toolgun.", excraftFile);
        }

        LOGGER.info("[AeroWorld] PhysicalStructures registrations complete.");
    }

    /** Регистрирует структуру, логируя ошибки без падения. */
    private static void safeRegister(PhysicalStructureDefinition def) {
        try {
            if (PhysicalStructures.isRegistered(def.id())) {
                // Уже зарегистрирована (например, JSON-файл из датапака) — пропускаем
                LOGGER.info("[AeroWorld] Structure '{}' already registered, skipping.", def.id());
            } else {
                PhysicalStructures.registerStructure(def);
                LOGGER.info("[AeroWorld] Registered structure: {}", def.id());
            }
        } catch (Exception e) {
            LOGGER.error("[AeroWorld] Failed to register structure '{}': {}", def.id(), e.getMessage());
        }
    }

    // ── Reload listener ───────────────────────────────────────────────────────

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        // При /reload датапаков PhysicalStructures перечитывает JSON-файлы.
        // Наши runtime-регистрации защищены от удаления (registerRuntime),
        // но кеш размеров NBT нужно сбросить — файлы могли измениться.
        StructureSizeCache.invalidate();
        LOGGER.info("[AeroWorld] Resource reload detected — StructureSizeCache invalidated.");
    }
}