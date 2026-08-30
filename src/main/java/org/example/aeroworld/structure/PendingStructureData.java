package org.example.aeroworld.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.example.aeroworld.AeroWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * SavedData — хранит очередь структур, ожидающих спавна.
 *
 * <p>Каждая запись содержит:
 * <ul>
 *   <li>{@code pos}           — XZ-центр острова (Y=0, реальный Y ищется в момент спавна)</li>
 *   <li>{@code id}            — ResourceLocation структуры в PhysicalStructures</li>
 *   <li>{@code attempts}      — счётчик попыток</li>
 *   <li>{@code nextRetryTick} — game-tick, начиная с которого разрешена следующая попытка</li>
 * </ul>
 *
 * <p>Backoff экспоненциальный: 60 → 120 → 240 → 480 → 960 → 1200 тиков (= 1 минута max).
 * После {@link Entry#MAX_ATTEMPTS} попыток запись удаляется с ERROR-логом.
 */
public class PendingStructureData extends SavedData {

    public static final String DATA_NAME = AeroWorld.MOD_ID + "_pending_structures";

    // ── Entry ─────────────────────────────────────────────────────────────────

    public record Entry(BlockPos pos, ResourceLocation id, int attempts, long nextRetryTick) {

        /** Максимум попыток до капитуляции. */
        public static final int MAX_ATTEMPTS     = 10;
        /** Базовая задержка (тики). Удваивается при каждой неудаче. */
        public static final int BASE_DELAY_TICKS = 20;
        /** Потолок задержки — 10 секунд. */
        public static final int MAX_DELAY_TICKS  = 200;

        /** Создать новую запись (попытка 0, готова немедленно). */
        public Entry(BlockPos pos, ResourceLocation id) {
            this(pos, id, 0, 0L);
        }

        /**
         * Вернуть обновлённую копию с увеличенным счётчиком и следующим допустимым тиком.
         * @param currentTick значение {@code level.getGameTime()} в момент неудачи.
         */
        public Entry withNextRetry(long currentTick) {
            int next  = attempts + 1;
            int delay = Math.min(BASE_DELAY_TICKS << next, MAX_DELAY_TICKS);
            return new Entry(pos, id, next, currentTick + delay);
        }

        /** Готова ли запись к следующей попытке? */
        public boolean isReadyToRetry(long currentTick) {
            return currentTick >= nextRetryTick;
        }

        /** Исчерпан ли лимит попыток? */
        public boolean isExhausted() {
            return attempts >= MAX_ATTEMPTS;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final List<Entry> pending = new ArrayList<>();

    private PendingStructureData() {}

    // ── Factory ───────────────────────────────────────────────────────────────

    private static PendingStructureData load(CompoundTag tag, HolderLookup.Provider provider) {
        PendingStructureData data = new PendingStructureData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            // Y хранится как 0 — реальный Y вычисляется в ProximityTriggerHandler
            BlockPos pos = new BlockPos(e.getInt("x"), 0, e.getInt("z"));
            ResourceLocation id = ResourceLocation.tryParse(e.getString("id"));
            if (id == null) continue;
            int  attempts      = e.contains("attempts")  ? e.getInt("attempts")   : 0;
            long nextRetryTick = e.contains("nextRetry") ? e.getLong("nextRetry") : 0L;
            data.pending.add(new Entry(pos, id, attempts, nextRetryTick));
        }
        return data;
    }

    public static PendingStructureData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(PendingStructureData::new, PendingStructureData::load),
                DATA_NAME);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry e : pending) {
            CompoundTag c = new CompoundTag();
            c.putInt("x",           e.pos().getX());
            // Y не сохраняем — всегда 0, реальный ищем в момент спавна
            c.putInt("z",           e.pos().getZ());
            c.putString("id",       e.id().toString());
            c.putInt("attempts",    e.attempts());
            c.putLong("nextRetry",  e.nextRetryTick());
            list.add(c);
        }
        tag.put("entries", list);
        return tag;
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Добавить запись с дедупликацией по XZ + id.
     * Y намеренно не сравнивается: разные worldgen-потоки могут дать чуть разный surfaceY,
     * но это один и тот же остров.
     */
    public void addEntry(BlockPos pos, ResourceLocation id) {
        for (Entry e : pending) {
            if (e.id().equals(id)
                    && e.pos().getX() == pos.getX()
                    && e.pos().getZ() == pos.getZ()) {
                return;
            }
        }
        pending.add(new Entry(pos, id));
        setDirty();
    }

    /**
     * Заменить запись обновлённой версией (например с новым счётчиком и nextRetryTick).
     * Если запись не найдена — добавляет как новую.
     */
    public void replaceEntry(Entry old, Entry updated) {
        int idx = pending.indexOf(old);
        if (idx >= 0) {
            pending.set(idx, updated);
        } else {
            pending.add(updated);
        }
        setDirty();
    }

    public List<Entry> getAll() {
        return pending;
    }

    public void remove(Entry entry) {
        pending.remove(entry);
        setDirty();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }
}