package org.example.aeroworld.mixin.dh;

import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import org.example.aeroworld.worldgen.dh.AeroThroughputLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Настраивает SQLite прагмы для БД DH после инициализации репозитория:
 * - cache_size = -262144 (256 МБ page cache)
 * - mmap_size  = 268435456 (256 МБ memory-mapped I/O)
 * - temp_store = MEMORY
 * - synchronous = NORMAL (или значение из AeroThroughputLimits.SQLITE_SYNC)
 *
 * Ускоряет запись/чтение LOD-данных при интенсивной аналитической генерации.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo", remap = false)
public abstract class SqliteTuningMixin {

    @Unique
    private static final Logger AERO_LOGGER = LoggerFactory.getLogger("AeroWorld-SqliteTuning");

    @Unique
    private static final String CACHE_SIZE_KIB = "-262144";

    @Unique
    private static final String MMAP_BYTES = "268435456";

    @Inject(method = "<init>(Ljava/lang/String;Ljava/io/File;Ljava/lang/Class;)V", at = @At("TAIL"))
    private void aeroworld$tuneSqlite(String databaseType, File databaseFile, Class<?> dtoClass, CallbackInfo ci) {
        try {
            Connection conn = ((AbstractDhRepo<?, ?>) (Object) this).getConnection();
            if (conn == null) return;

            try (Statement stmt = conn.createStatement()) {
                String oldSync = aeroworld$readSynchronous(stmt);

                stmt.execute("PRAGMA cache_size = " + CACHE_SIZE_KIB);
                stmt.execute("PRAGMA mmap_size = " + MMAP_BYTES);
                stmt.execute("PRAGMA temp_store = MEMORY");

                String syncTarget = "FULL".equals(AeroThroughputLimits.SQLITE_SYNC) ? "FULL" : "NORMAL";
                stmt.execute("PRAGMA synchronous = " + syncTarget);

                String parentName = databaseFile.getParentFile() != null
                        ? databaseFile.getParentFile().getName()
                        : databaseFile.getName();

                AERO_LOGGER.info("[AeroWorld] [{}] SQLite synchronous {} -> {} (0=OFF 1=NORMAL 2=FULL), page cache 256MB",
                        parentName, oldSync, aeroworld$readSynchronous(stmt));
            }
        } catch (Throwable ignored) {
            // Не ронять сервер при изменениях DH API — требование §3.9
        }
    }

    @Unique
    private static String aeroworld$readSynchronous(Statement stmt) {
        try (ResultSet rs = stmt.executeQuery("PRAGMA synchronous")) {
            return rs.next() ? rs.getString(1) : "?";
        } catch (Throwable e) {
            return "?";
        }
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void aeroworld$invalidateCacheOnSave(com.seibel.distanthorizons.core.sql.dto.IBaseDTO<?> dto, CallbackInfo ci) {
        if (dto != null && ((Object) this) instanceof com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo) {
            Object key = dto.getKey();
            if (key instanceof Long pos) {
                org.example.aeroworld.worldgen.dh.AeroAdjacencyCache.invalidate(pos);
            }
        }
    }

    @Inject(method = "deleteWithKey", at = @At("HEAD"))
    private void aeroworld$invalidateCacheOnDelete(Object key, CallbackInfo ci) {
        if (((Object) this) instanceof com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo && key instanceof Long pos) {
            org.example.aeroworld.worldgen.dh.AeroAdjacencyCache.invalidate(pos);
        }
    }

    @Inject(method = "deleteAll", at = @At("HEAD"))
    private void aeroworld$clearCacheOnDeleteAll(CallbackInfo ci) {
        if (((Object) this) instanceof com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo) {
            org.example.aeroworld.worldgen.dh.AeroAdjacencyCache.clear();
        }
    }
}
