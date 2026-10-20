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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 自动炒锅任务按配方补料，并把多余油脂退回可达仓库。 */
public final class CookingSupplyGuard {
    private static final TagKey<Item> OIL = TagKey.create(Registries.ITEM,
            new ResourceLocation("kaleidoscope_cookery", "oil"));
    private static final String NEXT_OIL_RETURN = "maid_restaurant_automation:nextOilReturn";
    private static final String OIL_RETURN_CURSOR = "maid_restaurant_automation:oilReturnCursor";
    private static final int MAX_CANDIDATES_PER_CHECK = 64;
    private static final int MAX_STORAGES_PER_CHECK = 4;
    private static final int MAX_RETURNED_STACKS_PER_CHECK = 2;

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
        List<Pair<StackPredicate, Integer>> requirements = missing(level, maid, request, true);
        if (requirements.isEmpty()) return;
        IItemHandler source = storage.getHandler(level, pos);
        if (source == null || !storage.isValid(level, pos)) return;
        int sourceSlots = source.getSlots();
        for (int slot = 0; slot < sourceSlots && count > 0; slot++) {
            ItemStack stored = source.getStackInSlot(slot);
            if (stored.isEmpty() || !predicate.test(stored)) continue;
            int needed = 0;
            for (Pair<StackPredicate, Integer> requirement : requirements) {
                if (requirement.left().test(stored)) needed += requirement.right();
            }
            if (needed <= 0) continue;
            // 上游传入的缺口可能已过时，缩小本次预算，补齐后立即结束保险柜遍历。
            count = Math.min(count, needed);
            int take = Math.min(count, stored.getCount());
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
            int inserted = boundedCount - leftover.getCount();
            count -= inserted;
            restoreToStorage(level, pos, storage, inventory, maid, excess);
            restoreToStorage(level, pos, storage, inventory, maid, leftover);
            if (count <= 0) break;
            // 仅在背包实际发生变化后重算，不能对保险柜的每个槽位重复解析配方。
            if (inserted > 0) {
                requirements = missing(level, maid, request, true);
                if (requirements.isEmpty()) break;
            }
        }
    }

    /** 调度器维护时恢复已被油脂占满的背包，不挪动其他材料、厨具或私人用品。 */
    public static void returnSurplusOil(ServerLevel level, EntityMaid maid) {
        CookRequest request = automaticPotRequest(maid);
        if (request == null || request.remain <= 0) return;
        long now = level.getGameTime();
        if (now < maid.getPersistentData().getLong(NEXT_OIL_RETURN)) return;
        // 无需退料时也节流，避免每轮调度都重新计算配方与背包。
        maid.getPersistentData().putLong(NEXT_OIL_RETURN, now + 40L);
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
        BlockPos center = maid.isHomeModeEnable() ? maid.getRestrictCenter() : maid.blockPosition().below();
        int radius = Math.max(0, (int) maid.getRestrictRadius());
        List<BlockPos> candidates = loadedStorageCandidates(level, center, radius, maid.blockPosition());
        if (candidates.isEmpty()) return;
        int cursor = Math.floorMod(maid.getPersistentData().getInt(OIL_RETURN_CURSOR), candidates.size());
        Set<IItemHandler> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = total;
        int storages = 0;
        int returnedStacks = 0;
        for (int checked = 0; checked < Math.min(candidates.size(), MAX_CANDIDATES_PER_CHECK)
                && storages < MAX_STORAGES_PER_CHECK; checked++) {
            int candidateIndex = cursor;
            BlockPos pos = candidates.get(candidateIndex);
            cursor = (cursor + 1) % candidates.size();
            maid.getPersistentData().putInt(OIL_RETURN_CURSOR, cursor);
            if (!level.isLoaded(pos)) continue;
            IMaidStorage storage = MaidStorages.tryGetType(level, pos);
            // 餐桌同样注册为可取料存储，但多余油脂不能被当成菜品摆上餐桌。
            if (storage == null || "TableStorage".equals(storage.getUID())) continue;
            IItemHandler destination = storage.getHandler(level, pos);
            // 多方块保险柜可能从多个方块暴露同一个背包，一轮只尝试一次。
            if (destination == null || destination == inventory || !visited.add(destination)) continue;
            storages++;
            List<ItemStack> rejectedTypes = new ArrayList<>();
            for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
                if (surplus[slot] <= 0) continue;
                ItemStack sample = inventory.extractItem(slot, surplus[slot], true);
                if (sample.isEmpty()) continue;
                if (rejectedTypes.stream().anyMatch(stack -> ItemStack.isSameItemSameTags(stack, sample))) continue;
                ItemStack rejected = storage.insert(level, pos, sample.copy(), true);
                int accepted = sample.getCount() - rejected.getCount();
                if (accepted <= 0) {
                    // 满仓已拒收这种油脂，不再为背包里每一组相同油脂扫描整座保险柜。
                    rejectedTypes.add(sample.copy());
                    continue;
                }
                ItemStack extracted = inventory.extractItem(slot, accepted, false);
                int extractedCount = extracted.getCount();
                ItemStack leftover = storage.insert(level, pos, extracted, false);
                int moved = extractedCount - leftover.getCount();
                surplus[slot] -= moved;
                remaining -= moved;
                restoreToMaid(maid, inventory, leftover);
                if (moved <= 0) rejectedTypes.add(sample.copy());
                if (moved > 0 && ++returnedStacks >= MAX_RETURNED_STACKS_PER_CHECK) {
                    // 分轮清理满背包，下轮优先继续使用刚才成功接收的仓库。
                    maid.getPersistentData().putInt(OIL_RETURN_CURSOR, candidateIndex);
                    break;
                }
            }
            if (remaining <= 0 || returnedStacks >= MAX_RETURNED_STACKS_PER_CHECK) break;
        }
        if (remaining < total) {
            MaidRestaurantAutomation.LOGGER.debug("自动炒锅退回多余油脂：女仆={} 数量={}",
                    maid.getName().getString(), total - remaining);
        }
    }

    /** 只枚举已加载区块中的方块实体，不逐个检查搜索范围内的空气和普通方块。 */
    private static List<BlockPos> loadedStorageCandidates(ServerLevel level, BlockPos center,
                                                          int radius, BlockPos maidPos) {
        List<BlockPos> candidates = new ArrayList<>();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    if (pos.getX() >= minX && pos.getX() <= maxX
                            && pos.getZ() >= minZ && pos.getZ() <= maxZ
                            && Math.abs(pos.getY() - center.getY()) <= 2) {
                        candidates.add(pos.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(maidPos))
                .thenComparingLong(BlockPos::asLong));
        return candidates;
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
