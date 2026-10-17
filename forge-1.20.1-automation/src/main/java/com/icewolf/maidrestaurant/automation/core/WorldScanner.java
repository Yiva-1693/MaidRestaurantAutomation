package com.icewolf.maidrestaurant.automation.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="maid_restaurant_automation")
public class WorldScanner {
    private static final Map<ServerLevel, Set<BlockPos>> trackedPositions = new ConcurrentHashMap<ServerLevel, Set<BlockPos>>();

    /** 服务器启动时清空全部旧世界的方块实体索引，新区块加载时重建（见 {@link RuntimeState}）。 */
    public static void clearAll() {
        trackedPositions.clear();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel)levelAccessor;
        ChunkAccess chunkAccess = event.getChunk();
        if (!(chunkAccess instanceof LevelChunk)) {
            return;
        }
        LevelChunk chunk = (LevelChunk)chunkAccess;
        Set set = trackedPositions.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            set.add(pos.immutable());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel)levelAccessor;
        ChunkAccess chunkAccess = event.getChunk();
        if (!(chunkAccess instanceof LevelChunk)) {
            return;
        }
        LevelChunk chunk = (LevelChunk)chunkAccess;
        Set<BlockPos> set = trackedPositions.get(level);
        if (set == null) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            set.remove(pos);
        }
    }

    /**
     * 玩家（或其它实体）放置方块：若新方块带方块实体，立即纳入索引。
     * 修复原先只在区块加载时快照、运行时新放的操作台/打单机永远扫不到的问题。
     * 仅按"是否带方块实体"粗加入，具体类型由 scan(...) 时的 isInstance 过滤，残留位置无害。
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!event.getPlacedBlock().hasBlockEntity()) {
            return;
        }
        ServerLevel level = (ServerLevel)event.getLevel();
        trackedPositions.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet())
                .add(event.getPos().immutable());
    }

    /**
     * 玩家破坏方块：从索引移除。非玩家破坏（爆炸等）虽不触发本事件，
     * 但 scan(...) 时 getBlockEntity 返回 null 会自动跳过，不会产生错误绑定。
     */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        Set<BlockPos> set = trackedPositions.get((ServerLevel)event.getLevel());
        if (set != null) {
            set.remove(event.getPos().immutable());
        }
    }

    public static List<BlockPos> scan(ServerLevel level, Class<?> ... targetClasses) {
        List<BlockPos> result = new ArrayList<BlockPos>();
        Set<BlockPos> set = trackedPositions.get(level);
        if (set == null || set.isEmpty()) {
            try {
                ServerChunkCache chunkCache = level.getChunkSource();
                Field chunkMapField = chunkCache.getClass().getDeclaredField("chunkMap");
                chunkMapField.setAccessible(true);
                Object chunkMap = chunkMapField.get(chunkCache);
                if (chunkMap != null) {
                    Field visibleChunksField = chunkMap.getClass().getDeclaredField("visibleChunks");
                    visibleChunksField.setAccessible(true);
                    Object visibleChunks = visibleChunksField.get(chunkMap);
                    if (visibleChunks instanceof Iterable) {
                        for (Object chunkHolder : (Iterable)visibleChunks) {
                            try {
                                LevelChunk chunk;
                                ChunkPos chunkPos;
                                Field posField = chunkHolder.getClass().getDeclaredField("pos");
                                posField.setAccessible(true);
                                Object posObj = posField.get(chunkHolder);
                                if (!(posObj instanceof ChunkPos) || !level.isLoaded((chunkPos = (ChunkPos)posObj).getWorldPosition()) || (chunk = level.getChunk(chunkPos.x, chunkPos.z)) == null) continue;
                                block5: for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                                    BlockEntity be = level.getBlockEntity(pos);
                                    if (be == null) continue;
                                    for (Class<?> clazz : targetClasses) {
                                        if (!clazz.isInstance(be)) continue;
                                        result.add(pos.immutable());
                                        continue block5;
                                    }
                                }
                            }
                            catch (Throwable throwable) {
                            }
                        }
                    }
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            if (result.isEmpty()) {
                result = fallbackScanByPlayers(level, targetClasses);
            }
            return result;
        }
        block7: for (BlockPos pos : set) {
            BlockEntity be;
            if (!level.isLoaded(pos) || (be = level.getBlockEntity(pos)) == null) continue;
            for (Class<?> clazz : targetClasses) {
                if (!clazz.isInstance(be)) continue;
                result.add(pos);
                continue block7;
            }
        }
        if (result.isEmpty()) {
            result = fallbackScanByPlayers(level, targetClasses);
        }
        return result;
    }

    /** 兜底扫描节流：level -> 上次全量兜底扫描 tick。 */
    private static final Map<ServerLevel, Long> lastFallbackScan = new ConcurrentHashMap<>();

    /**
     * 兜底扫描：遍历玩家周围已加载区块内的方块实体。
     *
     * <p>修复：区块加载事件触发时，区块里的方块实体可能尚未创建，导致
     * {@link #trackedPositions} 索引为空；一旦服务器重启，之前已放置的打单机/调度显示器等
     * 就再也扫不到（表现为"操作台映射=0、调度显示器=0、女仆不做菜"）。
     * 这里以玩家为中心扫描已加载区块并**回填索引**，之后走快路径。</p>
     */
    private static List<BlockPos> fallbackScanByPlayers(ServerLevel level, Class<?>... targetClasses) {
        List<BlockPos> result = new ArrayList<>();
        long now = level.getGameTime();
        Long last = lastFallbackScan.get(level);
        if (last != null && now - last < 100L) {
            return result;
        }
        lastFallbackScan.put(level, now);
        Set<BlockPos> index = trackedPositions.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        final int chunkRadius = 16;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            ChunkPos center = new ChunkPos(player.blockPosition());
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    LevelChunk chunk;
                    try {
                        chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (chunk == null) continue;
                    for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be == null) continue;
                        for (Class<?> clazz : targetClasses) {
                            if (clazz.isInstance(be)) {
                                result.add(pos.immutable());
                                index.add(pos.immutable());
                                break;
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}