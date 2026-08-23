package org.example.aeroworld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.event.ProximityTriggerHandler;
import org.example.aeroworld.worldgen.AeroWorldChunkGenerator;
import org.example.aeroworld.worldgen.cache.IslandData;
import org.example.aeroworld.worldgen.layer.HighIslandGenerator;
import org.example.aeroworld.worldgen.layer.LowerIslandGenerator;
import org.example.aeroworld.worldgen.layer.UpperIslandGenerator;
import org.example.aeroworld.worldgen.noise.IslandPlacer;

import java.util.Set;

/**
 * Команда {@code /aeroworld forcePlacePending} — принудительно размещает ВСЕ
 * структуры (tank_11 / haul_01), стоящие в очереди {@code PendingStructureData},
 * не дожидаясь, пока к ним подлетит живой игрок.
 *
 * <h3>Зачем</h3>
 * <p>Обычно структуры реально дописываются в чанк только когда игрок оказывается
 * в радиусе ~96 блоков ({@link ProximityTriggerHandler}). После прегенерации
 * через Chunky/C2ME таких игроков рядом почти ни с одним островом не было —
 * значит, структуры физически отсутствуют в уже сохранённых на диск чанках.
 * Любой инструмент, читающий эти чанки напрямую (например, Voxy World Import),
 * увидит острова БЕЗ структур, а при реальном пролёте игрока сервер допишет
 * блоки в уже импортированный чанк "на лету" — отсюда расхождение между тем,
 * что было заранее отрисовано, и тем, что появляется по факту.</p>
 *
 * <h3>Как использовать</h3>
 * <ol>
 *   <li>Дождаться 100% завершения задачи Chunky/C2ME для измерения AeroWorld.</li>
 *   <li>Выполнить {@code /aeroworld forcePlacePending} (через консоль сервера
 *       или от лица оператора).</li>
 *   <li>Дождаться лога {@code [AeroWorld] forcePlaceAll: завершено. N/M ...}.</li>
 *   <li>Только теперь выполнять {@code /voxy import} — чанки на диске уже будут
 *       содержать все структуры.</li>
 * </ol>
 *
 * <p>Команда синхронно подгружает чанки и может занять заметное время на
 * больших прегенерированных регионах — это ожидаемо, не баг.</p>
 */
public final class AeroWorldCommands {

    private AeroWorldCommands() {}

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aeroworld")
                .requires(src -> src.hasPermission(2)) // только операторы/консоль
                .then(Commands.literal("forcePlacePending")
                        .executes(AeroWorldCommands::runForcePlacePending))
                .then(Commands.literal("findIsland4")
                        .executes(AeroWorldCommands::runFindIsland4))
                .then(Commands.literal("findIsland2")
                        .executes(ctx -> runFindLowerIsland(ctx, 2)))
                .then(Commands.literal("findIsland3")
                        .executes(AeroWorldCommands::runFindHighIsland)));
    }

    private static int runForcePlacePending(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        boolean isAeroWorld = level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false);
        if (!isAeroWorld) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Эту команду нужно выполнять находясь в измерении aeroworld " +
                            "(сейчас: " + level.dimension().location() + "). " +
                            "Используйте /execute in aeroworld:aeroworld run aeroworld forcePlacePending"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "[AeroWorld] Запущено принудительное размещение всех ожидающих структур. " +
                        "Это может занять время — следите за логом сервера."), true);

        ProximityTriggerHandler handler = new ProximityTriggerHandler();
        int placed = handler.forcePlaceAll(level);

        source.sendSuccess(() -> Component.literal(
                "[AeroWorld] Готово: размещено " + placed + " структур(ы). " +
                        "Теперь можно безопасно делать импорт в Voxy."), true);
        return placed;
    }

    /**
     * {@code /aeroworld findIsland4} — спиральный поиск ближайшей ЗАНЯТОЙ
     * ячейки сетки слоя 4 (Upper Sky Islands) от текущей позиции игрока,
     * используя ТОТ ЖЕ САМЫЙ {@link IslandPlacer}, что и реальная генерация
     * (то есть тот же derived seed через RandomState — без риска разойтись
     * с игрой, как было бы при ручном воспроизведении хеш-функции снаружи).
     *
     * Полезно для диагностики: если остров найден и игрок телепортирован
     * прямо к нему, но острова физически нет — проблема в генерации.
     * Если остров есть — значит, слой 4 работает штатно, и дело было
     * просто в редкости (spawn_chance/grid_chunks) при поиске "вслепую".
     */
    private static int runFindIsland4(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        boolean isAeroWorld = level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false);
        if (!isAeroWorld) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Эту команду нужно выполнять находясь в измерении aeroworld " +
                            "(сейчас: " + level.dimension().location() + "). " +
                            "Используйте /execute in aeroworld:aeroworld run aeroworld findIsland4"));
            return 0;
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof AeroWorldChunkGenerator aeroGen)) {
            source.sendFailure(Component.literal("[AeroWorld] Неожиданный тип генератора: " + generator.getClass()));
            return 0;
        }

        UpperIslandGenerator upperIslands = aeroGen.getUpperIslands();
        if (upperIslands == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] upperIslands ещё не инициализирован — сгенерируйте хотя бы один чанк (просто полетайте) и повторите."));
            return 0;
        }

        IslandPlacer placer = upperIslands.getPlacer();
        int gridChunks = placer.gridSizeChunks();

        BlockPos origin = BlockPos.containing(source.getPosition());
        int originCellX = Math.floorDiv(origin.getX() >> 4, gridChunks);
        int originCellZ = Math.floorDiv(origin.getZ() >> 4, gridChunks);

        // Спиральный поиск ближайшей занятой ячейки "кольцами" от центра.
        // MAX_RING=64 → покрывает 64*gridChunks*16 блоков в каждую сторону,
        // с огромным запасом даже для самого редкого пресета (skyblock_classic).
        final int MAX_RING = 64;
        int[] found = null;
        int foundRing = -1;
        search:
        for (int ring = 0; ring <= MAX_RING; ring++) {
            for (int dcx = -ring; dcx <= ring; dcx++) {
                for (int dcz = -ring; dcz <= ring; dcz++) {
                    if (Math.max(Math.abs(dcx), Math.abs(dcz)) != ring) continue; // только периметр кольца
                    int[] c = placer.getCentreForCell(originCellX + dcx, originCellZ + dcz);
                    if (c != null) {
                        found = c;
                        foundRing = ring;
                        break search;
                    }
                }
            }
        }

        if (found == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Остров слоя 4 не найден даже в радиусе " + MAX_RING + " ячеек сетки (~" +
                            (MAX_RING * gridChunks * 16) + " блоков). Это уже похоже на реальную поломку " +
                            "генерации, а не на статистическую редкость — пришлите новый latest.log/debug.log."));
            return 0;
        }

        IslandData data = upperIslands.getIslandData(found[0], found[1]);
        int teleportY = data.topY + 5;

        final int fx = found[0], fz = found[1], fring = foundRing;
        boolean teleported = false;
        if (source.getEntity() instanceof ServerPlayer player) {
            player.teleportTo(level, fx + 0.5, teleportY, fz + 0.5, Set.of(), player.getYRot(), player.getXRot());
            teleported = true;
        }
        final boolean tp = teleported;

        source.sendSuccess(() -> Component.literal(
                "[AeroWorld] Ближайший остров слоя 4: X=" + fx + " Z=" + fz +
                        " (Y " + data.bottomY + "\u2013" + data.topY + "), кольцо сетки #" + fring +
                        " от вас. " + (tp ? "Телепортирую..." : "Выполните с игрока, чтобы телепортироваться.")), true);
        return 1;
    }

    /**
     * {@code /aeroworld findIsland2} — спиральный поиск ближайшего острова
     * Layer 2 ({@link LowerIslandGenerator}), тот же принцип, что и
     * {@link #runFindIsland4}, но по сетке {@code lowerIslands}.
     *
     * <p>⚠ ИСПРАВЛЕНО: ранее {@code findIsland3} по ошибке тоже вызывал этот
     * метод (с {@code layerNumber=3} только в тексте сообщения), из-за чего
     * телепортировал на остров Layer 2, а не Layer 3 — острова этих слоёв
     * физически НЕ на одной сетке: {@link LowerIslandGenerator} и
     * {@link HighIslandGenerator}
     * — два независимых {@code IslandPlacer} с разной солью seed'а
     * ({@code worldSeed ^ 0x2L} у Layer 2 против {@code worldSeed ^ 0x10L}
     * у Layer 3, см. конструкторы обоих классов), поэтому координаты их
     * островов в общем случае не совпадают. Теперь {@code findIsland3}
     * реализован отдельным методом {@link #runFindHighIsland}, использующим
     * {@code aeroGen.getHighIslands()} вместо {@code getLowerIslands()}.</p>
     */
    private static int runFindLowerIsland(CommandContext<CommandSourceStack> ctx, int layerNumber) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        boolean isAeroWorld = level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false);
        if (!isAeroWorld) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Эту команду нужно выполнять находясь в измерении aeroworld " +
                            "(сейчас: " + level.dimension().location() + "). " +
                            "Используйте /execute in aeroworld:aeroworld run aeroworld findIsland" + layerNumber));
            return 0;
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof AeroWorldChunkGenerator aeroGen)) {
            source.sendFailure(Component.literal("[AeroWorld] Неожиданный тип генератора: " + generator.getClass()));
            return 0;
        }

        LowerIslandGenerator lowerIslands = aeroGen.getLowerIslands();
        if (lowerIslands == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] lowerIslands ещё не инициализирован — сгенерируйте хотя бы один чанк (просто полетайте) и повторите."));
            return 0;
        }

        IslandPlacer placer = lowerIslands.getPlacer();
        int gridChunks = placer.gridSizeChunks();

        BlockPos origin = BlockPos.containing(source.getPosition());
        int originCellX = Math.floorDiv(origin.getX() >> 4, gridChunks);
        int originCellZ = Math.floorDiv(origin.getZ() >> 4, gridChunks);

        final int MAX_RING = 64;
        int[] found = null;
        int foundRing = -1;
        search:
        for (int ring = 0; ring <= MAX_RING; ring++) {
            for (int dcx = -ring; dcx <= ring; dcx++) {
                for (int dcz = -ring; dcz <= ring; dcz++) {
                    if (Math.max(Math.abs(dcx), Math.abs(dcz)) != ring) continue;
                    int[] c = placer.getCentreForCell(originCellX + dcx, originCellZ + dcz);
                    if (c != null) {
                        found = c;
                        foundRing = ring;
                        break search;
                    }
                }
            }
        }

        if (found == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Остров слоя " + layerNumber + " не найден даже в радиусе " + MAX_RING + " ячеек сетки (~" +
                            (MAX_RING * gridChunks * 16) + " блоков). Это уже похоже на реальную поломку " +
                            "генерации, а не на статистическую редкость — пришлите новый latest.log/debug.log."));
            return 0;
        }

        IslandData data = lowerIslands.getIslandData(found[0], found[1]);
        int teleportY = data.topY + 5;

        final int fx = found[0], fz = found[1], fring = foundRing;
        boolean teleported = false;
        if (source.getEntity() instanceof ServerPlayer player) {
            player.teleportTo(level, fx + 0.5, teleportY, fz + 0.5, Set.of(), player.getYRot(), player.getXRot());
            teleported = true;
        }
        final boolean tp = teleported;

        source.sendSuccess(() -> Component.literal(
                "[AeroWorld] Ближайший остров слоя " + layerNumber + ": X=" + fx + " Z=" + fz +
                        " (Y " + data.bottomY + "\u2013" + data.topY + "), кольцо сетки #" + fring +
                        " от вас. " + (tp ? "Телепортирую..." : "Выполните с игрока, чтобы телепортироваться.")), true);
        return 1;
    }

    /**
     * {@code /aeroworld findIsland3} — спиральный поиск ближайшего острова
     * Layer 3 ({@link HighIslandGenerator}), структурно идентичен
     * {@link #runFindIsland4} (та же логика спирального обхода колец сетки),
     * но читает {@code aeroGen.getHighIslands()} вместо {@code getUpperIslands()}.
     *
     * <p>Раньше {@code findIsland3} по ошибке использовал
     * {@link #runFindLowerIsland} (сетку Layer 2), из-за чего телепортировал
     * на остров Layer 2 — см. javadoc {@link #runFindLowerIsland} с деталями
     * причины (два независимых {@code IslandPlacer} с разной солью seed'а).</p>
     */
    private static int runFindHighIsland(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        boolean isAeroWorld = level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AeroWorld.MOD_ID))
                .orElse(false);
        if (!isAeroWorld) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Эту команду нужно выполнять находясь в измерении aeroworld " +
                            "(сейчас: " + level.dimension().location() + "). " +
                            "Используйте /execute in aeroworld:aeroworld run aeroworld findIsland3"));
            return 0;
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof AeroWorldChunkGenerator aeroGen)) {
            source.sendFailure(Component.literal("[AeroWorld] Неожиданный тип генератора: " + generator.getClass()));
            return 0;
        }

        HighIslandGenerator highIslands = aeroGen.getHighIslands();
        if (highIslands == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] highIslands ещё не инициализирован — сгенерируйте хотя бы один чанк (просто полетайте) и повторите."));
            return 0;
        }

        IslandPlacer placer = highIslands.getPlacer();
        int gridChunks = placer.gridSizeChunks();

        BlockPos origin = BlockPos.containing(source.getPosition());
        int originCellX = Math.floorDiv(origin.getX() >> 4, gridChunks);
        int originCellZ = Math.floorDiv(origin.getZ() >> 4, gridChunks);

        final int MAX_RING = 64;
        int[] found = null;
        int foundRing = -1;
        search:
        for (int ring = 0; ring <= MAX_RING; ring++) {
            for (int dcx = -ring; dcx <= ring; dcx++) {
                for (int dcz = -ring; dcz <= ring; dcz++) {
                    if (Math.max(Math.abs(dcx), Math.abs(dcz)) != ring) continue;
                    int[] c = placer.getCentreForCell(originCellX + dcx, originCellZ + dcz);
                    if (c != null) {
                        found = c;
                        foundRing = ring;
                        break search;
                    }
                }
            }
        }

        if (found == null) {
            source.sendFailure(Component.literal(
                    "[AeroWorld] Остров слоя 3 не найден даже в радиусе " + MAX_RING + " ячеек сетки (~" +
                            (MAX_RING * gridChunks * 16) + " блоков). Это уже похоже на реальную поломку " +
                            "генерации, а не на статистическую редкость — пришлите новый latest.log/debug.log."));
            return 0;
        }

        IslandData data = highIslands.getIslandData(found[0], found[1]);
        int teleportY = data.topY + 5;

        final int fx = found[0], fz = found[1], fring = foundRing;
        boolean teleported = false;
        if (source.getEntity() instanceof ServerPlayer player) {
            player.teleportTo(level, fx + 0.5, teleportY, fz + 0.5, Set.of(), player.getYRot(), player.getXRot());
            teleported = true;
        }
        final boolean tp = teleported;

        source.sendSuccess(() -> Component.literal(
                "[AeroWorld] Ближайший остров слоя 3: X=" + fx + " Z=" + fz +
                        " (Y " + data.bottomY + "\u2013" + data.topY + "), кольцо сетки #" + fring +
                        " от вас. " + (tp ? "Телепортирую..." : "Выполните с игрока, чтобы телепортироваться.")), true);
        return 1;
    }
}