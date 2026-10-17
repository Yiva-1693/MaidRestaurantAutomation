package com.icewolf.maidrestaurant.automation.core;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存优先取用（开发文档 4.3⑤ / 5.2③）。
 *
 * <p>调度显示器为菜品发布烹饪委托前，先检测半径内已绑定容器中是否存在现成物品：
 * <ul>
 *   <li>订单菜品是成品 → 直接取对应数量放入操作台，跳过烹饪；</li>
 *   <li>订单菜品是原料级物品 → 同样直接取用放入操作台；</li>
 *   <li>数量不足 → 部分取用 + 剩余走烹饪，不因部分库存卡住整单。</li>
 * </ul>
 *
 * <p>只检测操作台 24 格内的容器（带缓存，不做全局遍历）；取用与烹饪委托在同一个调度评估周期内完成。</p>
 */
public final class StockPriority {

    private static final int SEARCH_RADIUS = 24;
    private static final int SEARCH_VERTICAL = 8;
    private static final long CACHE_INTERVAL = 100L;

    private static final Map<Long, List<BlockPos>> CONTAINER_CACHE = new HashMap<>();
    private static final Map<Long, Long> CONTAINER_CACHE_TICK = new HashMap<>();

    private StockPriority() {
    }

    public static void clearRuntimeState() {
        CONTAINER_CACHE.clear();
        CONTAINER_CACHE_TICK.clear();
    }

    /**
     * 把附近容器里的现成物品搬到操作台，并把 {@code remaining} 中对应需求扣减。
     *
     * @param counterHandler 操作台的物品处理器（订单槽为 0，成品放 1..N）
     * @return 实际搬运的数量（按 itemId 汇总）
     */
    public static Map<String, Integer> moveReadyItemsToCounter(ServerLevel level, BlockPos counterPos,
                                                              LinkedHashMap<String, Integer> remaining,
                                                              IItemHandler counterHandler) {
        Map<String, Integer> moved = new HashMap<>();
        if (level == null || counterPos == null || remaining == null || counterHandler == null) {
            return moved;
        }
        List<BlockPos> containers = findContainers(level, counterPos);
        if (containers.isEmpty()) return moved;

        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            int need = entry.getValue() == null ? 0 : entry.getValue();
            if (need <= 0) continue;
            String itemId = entry.getKey();
            int movedForItem = 0;
            for (BlockPos containerPos : containers) {
                if (movedForItem >= need) break;
                if (containerPos.equals(counterPos)) continue;
                BlockEntity be = level.getBlockEntity(containerPos);
                if (be == null) continue;
                IItemHandler handler = getHandler(be);
                if (handler == null) continue;
                for (int slot = 0; slot < handler.getSlots() && movedForItem < need; slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (stack.isEmpty()) continue;
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (id == null || !itemId.equals(id.toString())) continue;
                    int take = Math.min(need - movedForItem, stack.getCount());
                    ItemStack extracted = handler.extractItem(slot, take, false);
                    if (extracted.isEmpty()) continue;
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(counterHandler, extracted, false);
                    int inserted = extracted.getCount() - remainder.getCount();
                    if (inserted > 0) {
                        movedForItem += inserted;
                    }
                    if (!remainder.isEmpty()) {
                        // 操作台放不下：把剩余塞回原容器，避免物品凭空消失
                        ItemHandlerHelper.insertItemStacked(handler, remainder, false);
                    }
                }
            }
            if (movedForItem > 0) {
                entry.setValue(Math.max(0, need - movedForItem));
                moved.put(itemId, movedForItem);
            }
        }
        // 清理已满足的条目（保持 remaining 语义：>0 才需要继续烹饪）
        Iterator<Map.Entry<String, Integer>> it = remaining.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= 0) it.remove();
        }
        return moved;
    }

    private static IItemHandler getHandler(BlockEntity be) {
        try {
            var cap = be.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent()) {
                return cap.orElse(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<BlockPos> findContainers(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        long now = level.getGameTime();
        Long last = CONTAINER_CACHE_TICK.get(key);
        if (last != null && now - last < CACHE_INTERVAL) {
            List<BlockPos> cached = CONTAINER_CACHE.get(key);
            if (cached != null) return cached;
        }
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -SEARCH_VERTICAL, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, SEARCH_VERTICAL, SEARCH_RADIUS))) {
            if (!level.isLoaded(pos)) continue;
            if (pos.distSqr(center) > (double) SEARCH_RADIUS * SEARCH_RADIUS) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) continue;
            try {
                if (be.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) {
                    found.add(pos.immutable());
                }
            } catch (Throwable ignored) {
            }
        }
        CONTAINER_CACHE.put(key, found);
        CONTAINER_CACHE_TICK.put(key, now);
        return found;
    }
}
