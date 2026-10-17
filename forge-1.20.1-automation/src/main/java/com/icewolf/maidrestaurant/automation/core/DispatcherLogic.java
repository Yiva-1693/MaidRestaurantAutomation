package com.icewolf.maidrestaurant.automation.core;

import cn.breezeth.ordertocook.api.OrderLifecycleApi;
import cn.breezeth.ordertocook.block.entity.FoodPlateBlockEntity;
import cn.breezeth.ordertocook.block.entity.OrderMachineBlockEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import com.icewolf.maidrestaurant.automation.core.DispatcherData.OrderState;
import com.icewolf.maidrestaurant.automation.core.DispatcherData.TrackedOrder;
import com.icewolf.maidrestaurant.automation.core.DispatcherData.WorkstationBinding;
import com.mastermarisa.maid_restaurant.request.CookRequest;
import com.mastermarisa.maid_restaurant.utils.RequestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 调度显示器服务端逻辑：打单机绑定、订单状态机推进、工位认领维护、GUI 快照生成。
 *
 * <p>对应开发文档 T3（调度显示器方块 + 状态机 + OTC 读取）、T4（工位认领）、T7（GUI 数据）。</p>
 */
public final class DispatcherLogic {

    private static final long EVAL_INTERVAL = 10L;
    private static final long BIND_MAINTAIN_INTERVAL = 20L;
    private static final long ORDER_MAINTAIN_INTERVAL = 10L;
    private static final long SNAPSHOT_INTERVAL = 20L;
    private static final int MAX_STAGE_RETRY = 3;
    private static final long BASE_BACKOFF = 40L;

    /** 订单 → 接待/入台女仆名（订单队列显示"正在被谁处理"）。 */
    private static final Map<String, String> ORDER_HANDLERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每个女仆认领尝试节流（避免每 tick 全网扫描）。 */
    private static final Map<UUID, Long> CLAIM_ATTEMPT_TICK = new HashMap<>();
    /** 每个女仆认领失败气泡的最后检测时间（60 秒一次，发气泡前先重试一次认领）。 */
    private static final Map<UUID, Long> CLAIM_FAIL_NEXT_TICK = new HashMap<>();

    private DispatcherLogic() {
    }

    public static void clearRuntimeState() {
        CLAIM_ATTEMPT_TICK.clear();
        CLAIM_FAIL_NEXT_TICK.clear();
    }

    /** 登记"正在处理该订单"的侍者名（到店顾客的订单由接待女仆放入操作台时调用）。 */
    public static void noteOrderHandler(String orderId, String maidName) {
        if (orderId == null || orderId.isEmpty() || maidName == null || maidName.isEmpty()) return;
        ORDER_HANDLERS.put(orderId, maidName);
    }

    // ================================================================
    // 主入口
    // ================================================================

    public static void tick(DispatcherBlockEntity be, ServerLevel level) {
        long now = level.getGameTime();
        if (be.getLastEvaluateTick() >= 0 && now - be.getLastEvaluateTick() < EVAL_INTERVAL) {
            return;
        }
        be.setLastEvaluateTick(now);

        ensureMachine(be, level);

        if (be.getBoundMachinePos() != null) {
            if (be.getLastBindMaintainTick() < 0 || now - be.getLastBindMaintainTick() >= BIND_MAINTAIN_INTERVAL) {
                be.setLastBindMaintainTick(now);
                maintainBindings(be, level);
            }
            if (be.getLastOrderMaintainTick() < 0 || now - be.getLastOrderMaintainTick() >= ORDER_MAINTAIN_INTERVAL) {
                be.setLastOrderMaintainTick(now);
                maintainOrders(be, level);
            }
        }
        if (be.getLastSnapshotTick() < 0 || now - be.getLastSnapshotTick() >= SNAPSHOT_INTERVAL) {
            be.setLastSnapshotTick(now);
            buildSnapshot(be, level);
        }
    }

    // ================================================================
    // 打单机绑定
    // ================================================================

    private static void ensureMachine(DispatcherBlockEntity be, ServerLevel level) {
        BlockPos machinePos = be.getBoundMachinePos();
        if (machinePos != null) {
            if (!(level.getBlockEntity(machinePos) instanceof OrderMachineBlockEntity machine)) {
                // 打单机被拆：解绑 + 级联释放工位绑定 + 清空在途订单（开发文档 4.3②/边界）
                be.clearBoundMachine();
                be.getBindingsMap().clear();
                be.getStationOwners().clear();
                for (TrackedOrder order : be.getTrackedOrders().values()) {
                    if (order.state.isActive()) {
                        order.state = OrderState.FAILED;
                        order.failReason = "打单机被拆除";
                        order.lastChangeTick = level.getGameTime();
                    }
                }
                be.getIgnoredOrderIds().clear();
                be.markDirtyAndSync();
                long now = level.getGameTime();
                if (be.getLastMachineLostNotifyTick() < 0 || now - be.getLastMachineLostNotifyTick() > 200L) {
                    be.setLastMachineLostNotifyTick(now);
                    DispatcherFeedback.machineLost(level, be.getBlockPos());
                }
                return;
            }
            if (be.getBoundMachineId() < 0) {
                be.setBoundMachineId(machine.getMachineId());
            }
            ActivationCache.activate(level, machinePos);
            return;
        }

        // 未绑定：主动扫描并绑定半径内一台未被绑定的打单机（开发文档 二）
        tryBindNearest(be, level);
    }

    /**
     * 立即尝试绑定半径内一台未被其它调度显示器绑定的打单机。
     *
     * <p>优先用 {@link WorldScanner} 的方块实体索引（覆盖已加载区块），索引未命中时再做一次
     * 以调度显示器为中心的局部方块直扫兜底，修复"先放打单机、后放调度显示器时偶发绑定不上"的问题。</p>
     */
    public static boolean tryBindNearest(DispatcherBlockEntity be, ServerLevel level) {
        int bindRadius = be.getBindRadius();
        for (BlockPos candidate : WorldScanner.scan(level, OrderMachineBlockEntity.class)) {
            if (tryBindTo(be, level, candidate, bindRadius)) return true;
        }
        // 兜底：局部直接扫描（不依赖方块实体索引 / 缓存）
        for (BlockPos candidate : BlockPos.betweenClosed(
                be.getBlockPos().offset(-bindRadius, -8, -bindRadius),
                be.getBlockPos().offset(bindRadius, 8, bindRadius))) {
            if (tryBindTo(be, level, candidate, bindRadius)) return true;
        }
        return false;
    }

    private static boolean tryBindTo(DispatcherBlockEntity be, ServerLevel level, BlockPos candidate, int bindRadius) {
        if (candidate.distSqr(be.getBlockPos()) > (double) bindRadius * bindRadius) return false;
        if (!(level.getBlockEntity(candidate) instanceof OrderMachineBlockEntity machine)) return false;
        DispatcherBlockEntity other = DispatcherManager.findForMachine(level, candidate);
        if (other != null) return false; // 已被其它调度显示器绑定
        be.setBoundMachine(candidate, machine.getMachineId());
        ActivationCache.activate(level, candidate);
        DispatcherFeedback.info(level, be.getBlockPos(),
                "已绑定打单机 " + candidate.toShortString() + "，可开始自动经营");
        return true;
    }

    // ================================================================
    // 工位认领维护（开发文档 T4）
    // ================================================================

    private static void maintainBindings(DispatcherBlockEntity be, ServerLevel level) {
        long now = level.getGameTime();
        List<EntityMaid> localMaids = nearbyMaids(be, level);
        Map<UUID, EntityMaid> byUuid = new HashMap<>();
        for (EntityMaid maid : localMaids) {
            byUuid.put(maid.getUUID(), maid);
        }

        // 0) 上岗女仆卡住自动脱困：去取菜/去工位途中被其它女仆挡住时，沿目标方向小步"穿过"，
        //    避免互相卡死（女仆之间不做硬碰撞，这里用服务端位移兜底，不依赖 mixin）。
        try {
            for (EntityMaid stuckCandidate : localMaids) {
                if (stuckCandidate == null || !stuckCandidate.isAlive()) continue;
                if (!TaskManager.getInstance().hasMaidTask(stuckCandidate.getUUID())) continue;
                unstuckMaidIfBlocked(level, be, stuckCandidate);
            }
        } catch (Throwable ignored) {
        }

        // 1) 校验已有绑定：职业变化 / 女仆消失 / 工作方块或板凳失效。
        //    注意：遍历期间禁止直接增删 Map（旧实现里 release() 会 remove，导致 ConcurrentModificationException），
        //    所有删除都只走迭代器 it.remove() + 同步清理 stationOwners。
        Iterator<Map.Entry<UUID, WorkstationBinding>> it = be.getBindingsMap().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WorkstationBinding> entry = it.next();
            WorkstationBinding binding = entry.getValue();
            EntityMaid maid = byUuid.get(entry.getKey());
            if (maid == null) {
                // 本地范围内找不到（女仆去取菜/走远/区块边界）：宽限 20 秒，避免"去取菜回来发现工位被解绑"。
                Entity raw = level.getEntity(entry.getKey());
                if (raw instanceof EntityMaid missingMaid) {
                    if (!missingMaid.isAlive()) {
                        it.remove();
                        if (binding.stationPos != null) be.getStationOwners().remove(binding.stationPos.asLong());
                        CLAIM_ATTEMPT_TICK.remove(entry.getKey());
                    } else {
                        binding.missingSinceTick = 0L;
                        ensureWorkRadius(missingMaid);
                    }
                    continue;
                }
                if (binding.missingSinceTick <= 0L) {
                    binding.missingSinceTick = now;
                } else if (now - binding.missingSinceTick > 400L) {
                    it.remove();
                    if (binding.stationPos != null) be.getStationOwners().remove(binding.stationPos.asLong());
                    CLAIM_ATTEMPT_TICK.remove(entry.getKey());
                }
                continue;
            }
            ensureWorkRadius(maid);
            String profession = StationSystem.professionOf(maid);
            if (profession == null || !profession.equals(binding.profession)) {
                // 女仆改职业（例如厨师改侍者）：级联释放旧工位，下一轮按新职业重新认领
                it.remove();
                if (binding.stationPos != null) be.getStationOwners().remove(binding.stationPos.asLong());
                CLAIM_ATTEMPT_TICK.remove(maid.getUUID());
                CLAIM_FAIL_NEXT_TICK.remove(maid.getUUID());
                continue;
            }
            BlockPos stationPos = binding.stationPos;
            boolean stationOk = stationPos != null && level.isLoaded(stationPos)
                    && StationSystem.isStationValidFor(level, maid, stationPos);
            // 板凳/待命行为已按要求删除：只校验工作方块本身，不再要求厨娘凳，也不会因缺凳判工位失效
            if (stationOk) {
                binding.invalid = false;
                continue;
            }

            // 2) 失效：暂停接单；优先重新认领原坐标，再搜索新方块（绝不自动占用他人工位）
            binding.invalid = true;
            if (!binding.invalidNotified) {
                binding.invalidNotified = true;
                DispatcherFeedback.workstationInvalid(maid, StationSystem.professionDisplayName(profession));
            }
            if (now - binding.lastReclaimTick < 100L) continue;
            binding.lastReclaimTick = now;
            if (stationPos != null && level.isLoaded(stationPos)
                    && StationSystem.isStationValidFor(level, maid, stationPos)) {
                binding.invalid = false;
                continue;
            }
            // 原坐标彻底不可用：释放并尝试重新认领（下一轮 claim 阶段）
            it.remove();
            if (stationPos != null) be.getStationOwners().remove(stationPos.asLong());
            CLAIM_ATTEMPT_TICK.remove(maid.getUUID());
        }

        // 3) 未认领女仆尝试认领
        Set<Long> claimed = new HashSet<>(be.getStationOwners().keySet());
        for (EntityMaid maid : localMaids) {
            if (be.getBindingsMap().containsKey(maid.getUUID())) continue;
            Long lastAttempt = CLAIM_ATTEMPT_TICK.get(maid.getUUID());
            if (lastAttempt != null && now - lastAttempt < 40L) continue;
            CLAIM_ATTEMPT_TICK.put(maid.getUUID(), now);

            String profession = StationSystem.professionOf(maid);
            if (profession == null) continue;
            boolean production = StationSystem.isProductionProfession(profession);
            String displayName = StationSystem.professionDisplayName(profession);

            // 开发文档 4.3③：到点时先再检测并尝试认领一次，仍失败才发气泡
            BlockPos station = StationSystem.findFreeStation(level, maid, be.getBlockPos(),
                    be.getWorkRadius(), 8, claimed);
            String failReason = null;
            if (station == null) {
                failReason = "no_station";
            } else {
                {
                    WorkstationBinding binding = new WorkstationBinding();
                    binding.maidUuid = maid.getUUID();
                    binding.maidName = maid.getName().getString();
                    binding.profession = profession;
                    binding.stationUid = StationSystem.stationUid(level, maid, station);
                    binding.stationPos = station;
                    binding.seatPos = null;
                    binding.bindTick = now;
                    binding.firstClaimNotified = true;
                    binding.invalid = false;
                    be.getBindingsMap().put(maid.getUUID(), binding);
                    be.getStationOwners().put(station.asLong(), maid.getUUID());
                    claimed.add(station.asLong());
                    // 绿色粒子（仅首次认领成功）+ 认领成功气泡
                    ensureWorkRadius(maid);
                    DispatcherFeedback.workstationClaimed(level, station);
                    DispatcherFeedback.claimSuccess(maid, displayName);
                    be.markDirtyAndSync();
                }
            }
            if (failReason != null) {
                Long next = CLAIM_FAIL_NEXT_TICK.get(maid.getUUID());
                if (next == null || now >= next) {
                    CLAIM_FAIL_NEXT_TICK.put(maid.getUUID(), now + 60L * 20L);
                    boolean anyPresent = anyStationPresent(be, level, maid);
                    DispatcherFeedback.claimFailed(maid, displayName, !anyPresent);
                }
            } else {
                CLAIM_FAIL_NEXT_TICK.remove(maid.getUUID());
            }
        }
    }
    /** 上岗女仆卡住检测：uuid -> {上次位置, 连续未移动次数, 上次脱困 tick}。 */
    private static final Map<UUID, long[]> STUCK_TRACKER = new java.util.HashMap<>();

    private static final double STUCK_MOVE_EPSILON = 0.0025D; // 0.05 格
    private static final int STUCK_CHECKS_BEFORE_RESCUE = 3;   // 20t * 3 = 3 秒
    private static final long UNSTUCK_COOLDOWN_TICKS = 60L;    // 3 秒

    /**
     * 上岗女仆卡住自动脱困：连续 3 次维护（约 3 秒）几乎没移动、且身上有餐厅任务时，
     * 沿她当前寻路目标方向瞬移 1.2~2 格（穿过后面的女仆），并清空导航让其重新寻路。
     */
    private static void unstuckMaidIfBlocked(ServerLevel level, DispatcherBlockEntity be, EntityMaid maid) {
        long now = level.getGameTime();
        long[] track = STUCK_TRACKER.get(maid.getUUID());
        double px = maid.getX();
        double py = maid.getY();
        double pz = maid.getZ();
        if (track == null) {
            STUCK_TRACKER.put(maid.getUUID(), new long[]{(long) (px * 1000), (long) (py * 1000), (long) (pz * 1000), 0L, 0L});
            return;
        }
        double dx = px - track[0] / 1000.0D;
        double dy = py - track[1] / 1000.0D;
        double dz = pz - track[2] / 1000.0D;
        boolean moved = dx * dx + dy * dy + dz * dz > STUCK_MOVE_EPSILON;
        Track:
        if (moved) {
            track[3] = 0L;
            track[0] = (long) (px * 1000);
            track[1] = (long) (py * 1000);
            track[2] = (long) (pz * 1000);
            return;
        }
        track[3]++;
        if (track[3] < STUCK_CHECKS_BEFORE_RESCUE) return;
        if (now - track[4] < UNSTUCK_COOLDOWN_TICKS) return;
        track[3] = 0L;
        track[4] = now;
        // 目标方向：优先当前寻路目标，其次自己绑定的工位
        BlockPos target = null;
        try {
            var walkTarget = maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            if (walkTarget.isPresent()) {
                target = walkTarget.get().getTarget().currentBlockPosition();
            }
        } catch (Throwable ignored) {
        }
        if (target == null) {
            DispatcherData.WorkstationBinding binding = be.getBindingsMap().get(maid.getUUID());
            if (binding != null) target = binding.stationPos;
        }
        if (target == null) return;
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double vx = tx - px;
        double vz = tz - pz;
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len < 0.3D) return;
        double step = Math.min(2.0D, len);
        double nx = px + vx / len * step;
        double nz = pz + vz / len * step;
        double ny = py;
        try {
            net.minecraft.world.phys.AABB movedBox = maid.getBoundingBox().move(nx - px, 0.0D, nz - pz);
            if (!level.noCollision(maid, movedBox)) {
                // 斜向被方块挡住：尝试只沿 x 或只沿 z 走
                net.minecraft.world.phys.AABB onlyX = maid.getBoundingBox().move(vx / len * step, 0.0D, 0.0D);
                net.minecraft.world.phys.AABB onlyZ = maid.getBoundingBox().move(0.0D, 0.0D, vz / len * step);
                if (level.noCollision(maid, onlyX)) {
                    nx = px + vx / len * step;
                    nz = pz;
                } else if (level.noCollision(maid, onlyZ)) {
                    nx = px;
                    nz = pz + vz / len * step;
                } else {
                    return;
                }
            }
            maid.teleportTo(nx, ny, nz);
            maid.getNavigation().stop();
            com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.debug("女仆脱困位移: {} -> ({}, {}, {})", maid.getName().getString(), nx, ny, nz);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把已认领工位女仆的活动/工作半径扩大到至少 24 格。' + "`r`n"
     *
     * <p>原女仆餐厅的取菜容器搜索、找工位、距离校验都基于车万女仆的 {@code getRestrictRadius()}
     * （默认较小，常见 10 格）；取菜稍远就会超出范围导致任务失败、看起来像"工位被解绑"。
     * 这里用原版 {@code restrictTo(center, radius)} API 把活动半径扩到 15（只增不减）。</p>
     */
    private static void ensureWorkRadius(EntityMaid maid) {
        if (maid == null) return;
        try {
            BlockPos center = maid.getRestrictCenter();
            if (center == null) return;
            int current = (int) maid.getRestrictRadius();
            if (current < 24) {
                // 绑定厨具/取菜的活动范围扩大到 24 格，避免女仆取菜走远时被判出范围而"解绑工位"
                maid.restrictTo(center, 24);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void release(DispatcherBlockEntity be, WorkstationBinding binding) {
        if (binding.stationPos != null) {
            be.getStationOwners().remove(binding.stationPos.asLong());
        }
        be.getBindingsMap().remove(binding.maidUuid);
        be.markDirtyAndSync();
    }

    /** 半径内是否存在（任意女仆可用的）该类工位，用于区分"店里没有"与"都被认领"两种气泡。 */
    private static boolean anyStationPresent(DispatcherBlockEntity be, ServerLevel level, EntityMaid maid) {
        int radius = be.getWorkRadius();
        BlockPos center = be.getBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -8, -radius),
                center.offset(radius, 8, radius))) {
            if (!level.isLoaded(pos)) continue;
            if (StationSystem.isStationValidFor(level, maid, pos)) return true;
        }
        return false;
    }

    private static List<EntityMaid> nearbyMaids(DispatcherBlockEntity be, ServerLevel level) {
        List<EntityMaid> result = new ArrayList<>();
        double radiusSqr = (double) be.getWorkRadius() * be.getWorkRadius();
        for (EntityMaid maid : TaskManager.getInstance().getCachedMaids(level)) {
            if (maid == null || !maid.isAlive()) continue;
            if (maid.distanceToSqr(be.getBlockPos().getX() + 0.5D, be.getBlockPos().getY() + 0.5D,
                    be.getBlockPos().getZ() + 0.5D) <= radiusSqr) {
                result.add(maid);
            }
        }
        return result;
    }

    // ================================================================
    // 订单状态机（开发文档 T3 / 5.1-5.5）
    // ================================================================

    private static void maintainOrders(DispatcherBlockEntity be, ServerLevel level) {
        BlockPos machinePos = be.getBoundMachinePos();
        if (machinePos == null) return;
        if (!(level.getBlockEntity(machinePos) instanceof OrderMachineBlockEntity machine)) return;
        long now = level.getGameTime();
        int machineId = be.getBoundMachineId() >= 0 ? be.getBoundMachineId() : machine.getMachineId();
        if (be.getBoundMachineId() < 0) be.setBoundMachineId(machineId);

        // 1) 消费 OTC 订单生命周期事件（完成 / 过期 / 取消）
        for (OrderLifecycleApi.Event event : DispatcherOrderEvents.drain(level, machineId)) {
            TrackedOrder order = be.getTrackedOrders().get(event.order().orderId());
            if (order == null) continue;
            OrderLifecycleApi.Type type = event.type();
            if (type == OrderLifecycleApi.Type.COMPLETED) {
                order.state = OrderState.CLOSED;
                order.lastChangeTick = now;
            } else if (type == OrderLifecycleApi.Type.EXPIRED) {
                failOrder(be, level, order, "订单已过期", false);
            } else if (type == OrderLifecycleApi.Type.CANCELLED) {
                failOrder(be, level, order, "订单被取消", false);
            }
        }

        // 2) 打单机取单入台（每轮最多接受一张，与 OTC 玩家接单行为一致）
        boolean anyActive = false;
        for (TrackedOrder order : be.getTrackedOrders().values()) {
            if (order.state.isActive()) {
                anyActive = true;
                break;
            }
        }
        if (!anyActive) {
            acceptOneMachineOrder(be, level, machine, machinePos, machineId, now);
        }

        // 3) 吸纳操作台上已有的订单（到店顾客单 / 其它链路放入的堂食单）
        adoptCounterOrders(be, level, machinePos, now);

        // 4) 推进订单状态 / 过期清理 / 重试
        Iterator<TrackedOrder> orderIterator = be.getTrackedOrders().values().iterator();
        while (orderIterator.hasNext()) {
            TrackedOrder order = orderIterator.next();
            if (order.state.isTerminal()) {
                if (now - order.lastChangeTick > 400L) {
                    be.getIgnoredOrderIds().add(order.orderId);
                    orderIterator.remove();
                }
                continue;
            }
            if (order.expiryTick > 0 && now > order.expiryTick) {
                failOrder(be, level, order, "订单已过期", true);
                continue;
            }
            if (order.counterPos == null) {
                if (now >= order.nextRetryTick) {
                    tryPlaceQueuedOrder(be, level, machine, machinePos, machineId, order, now);
                }
                continue;
            }
            advanceOrderState(be, level, order, now);
        }
        // 成品自动交付：厨师炒完菜后优先把成品放进对应操作台，避免堆在背包里
        try {
            DishDeliveryHelper.deliverFinishedDishes(be, level);
        } catch (Throwable ignored) {
        }
        if (be.getIgnoredOrderIds().size() > 128) {
            be.getIgnoredOrderIds().clear();
        }
    }

    private static void acceptOneMachineOrder(DispatcherBlockEntity be, ServerLevel level,
                                              OrderMachineBlockEntity machine, BlockPos machinePos,
                                              int machineId, long now) {
        NonNullList<ItemStack> items = machine.getItems();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || !stack.is(OtcCompat.ORDER())) continue;
            CompoundTag nbt = stack.getTag();
            if (nbt == null || !nbt.contains("FoodList")) continue;
            String orderId = nbt.getString("OrderId");
            if (orderId.isEmpty()) continue;
            if (be.getTrackedOrders().containsKey(orderId) || be.getIgnoredOrderIds().contains(orderId)) continue;
            // 开发文档 一：不做外卖，Order_Type > 1 / Delivery=true 的订单完全忽略
            if (nbt.getBoolean("Delivery")) continue;
            // 玩家点单（保留的旧经济系统）走既有链路，不由调度显示器接管
            if (nbt.getBoolean(PlayerOrderManager.PLAYER_ORDER)) continue;
            long expiry = nbt.contains("ExpiryTick") ? nbt.getLong("ExpiryTick") : -1L;
            if (expiry > 0 && expiry < now) {
                be.getIgnoredOrderIds().add(orderId);
                continue;
            }
            TrackedOrder order = new TrackedOrder();
            order.orderId = orderId;
            order.customerName = nbt.getString("CustomerName");
            order.machineId = machineId;
            order.walkIn = false;
            order.expiryTick = expiry;
            order.createTick = now;
            order.lastChangeTick = now;
            order.state = OrderState.QUEUED;
            order.foods = nbt.getCompound("FoodList").copy();
            be.getTrackedOrders().put(orderId, order);
            if (tryPlaceQueuedOrder(be, level, machine, machinePos, machineId, order, now)) {
                be.markDirtyAndSync();
                return;
            }
            return;
        }
    }

    private static boolean tryPlaceQueuedOrder(DispatcherBlockEntity be, ServerLevel level,
                                               OrderMachineBlockEntity machine, BlockPos machinePos,
                                               int machineId, TrackedOrder order, long now) {
        if (now < order.nextRetryTick) return false;
        BlockPos counter = findPlacementCounter(be, level, machinePos);
        if (counter == null) {
            order.stageRetryCount++;
            order.retryCount++;
            order.nextRetryTick = now + backoff(order.stageRetryCount);
            order.failReason = "没有空闲操作台";
            if (order.stageRetryCount > MAX_STAGE_RETRY) {
                failOrder(be, level, order, "没有空闲操作台（重试超限）", false);
            }
            return false;
        }
        // 构造原子写入操作台的订单副本（补齐 OTC 接单时 GUI 会写的机器归属 NBT）
        ItemStack orderStack = findMachineOrderStack(machine, order.orderId);
        if (orderStack.isEmpty()) {
            failOrder(be, level, order, "订单已从打单机消失", false);
            return false;
        }
        CompoundTag enriched = enrichOrderNbt(level, machine, machinePos, machineId, order.orderId, orderStack.getTag());
        ItemStack toCounter = orderStack.copy();
        toCounter.setTag(enriched);
        if (!OrderBridge.putOrderIntoSlot0(level, counter, toCounter)) {
            order.stageRetryCount++;
            order.nextRetryTick = now + backoff(order.stageRetryCount);
            order.failReason = "操作台写入失败";
            return false;
        }
        // 与玩家接单一致：打单机清空挂单并转入 idle
        machine.onOrderAccepted();
        // 打单机来源的堂食单需要生成顾客
        try {
            if (!order.walkIn) {
                OrderBridge.spawnCustomerForOrder(level, machinePos, enriched);
            }
        } catch (Throwable t) {
            com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.warn(
                    "调度显示器生成顾客失败 order={}", order.orderId, t);
        }
        order.counterPos = counter;
        order.state = OrderState.PLACED;
        order.stageRetryCount = 0;
        order.failReason = "";
        order.lastChangeTick = now;
        be.markDirtyAndSync();
        return true;
    }

    private static void adoptCounterOrders(DispatcherBlockEntity be, ServerLevel level, BlockPos machinePos, long now) {
        for (BlockPos counter : OrderBridge.scanCountersAround(level, machinePos)) {
            ItemStack orderStack = readCounterOrder(level, counter);
            if (orderStack.isEmpty()) continue;
            CompoundTag nbt = orderStack.getTag();
            if (nbt == null) continue;
            String orderId = nbt.getString("OrderId");
            if (orderId.isEmpty()) continue;
            if (be.getTrackedOrders().containsKey(orderId)) continue;
            // 订单被玩家/侍者从操作台拿出再放回（或重新出现）时，撤销"已忽略"标记并重新纳入队列，
            // 修复"拿出再放回后 订单列表/食物列表不再显示"的问题。
            be.getIgnoredOrderIds().remove(orderId);
            if (nbt.getBoolean("Delivery")) continue;
            TrackedOrder order = new TrackedOrder();
            order.orderId = orderId;
            order.customerName = nbt.getString("CustomerName");
            order.machineId = be.getBoundMachineId();
            order.walkIn = nbt.contains("Order_Type") && nbt.getInt("Order_Type") == 1;
            order.prestige = nbt.contains("Prestige") ? nbt.getInt("Prestige") : 0;
            order.handledBy = ORDER_HANDLERS.getOrDefault(orderId, "侍者已接单");
            order.expiryTick = nbt.contains("ExpiryTick") ? nbt.getLong("ExpiryTick") : -1L;
            order.createTick = now;
            order.lastChangeTick = now;
            order.state = OrderState.PLACED;
            order.counterPos = counter.immutable();
            order.foods = nbt.contains("FoodList") ? nbt.getCompound("FoodList").copy() : new CompoundTag();
            be.getTrackedOrders().put(orderId, order);
        }
    }

    private static void advanceOrderState(DispatcherBlockEntity be, ServerLevel level, TrackedOrder order, long now) {
        BlockPos counter = order.counterPos;
        if (!level.isLoaded(counter)) {
            // 区块未加载：先等待，不能误判为"操作台已消失"而失败
            return;
        }
        if (!(level.getBlockEntity(counter) instanceof cn.breezeth.ordertocook.block.entity.TakeoutBoxBlockEntity)) {
            failOrder(be, level, order, "操作台已消失", false);
            return;
        }
        ItemStack counterOrder = readCounterOrder(level, counter);
        boolean stillThere = !counterOrder.isEmpty() && counterOrder.getTag() != null
                && order.orderId.equals(counterOrder.getTag().getString("OrderId"));
        if (!stillThere) {
            // 订单物品离开操作台：多数情况是 OTC 装盘（装盘会消耗操作台上的订单物品）→ 进入送餐阶段，
            // 最终由 OTC 生命周期 COMPLETED 事件闭环；若长时间没有任何事件则兜底关闭。
            if (isPlateAbove(level, counter)) {
                order.state = OrderState.DELEGATED_TO_WAITER;
                order.lastChangeTick = now;
                return;
            }
            if (order.state == OrderState.READY || order.state == OrderState.DELEGATED_TO_WAITER
                    || order.state == OrderState.DELIVERING) {
                order.state = OrderState.DELIVERING;
                if (now - order.lastChangeTick > 600L) {
                    order.state = OrderState.CLOSED;
                    order.counterPos = null;
                }
                return;
            }
            order.state = OrderState.CLOSED;
            order.lastChangeTick = now;
            order.counterPos = null;
            return;
        }
        // 厨师归属按"每道菜"在 GUI 快照阶段精确匹配（findAssignedCookName(level,counter,itemId)），
        // 订单级 assignedMaidName 不再用整单共用一个名字。
        if (isPlateAbove(level, counter)) {
            order.state = OrderState.DELEGATED_TO_WAITER;
        } else if (isAllFoodReady(level, counter, order.foods)) {
            order.state = OrderState.READY;
        } else if (TaskManager.getInstance().hasTaskAt(counter, TaskManager.TYPE_COOKING)) {
            order.state = OrderState.COOKING;
        } else if (order.state == OrderState.QUEUED || order.state == OrderState.PLACED) {
            order.state = OrderState.PLACED;
        }
        if (order.state != OrderState.COOKING && order.state != OrderState.READY
                && now - order.lastChangeTick > 1200L) {
            // 长时间无任何推进：阶段重试计数 + 退避，交给烹饪链路重新接手
            order.stageRetryCount++;
            if (order.stageRetryCount > MAX_STAGE_RETRY) {
                failOrder(be, level, order, "制作阶段长时间无进展", false);
            } else {
                order.nextRetryTick = now + backoff(order.stageRetryCount);
                order.lastChangeTick = now;
            }
        }
    }

    private static void failOrder(DispatcherBlockEntity be, ServerLevel level, TrackedOrder order,
                                  String reason, boolean clearCounter) {
        order.state = OrderState.FAILED;
        order.failReason = reason;
        order.lastChangeTick = level.getGameTime();
        be.getIgnoredOrderIds().add(order.orderId);
        if (clearCounter && order.counterPos != null) {
            clearCounterOrder(level, order.counterPos);
            order.counterPos = null;
        }
        DispatcherFeedback.orderFailed(level, be.getBlockPos(), order.orderId, reason);
        be.markDirtyAndSync();
    }

    /** 外部（侍者超时顶替）调用：强制让某操作台上的过期订单失败并释放台面。 */
    public static void forceExpireCounterOrder(ServerLevel level, BlockPos counterPos) {
        if (level == null || counterPos == null) return;
        ItemStack orderStack = readCounterOrder(level, counterPos);
        if (orderStack.isEmpty() || orderStack.getTag() == null) return;
        String orderId = orderStack.getTag().getString("OrderId");
        CompoundTag nbt = orderStack.getTag();
        long expiry = nbt.contains("ExpiryTick") ? nbt.getLong("ExpiryTick") : -1L;
        if (expiry <= 0 || expiry > level.getGameTime()) return;
        clearCounterOrder(level, counterPos);
        for (DispatcherBlockEntity dispatcher : DispatcherManager.getDispatchers(level)) {
            TrackedOrder tracked = dispatcher.getTrackedOrders().get(orderId);
            if (tracked != null && tracked.state.isActive()) {
                tracked.state = OrderState.FAILED;
                tracked.failReason = "订单过期，已被侍者顶替";
                tracked.lastChangeTick = level.getGameTime();
                tracked.counterPos = null;
                dispatcher.getIgnoredOrderIds().add(orderId);
                dispatcher.markDirtyAndSync();
                return;
            }
        }
    }

    // ================================================================
    // GUI 快照（开发文档 ⑧⑨）
    // ================================================================

    private static void buildSnapshot(DispatcherBlockEntity be, ServerLevel level) {
        long now = level.getGameTime();
        List<String> header = new ArrayList<>();
        List<String> notice = new ArrayList<>();

        BlockPos machinePos = be.getBoundMachinePos();
        if (machinePos == null) {
            be.setStatusMode(be.getLastMachineLostNotifyTick() >= 0
                    ? DispatcherBlockEntity.STATUS_MACHINE_LOST : DispatcherBlockEntity.STATUS_UNBOUND);
            header.add("§6餐厅调度显示器");
            header.add("§7状态：§c未绑定打单机");
            notice.add(be.getLastMachineLostNotifyTick() >= 0
                    ? "§c绑定的打单机已消失，请在 16 格内重新放置打单机"
                    : "§e请在 16 格内放置打单机，调度显示器会自动绑定");
            be.setDisplaySnapshot(header, notice);
            be.setStructuredSnapshot("", List.of(), List.of(), "§7暂无员工信息", List.of(), List.of());
            return;
        }

        OrderMachineBlockEntity machine = level.getBlockEntity(machinePos) instanceof OrderMachineBlockEntity m ? m : null;
        String restaurantName = "";
        String ownerName = "";
        int restaurantLevel = 0;
        if (machine != null) {
            try {
                OrderMachineBlockEntity.RestaurantStats stats = machine.snapshotStats();
                restaurantName = stats.name() == null ? "" : stats.name();
                ownerName = stats.owner() == null ? "" : stats.owner();
                restaurantLevel = stats.level();
            } catch (Throwable ignored) {
            }
        }
        header.add("§6餐厅调度显示器");
        header.add("§7打单机：§a" + machinePos.toShortString());
        header.add("§7餐厅：§f" + (restaurantName.isEmpty() ? "未命名" : restaurantName)
                + " §7店长：§f" + (ownerName.isEmpty() ? "无" : ownerName)
                + " §7等级：§f" + restaurantLevel);

        // ---------- 员工总览（店长 / 玩家 / 女仆，结构化：GUI 负责排版与悬浮信息） ----------
        List<EntityMaid> localMaids = nearbyMaids(be, level);
        Collator collator = Collator.getInstance(Locale.CHINA);
        List<String> ownerNames = new ArrayList<>();
        List<CompoundTag> maidEntries = new ArrayList<>();
        int unclaimed = 0;
        for (EntityMaid maid : localMaids) {
            String profession = StationSystem.professionOf(maid);
            if (profession == null) continue;
            WorkstationBinding binding = be.getBindingsMap().get(maid.getUUID());
            String owner = resolveOwnerName(level, maid);
            if (!owner.isEmpty() && !owner.equals(ownerName) && !ownerNames.contains(owner)) {
                ownerNames.add(owner);
            }
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", maid.getName().getString());
            entry.putString("Job", StationSystem.professionLabel(profession));
            entry.putString("Profession", StationSystem.professionDisplayName(profession));
            entry.putString("Owner", owner);
            if (binding == null || binding.invalid || binding.stationPos == null) {
                unclaimed++;
                entry.putBoolean("Claimed", false);
                entry.putString("Station", "未认领");
            } else {
                entry.putBoolean("Claimed", true);
                entry.putString("Station", binding.stationPos.toShortString());
                if (!binding.stationUid.isEmpty()) {
                    entry.putString("Profession", StationSystem.professionDisplayName(profession)
                            + " · " + shortUid(binding.stationUid));
                }
            }
            maidEntries.add(entry);
        }
        maidEntries.sort((a, b) -> collator.compare(a.getString("Name"), b.getString("Name")));
        ownerNames.sort(collator::compare);
        String staffFooter = unclaimed > 0 ? "§7另有 §c" + unclaimed + " §7名女仆未认领工位" : "";
        if (be.getBindingsMap().isEmpty()) {
            notice.add("§e请让厨师/侍者女仆在调度显示器附近认领工位（未认领 = 不可接单）");
            be.setStatusMode(DispatcherBlockEntity.STATUS_NO_STAFF);
        } else {
            be.setStatusMode(DispatcherBlockEntity.STATUS_OK);
        }

        // ---------- 订单队列：已接待在上、未接待在下；每单按价格（Prestige）降序 ----------
        List<TrackedOrder> allOrders = new ArrayList<>(be.getTrackedOrders().values());
        allOrders.sort((a, b) -> Integer.compare(b.prestige, a.prestige));
        List<CompoundTag> orderEntries = new ArrayList<>();
        List<CompoundTag> foodEntries = new ArrayList<>();
        int orderIndex = 0;
        for (TrackedOrder order : allOrders) {
            if (!order.state.isActive() && order.state != OrderState.FAILED) continue;
            String remain = order.expiryTick > 0 ? remainTime(order.expiryTick, now) : "--:--";
            String handler = order.handledBy == null || order.handledBy.isEmpty() ? "" : order.handledBy;
            boolean received = handler.length() > 0 || order.counterPos != null;
            int foodGroupIndex = orderIndex++;
            String foodGroupLabel = "★" + order.prestige + " · " + order.customerName;
            CompoundTag orderEntry = new CompoundTag();
            orderEntry.putBoolean("Received", received);
            orderEntry.putInt("Price", order.prestige);
            orderEntry.putString("Customer", order.customerName);
            orderEntry.putString("Remain", remain);
            orderEntry.putString("State", stateLabel(order.state));
            orderEntry.putString("Handler", handler);
            orderEntry.putBoolean("Failed", order.state == OrderState.FAILED);
            orderEntry.putString("FailReason", order.failReason == null ? "" : order.failReason);
            orderEntry.putString("Hover1", "§7订单号: §f" + order.orderId);
            orderEntry.putString("Hover2", "§7菜品: §f" + describeFoods(order.foods));
            orderEntry.putString("Hover3", order.state == OrderState.FAILED
                    ? "§c失败原因: " + order.failReason
                    : "§7顾客: §f" + order.customerName + (order.counterPos == null ? "" : " §8@ " + order.counterPos.toShortString()));
            orderEntries.add(orderEntry);

            // ---------- 食物队列：逐份列出（结构化，GUI 两列排版；不显示坐标） ----------
            List<String> dishIds = new ArrayList<>(order.foods.getAllKeys());
            dishIds.sort(collator::compare);
            Map<String, Integer> onCounterCounts = order.counterPos == null
                    ? new java.util.HashMap<>() : countFoodOnCounter(level, order.counterPos);
            for (String itemId : dishIds) {
                int count = order.foods.getInt(itemId);
                int completedCount = Math.min(count, onCounterCounts.getOrDefault(itemId, 0));
                int remainCount = Math.max(0, count - completedCount);
                List<String> currentNames = order.counterPos == null
                        ? new ArrayList<>() : findAssignedCookNames(level, order.counterPos, itemId);
                List<String> history = order.dishAssignedNames.computeIfAbsent(itemId, k -> new ArrayList<>());
                int activeCount = Math.min(remainCount, currentNames.size());
                for (int i = 0; i < activeCount; i++) {
                    String name = currentNames.get(i);
                    if (name == null || name.isEmpty()) continue;
                    if (i < history.size()) {
                        if (history.get(i) == null || history.get(i).isEmpty()) {
                            history.set(i, name);
                        } else if (!name.equals(history.get(i)) && !history.contains(name)) {
                            history.set(i, name);
                        }
                    } else {
                        while (history.size() < i) {
                            history.add("");
                        }
                        history.add(name);
                    }
                }
                if (history.size() > remainCount) {
                    history.subList(remainCount, history.size()).clear();
                }
                String dishName = itemDisplayName(itemId);
                for (int i = 0; i < remainCount; i++) {
                    CompoundTag foodEntry = new CompoundTag();
                    foodEntry.putString("Dish", dishName);
                    String assignee = "";
                    int flag;
                    if (i < activeCount && currentNames.get(i) != null && !currentNames.get(i).isEmpty()) {
                        flag = 0; // 制作中
                        assignee = currentNames.get(i);
                    } else if (i < history.size() && history.get(i) != null && !history.get(i).isEmpty()) {
                        flag = 1; // 已分配（排队/已完成但未上台）
                        assignee = history.get(i);
                    } else {
                        flag = 2; // 待分配
                    }
                    foodEntry.putString("Assignee", assignee);
                    foodEntry.putInt("Flag", flag);
                    foodEntry.putInt("Order", foodGroupIndex);
                    foodEntry.putString("OrderLabel", foodGroupLabel);
                    foodEntries.add(foodEntry);
                }
                for (int i = 0; i < completedCount; i++) {
                    CompoundTag foodEntry = new CompoundTag();
                    foodEntry.putString("Dish", dishName);
                    foodEntry.putString("Assignee", "");
                    foodEntry.putInt("Flag", 3); // 已完成（在操作台）
                    foodEntry.putInt("Order", foodGroupIndex);
                    foodEntry.putString("OrderLabel", foodGroupLabel);
                    foodEntries.add(foodEntry);
                }
            }
        }
        // 打单机里尚未接单的堂食订单（未接待组）
        if (machine != null) {
            for (ItemStack stack : machine.getItems()) {
                if (stack.isEmpty() || !stack.is(OtcCompat.ORDER())) continue;
                CompoundTag nbt = stack.getTag();
                if (nbt == null || !nbt.contains("FoodList")) continue;
                String queuedOrderId = nbt.getString("OrderId");
                if (queuedOrderId.isEmpty()) continue;
                if (be.getTrackedOrders().containsKey(queuedOrderId)
                        || be.getIgnoredOrderIds().contains(queuedOrderId)) continue;
                if (nbt.getBoolean("Delivery")) continue;
                if (nbt.getBoolean(PlayerOrderManager.PLAYER_ORDER)) continue;
                int queuedPrestige = nbt.contains("Prestige") ? nbt.getInt("Prestige") : 0;
                long queuedExpiry = nbt.contains("ExpiryTick") ? nbt.getLong("ExpiryTick") : -1L;
                CompoundTag entry = new CompoundTag();
                entry.putBoolean("Received", false);
                entry.putInt("Price", queuedPrestige);
                entry.putString("Customer", nbt.getString("CustomerName"));
                entry.putString("Remain", queuedExpiry > 0 ? remainTime(queuedExpiry, now) : "--:--");
                entry.putString("State", "待接单");
                entry.putString("Handler", "");
                entry.putBoolean("Failed", false);
                entry.putString("Hover1", "§7订单号: §f" + queuedOrderId);
                entry.putString("Hover2", "§7菜品: §f" + describeFoods(nbt.getCompound("FoodList")));
                entry.putString("Hover3", "§8[打单机] 尚未接单");
                orderEntries.add(entry);
            }
        }
        // 已到店但尚未被接待的顾客（未接待组）
        if (machine != null) {
            try {
                for (String waitingCustomer : com.icewolf.maidrestaurant.automation.core.WalkInGreetBridge
                        .waitingWalkInCustomerNames(level, machinePos)) {
                    CompoundTag entry = new CompoundTag();
                    entry.putBoolean("Received", false);
                    entry.putInt("Price", -1);
                    entry.putString("Customer", waitingCustomer);
                    entry.putString("Remain", "--:--");
                    entry.putString("State", "待接待");
                    entry.putString("Handler", "");
                    entry.putBoolean("Failed", false);
                    entry.putString("Hover1", "§7该顾客尚未被侍者接待，接待后才会生成订单");
                    entry.putString("Hover2", "§7（自动接待已开启，侍者会尽快前往）");
                    orderEntries.add(entry);
                }
            } catch (Throwable ignored) {
            }
        }
        // 食物队列：按订单分组输出（组序 = 订单队列顺序）；组内顺序即 制作中→已分配→待分配→已完成，不做全局排序
        if (foodEntries.isEmpty()) {
            CompoundTag placeholder = new CompoundTag();
            placeholder.putString("Dish", "暂无待制作食物");
            placeholder.putString("Assignee", "");
            placeholder.putInt("Flag", 2);
            placeholder.putInt("Order", 0);
            placeholder.putString("OrderLabel", "");
            foodEntries.add(placeholder);
        }
        // 订单队列：已接待在前、未接待在后；组内按价格降序
        orderEntries.sort((a, b) -> {
            int ra = a.getBoolean("Received") ? 0 : 1;
            int rb = b.getBoolean("Received") ? 0 : 1;
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(b.getInt("Price"), a.getInt("Price"));
        });
        if (orderEntries.isEmpty()) {
            CompoundTag placeholder = new CompoundTag();
            placeholder.putBoolean("Received", false);
            placeholder.putInt("Price", -1);
            placeholder.putString("Customer", "暂无订单");
            placeholder.putString("State", "");
            orderEntries.add(placeholder);
        }
        be.setStructuredSnapshot(ownerName.isEmpty() ? "" : ownerName, ownerNames, maidEntries, staffFooter,
                orderEntries, foodEntries);
    }

    /** 悬浮信息用的菜品清单（最多列 5 种，超出用"等 N 种"）。 */
    private static String describeFoods(CompoundTag foods) {
        if (foods == null || foods.isEmpty()) return "无";
        List<String> ids = new ArrayList<>(foods.getAllKeys());
        ids.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String itemId : ids) {
            if (shown >= 5) {
                sb.append(" 等").append(ids.size()).append("种");
                break;
            }
            if (shown > 0) sb.append("、");
            sb.append(itemDisplayName(itemId)).append("x").append(foods.getInt(itemId));
            shown++;
        }
        return sb.toString();
    }

    private static String dishLine(TrackedOrder order, String dishName) {
        String assigned = order.assignedMaidName == null || order.assignedMaidName.isEmpty()
                ? "§8待分配" : "§a" + order.assignedMaidName;
        return "§b" + dishName + " §7→ " + assigned;
    }

    private static String stateLabel(OrderState state) {
        return switch (state) {
            case QUEUED -> "排队中";
            case PLACED -> "已入台";
            case COOKING -> "制作中";
            case READY -> "待上菜";
            case DELEGATED_TO_WAITER -> "已派侍者";
            case DELIVERING -> "送餐中";
            case SERVED -> "已上菜";
            case CLOSED -> "已完成";
            case FAILED -> "已失败";
        };
    }

    private static String remainTime(long expiryTick, long now) {
        long remain = Math.max(0L, expiryTick - now) / 20L;
        return String.format("剩余 %d:%02d", remain / 60, remain % 60);
    }

    private static String shortUid(String uid) {
        if (uid == null) return "";
        int idx = uid.indexOf(':');
        return idx >= 0 ? uid.substring(idx + 1) : uid;
    }

    private static String itemDisplayName(String itemId) {
        try {
            ResourceLocation id = new ResourceLocation(itemId);
            var item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null) return Component.translatable(item.getDescriptionId()).getString();
            return itemId;
        } catch (Throwable t) {
            return itemId;
        }
    }

    private static String resolveOwnerName(ServerLevel level, EntityMaid maid) {
        try {
            UUID ownerUuid = maid.getOwnerUUID();
            if (ownerUuid == null) return "";
            var player = level.getServer() == null ? null
                    : level.getServer().getPlayerList().getPlayer(ownerUuid);
            if (player != null) return player.getGameProfile().getName();
            return level.getServer() == null ? "" : level.getServer().getProfileCache()
                    .get(ownerUuid).map(profile -> profile.getName()).orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================
    // 工具
    // ================================================================

    private static long backoff(int retry) {
        int n = Math.max(0, Math.min(retry, 4));
        return BASE_BACKOFF * (1L << n);
    }

    private static BlockPos findPlacementCounter(DispatcherBlockEntity be, ServerLevel level, BlockPos machinePos) {
        BusinessManager manager = com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.getManager();
        Set<Long> used = new HashSet<>();
        for (TrackedOrder order : be.getTrackedOrders().values()) {
            if (order.counterPos != null && order.state.isActive()) {
                used.add(order.counterPos.asLong());
            }
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos counter : OrderBridge.scanCountersAround(level, machinePos)) {
            if (used.contains(counter.asLong())) continue;
            if (!OrderBridge.isCounterFree(level, counter, manager)) continue;
            double dist = counter.distSqr(machinePos);
            if (dist < bestDist) {
                bestDist = dist;
                best = counter.immutable();
            }
        }
        return best;
    }

    private static ItemStack findMachineOrderStack(OrderMachineBlockEntity machine, String orderId) {
        for (ItemStack stack : machine.getItems()) {
            if (stack.isEmpty() || !stack.is(OtcCompat.ORDER())) continue;
            CompoundTag tag = stack.getTag();
            if (tag != null && orderId.equals(tag.getString("OrderId"))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static CompoundTag enrichOrderNbt(ServerLevel level, OrderMachineBlockEntity machine,
                                              BlockPos machinePos, int machineId,
                                              String orderId, CompoundTag source) {
        CompoundTag nbt = source == null ? new CompoundTag() : source.copy();
        try {
            nbt.putInt("order_machine.id", machineId);
            nbt.putLong("order_machine.pos_long", machinePos.asLong());
            nbt.putString("order_machine.dimension", level.dimension().location().toString());
            if (!nbt.contains("OrderId")) nbt.putString("OrderId", orderId);
            if (!nbt.contains("order_machine.level")) {
                nbt.putInt("order_machine.level", machine.getRestaurantLevel());
            }
        } catch (Throwable ignored) {
        }
        return nbt;
    }

    private static ItemStack readCounterOrder(ServerLevel level, BlockPos counter) {
        if (level == null || counter == null || !level.isLoaded(counter)) return ItemStack.EMPTY;
        if (!(level.getBlockEntity(counter) instanceof cn.breezeth.ordertocook.block.entity.TakeoutBoxBlockEntity)) {
            return ItemStack.EMPTY;
        }
        IItemHandler handler = OrderBridge.getItemHandler(level.getBlockEntity(counter));
        if (handler == null || handler.getSlots() <= 0) return ItemStack.EMPTY;
        ItemStack stack = handler.getStackInSlot(0);
        if (stack.isEmpty() || !stack.is(OtcCompat.ORDER())) return ItemStack.EMPTY;
        return stack;
    }

    private static void clearCounterOrder(ServerLevel level, BlockPos counter) {
        try {
            IItemHandler handler = OrderBridge.getItemHandler(level.getBlockEntity(counter));
            if (handler != null) {
                handler.extractItem(0, 64, false);
            }
        } catch (Throwable t) {
            com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.warn(
                    "清理操作台订单失败 counter={}", counter, t);
        }
    }

    private static boolean isPlateAbove(ServerLevel level, BlockPos counter) {
        return level.getBlockEntity(counter.above()) instanceof FoodPlateBlockEntity;
    }

    /** 统计操作台上各菜品的份数（跳过订单槽 0；用于食物队列显示"已完成（在操作台）"）。 */
    private static Map<String, Integer> countFoodOnCounter(ServerLevel level, BlockPos counter) {
        Map<String, Integer> counts = new java.util.HashMap<>();
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

    private static boolean isAllFoodReady(ServerLevel level, BlockPos counter, CompoundTag foods) {
        if (foods == null || foods.isEmpty()) return false;
        IItemHandler handler = OrderBridge.getItemHandler(level.getBlockEntity(counter));
        if (handler == null) return false;
        Map<String, Integer> onCounter = new HashMap<>();
        for (int slot = 1; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) {
                onCounter.merge(id.toString(), stack.getCount(), Integer::sum);
            }
        }
        for (String itemId : foods.getAllKeys()) {
            int need = foods.getInt(itemId);
            if (need <= 0) continue;
            if (onCounter.getOrDefault(itemId, 0) < need) return false;
        }
        return true;
    }

    /**
     * 查询某道菜当前被哪位厨师认领制作（按菜品精确匹配，修复"整单所有食物都指向同一个女仆"）。
     *
     * <p>优先用烹饪请求 extraData 里的 BusinessItemId（我方写入的目标产物），
     * 没有再回退到按配方 ID 解析产物。</p>
     */
    private static List<String> findAssignedCookNames(ServerLevel level, BlockPos counter, String itemId) {
        List<String> names = new ArrayList<>();
        if (level == null || counter == null || itemId == null || itemId.isEmpty()) return names;
        try {
            for (EntityMaid maid : TaskManager.getInstance().getCachedMaids(level)) {
                var request = RequestManager.peek(maid, 0);
                if (!(request instanceof CookRequest cookRequest)) continue;
                if (cookRequest.extraData == null || !cookRequest.extraData.contains("BusinessCounter")) continue;
                if (cookRequest.extraData.getLong("BusinessCounter") != counter.asLong()) continue;
                String businessItem = cookRequest.extraData.getString("BusinessItemId");
                String dishId = businessItem.isEmpty() ? resolveRecipeOutputId(level, cookRequest.id) : businessItem;
                if (!itemId.equals(dishId)) continue;
                // 一个烹饪请求 = 该女仆一次处理能覆盖的份数（普通锅为 1，多槽批量厨具可能 >1）
                int capacity = Math.max(1, Math.min(cookRequest.requested, 8));
                for (int i = 0; i < capacity; i++) {
                    names.add(maid.getName().getString());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private static String resolveRecipeOutputId(ServerLevel level, ResourceLocation recipeId) {
        try {
            if (recipeId == null) return "";
            var optional = level.getRecipeManager().byKey(recipeId);
            if (optional.isEmpty()) return "";
            ItemStack result = optional.get().getResultItem(level.registryAccess());
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(result.getItem());
            return key == null ? "" : key.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}