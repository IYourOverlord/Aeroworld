package org.example.aeroworld.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.example.aeroworld.AeroWorld;
import org.example.aeroworld.event.ProximityTriggerHandler;

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
                        .executes(AeroWorldCommands::runForcePlacePending)));
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
}
