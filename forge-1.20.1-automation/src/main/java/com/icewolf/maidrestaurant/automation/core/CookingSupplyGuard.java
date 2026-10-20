package com.icewolf.maidrestaurant.automation.core;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation;
import com.icewolf.maidrestaurant.automation.config.BatchCookingConfig;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.api.IMaidStorage;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.BlockUsageManager;
import com.mastermarisa.maid_restaurant.utils.CookTasks;
import com.mastermarisa.maid_restaurant.utils.ItemHandlerUtils;
import com.mastermarisa.maid_restaurant.utils.MaidStorages;
import com.mastermarisa.maid_restaurant.utils.RequestManager;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;

/** 自动炒锅任务按配方补料，并把多余油脂退回可达仓库。 */
public final class CookingSupplyGuard {
    private static final TagKey<Item> OIL = TagKey.create(Registries.ITEM,
            new ResourceLocation("kaleidoscope_cookery", "oil"));
    private static final String NEXT_OIL_RETURN = "maid_restaurant_automation:nextOilReturn";

    private CookingSupplyGuard() {
    }

    private static CookRequest automaticPotRequest(EntityMaid maid) {
        if (!(RequestManager.peek(maid, CookRequest.TYPE) instanceof CookRequest request)
                || request.extraData == null || !request.extraData.contains("BusinessCounter")) {
            return null;
        }
        ICookTask task = CookTasks.getTask(request.type);
        return task != null && "PotCookTask".equals(task.getUID()) ? request : null;
    }

    /** 每个重复食材谓词单独计数，保留两青椒、一牛肉等配方需求。 */
    private static List<Pair<StackPredicate, Integer>> missing(ServerLevel level, EntityMaid maid,
                                                               CookRequest request, boolean includeBackpack) {
        ICookTask task = CookTasks.getTask(request.type);
        Recipe<?> recipe = level.getRecipeManager().byKey(request.id).orElse(null);
        if (recipe == null || task == null || request.remain <= 0) return List.of();
        int cooks = Math.min(request.remain, BatchCookingConfig.getBatchCap(task.getUID()));
        List<ItemStack> available = new ArrayList<>();
        if (includeBackpack) available.addAll(ItemHandlerUtils.toStacks(maid.getAvailableInv(false)));
        if (request.targets != null && request.targets.length > 0) {
            BlockPos pot = BlockPos.of(request.targets[0]);
            if (level.isLoaded(pot) && (BlockUsageManager.getUserCount(pot) <= 0
                    || BlockUsageManager.isUsing(pot, maid.getUUID()))) {
                available.addAll(task.getCurrentInput(level, pot, maid));
            }
        }
        return ItemHandlerUtils.filterByCount(task.getIngredients(recipe, level), available, cooks);
    }

    /** 只接管本附属发布的炒锅任务，其余任务仍走本体取料逻辑。 */
    public static void takeIngredients(ServerLevel level, BlockPos pos, IMaidStorage storage,
                                       IItemHandler inventory, StackPredicate predicate, int count,
                                       EntityMaid maid) {
        CookRequest request = automaticPotRequest(maid);
        if (request == null) {
            ItemHandlerUtils.tryTakeFrom(level, pos, storage, inventory, predicate, count);
            return;
        }
        IItemHandler source = storage.getHandler(level, pos);
        if (source == null || !storage.isValid(level, pos)) return;
        for (int slot = 0; slot < source.getSlots() && count > 0; slot++) {
            ItemStack stored = source.getStackInSlot(slot);
            if (stored.isEmpty() || !predicate.test(stored)) continue;
            int needed = 0;
            for (Pair<StackPredicate, Integer> requirement : missing(level, maid, request, true)) {
                if (requirement.left().test(stored)) needed += requirement.right();
            }
            int take = Math.min(count, Math.min(needed, stored.getCount()));
            if (take <= 0) continue;
            ItemStack simulated = storage.extract(level, pos, slot, take, true);
            if (simulated.isEmpty() || !predicate.test(simulated)) continue;
            ItemStack offered = simulated.copyWithCount(Math.min(take, simulated.getCount()));
            ItemStack rejected = ItemHandlerHelper.insertItemStacked(inventory, offered.copy(), true);
            int accepted = offered.getCount() - rejected.getCount();
            if (accepted <= 0) continue;
            ItemStack extracted = storage.extract(level, pos, slot, accepted, false);
            if (extracted.isEmpty()) continue;
            // 存储实现返回超额数量时也只收预算内部分，其余退回原存储。
            int boundedCount = Math.min(accepted, extracted.getCount());
            ItemStack excess = extracted.copyWithCount(extracted.getCount() - boundedCount);
            ItemStack toInsert = extracted.copyWithCount(boundedCount);
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inventory, toInsert, false);
            // 缓存数量再插入，不能依赖其他模组是否会修改传入的 ItemStack。
            count -= boundedCount - leftover.getCount();
            restoreToStorage(level, pos, storage, inventory, maid, excess);
            restoreToStorage(level, pos, storage, inventory, maid, leftover);
        }
    }

    /** 调度器维护时恢复已被油脂占满的背包，不挪动其他材料、厨具或私人用品。 */
    public static void returnSurplusOil(ServerLevel level, EntityMaid maid) {
        CookRequest request = automaticPotRequest(maid);
        if (request == null || request.remain <= 0) return;
        long now = level.getGameTime();
        if (now < maid.getPersistentData().getLong(NEXT_OIL_RETURN)) return;
        // 配方失效时不据此退料，交给本体清理请求。
        if (level.getRecipeManager().byKey(request.id).isEmpty()) return;
        IItemHandler inventory = maid.getAvailableInv(false);
        int[] keep = new int[inventory.getSlots()];
        for (Pair<StackPredicate, Integer> requirement : missing(level, maid, request, false)) {
            int need = requirement.right();
            for (int slot = 0; slot < inventory.getSlots() && need > 0; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty() || !stack.is(OIL) || !requirement.left().test(stack)) continue;
                int reserved = Math.min(need, stack.getCount() - keep[slot]);
                keep[slot] += reserved;
                need -= reserved;
            }
        }
        int[] surplus = new int[inventory.getSlots()];
        int total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !stack.is(OIL)) continue;
            surplus[slot] = stack.getCount() - keep[slot];
            total += surplus[slot];
        }
        if (total <= 0) return;
        // 无空位的仓库隔两秒重试，避免满背包时每轮遍历整个取料范围。
        maid.getPersistentData().putLong(NEXT_OIL_RETURN, now + 40L);
        BlockPos center = maid.isHomeModeEnable() ? maid.getRestrictCenter() : maid.blockPosition().below();
        int radius = Math.max(0, (int) maid.getRestrictRadius());
        int remaining = total;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!level.isLoaded(pos)) continue;
            IMaidStorage storage = MaidStorages.tryGetType(level, pos);
            // 餐桌同样注册为可取料存储，但多余油脂不能被当成菜品摆上餐桌。
            if (storage == null || "TableStorage".equals(storage.getUID())) continue;
            for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
                if (surplus[slot] <= 0) continue;
                ItemStack sample = inventory.extractItem(slot, surplus[slot], true);
                if (sample.isEmpty()) continue;
                ItemStack rejected = storage.insert(level, pos, sample.copy(), true);
                int accepted = sample.getCount() - rejected.getCount();
                if (accepted <= 0) continue;
                ItemStack extracted = inventory.extractItem(slot, accepted, false);
                int extractedCount = extracted.getCount();
                ItemStack leftover = storage.insert(level, pos, extracted, false);
                int moved = extractedCount - leftover.getCount();
                surplus[slot] -= moved;
                remaining -= moved;
                restoreToMaid(maid, inventory, leftover);
            }
            if (remaining <= 0) break;
        }
        if (remaining < total) {
            MaidRestaurantAutomation.LOGGER.debug("自动炒锅退回多余油脂：女仆={} 数量={}",
                    maid.getName().getString(), total - remaining);
        }
    }

    private static void restoreToStorage(ServerLevel level, BlockPos pos, IMaidStorage storage,
                                          IItemHandler inventory, EntityMaid maid, ItemStack stack) {
        if (!stack.isEmpty()) restoreToMaid(maid, inventory, storage.insert(level, pos, stack, false));
    }

    private static void restoreToMaid(EntityMaid maid, IItemHandler inventory, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack leftover = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
        if (!leftover.isEmpty()) {
            // 存储在模拟与提交之间变化且背包也被占用：保留实体掉落，不吞掉物品。
            maid.spawnAtLocation(leftover);
            MaidRestaurantAutomation.LOGGER.warn("油脂退料时存储和背包同时拒收，已在女仆旁保留物品掉落");
        }
    }
}
