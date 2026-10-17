package com.icewolf.maidrestaurant.automation.core;

import cn.breezeth.ordertocook.block.entity.TakeoutBoxBlockEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import com.icewolf.maidrestaurant.automation.core.DispatcherData.TrackedOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * 成品自动交付：厨师炒完的菜直接搬进对应操作台。
 *
 * <p>原女仆餐厅流程中，厨师炒完菜后成品留在自己背包、依赖"备菜"搬运；
 * 一旦备菜没触发或厨师马上接下一单，菜品就会越堆越多。这里由调度显示器每轮检查：
 * 对每个在制订单，按"订单需求 − 操作台已有"的差额，从本店上岗女仆背包里
 * 把对应成品搬进操作台（槽 0 是订单槽，只从槽 1 开始放）。</p>
 */
public final class DishDeliveryHelper {

    private DishDeliveryHelper() {
    }

    public static void deliverFinishedDishes(DispatcherBlockEntity be, ServerLevel level) {
        if (be == null || level == null) return;
        for (TrackedOrder order : be.getTrackedOrders().values()) {
            if (!order.state.isActive() || order.counterPos == null) continue;
            BlockPos counterPos = order.counterPos;
            if (!(level.getBlockEntity(counterPos) instanceof TakeoutBoxBlockEntity)) continue;
            IItemHandler counterHandler = OrderBridge.getItemHandler(level.getBlockEntity(counterPos));
            if (counterHandler == null) continue;
            Map<String, Integer> onCounter = countFoodOnCounter(level, counterPos);
            for (String itemId : order.foods.getAllKeys()) {
                int need = order.foods.getInt(itemId) - onCounter.getOrDefault(itemId, 0);
                if (need <= 0) continue;
                for (EntityMaid maid : TaskManager.getInstance().getCachedMaids(level)) {
                    if (need <= 0) break;
                    if (maid == null || !maid.isAlive()) continue;
                    if (!be.getBindingsMap().containsKey(maid.getUUID())) continue; // 只搬本店员工
                    IItemHandler inv = MaidUtils.getInventory(maid);
                    if (inv == null) continue;
                    for (int slot = 0; slot < inv.getSlots() && need > 0; slot++) {
                        ItemStack stack = inv.getStackInSlot(slot);
                        if (stack.isEmpty()) continue;
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        if (id == null || !itemId.equals(id.toString())) continue;
                        int take = Math.min(need, stack.getCount());
                        ItemStack extracted = inv.extractItem(slot, take, false);
                        if (extracted.isEmpty()) continue;
                        ItemStack remainder = insertIntoCounterSlots(counterHandler, extracted);
                        int inserted = extracted.getCount() - remainder.getCount();
                        if (inserted > 0) {
                            need -= inserted;
                            onCounter.merge(itemId, inserted, Integer::sum);
                        }
                        if (!remainder.isEmpty()) {
                            inv.insertItem(slot, remainder, false);
                        }
                    }
                }
            }
        }
    }

    /** 统计操作台上各菜品的份数（跳过订单槽 0）。 */
    public static Map<String, Integer> countFoodOnCounter(ServerLevel level, BlockPos counter) {
        Map<String, Integer> counts = new HashMap<>();
        if (level == null || counter == null) return counts;
        try {
            IItemHandler handler = OrderBridge.getItemHandler(level.getBlockEntity(counter));
            if (handler == null) return counts;
            for (int slot = 1; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null) {
                    counts.merge(id.toString(), stack.getCount(), Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        return counts;
    }

    /** 把物品从操作台槽位 1 开始插入（槽 0 是订单槽，不能覆盖）。 */
    private static ItemStack insertIntoCounterSlots(IItemHandler counter, ItemStack stack) {
        ItemStack remainder = stack;
        for (int slot = 1; slot < counter.getSlots() && !remainder.isEmpty(); slot++) {
            remainder = counter.insertItem(slot, remainder, false);
        }
        return remainder;
    }
}
