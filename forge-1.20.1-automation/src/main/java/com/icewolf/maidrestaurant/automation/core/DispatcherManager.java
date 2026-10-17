package com.icewolf.maidrestaurant.automation.core;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 调度显示器的服务端发现 / 缓存 / 供其它 Bridge 查询工位绑定的静态入口。
 *
 * <p>开发文档 T3/T4：调度显示器方块是餐厅自动化的唯一控制与观测中心。
 * 其它自动化 Bridge（烹饪 / 上菜 / 打包）通过本类查询"该女仆是否已认领工位"、"她绑定的具体设备"，
 * 实现「未认领工位 = 不可接单」与「只在自己的工位干活」。</p>
 */
public final class DispatcherManager {

    private static final Map<String, List<DispatcherBlockEntity>> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHE_TICK = new HashMap<>();
    private static final long CACHE_INTERVAL = 20L;

    private DispatcherManager() {
    }

    /** 由 BusinessManager 每 10 tick 调用一次（每个维度）。 */
    public static void tick(ServerLevel level, BusinessManager manager) {
        if (level == null) return;
        List<DispatcherBlockEntity> dispatchers = getDispatchers(level);
        for (DispatcherBlockEntity dispatcher : dispatchers) {
            try {
                DispatcherLogic.tick(dispatcher, level);
            } catch (Throwable t) {
                com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.error(
                        "调度显示器 tick 出错 @ {}", dispatcher.getBlockPos(), t);
            }
        }
    }

    /** 获取本维度所有已加载的调度显示器（带 1 秒缓存，避免每 tick 全维度扫描）。 */
    public static List<DispatcherBlockEntity> getDispatchers(ServerLevel level) {
        String key = level.dimension().location().toString();
        long now = level.getGameTime();
        Long last = CACHE_TICK.get(key);
        if (last != null && now - last < CACHE_INTERVAL) {
            List<DispatcherBlockEntity> cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        List<DispatcherBlockEntity> found = new ArrayList<>();
        try {
            for (BlockPos pos : WorldScanner.scan(level, DispatcherBlockEntity.class)) {
                if (level.getBlockEntity(pos) instanceof DispatcherBlockEntity dispatcher) {
                    found.add(dispatcher);
                }
            }
        } catch (Throwable t) {
            com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.debug(
                    "扫描调度显示器失败: {}", t.toString());
        }
        CACHE.put(key, found);
        CACHE_TICK.put(key, now);
        return found;
    }

    /** 找到绑定了指定打单机的调度显示器。 */
    public static DispatcherBlockEntity findForMachine(ServerLevel level, BlockPos machinePos) {
        if (level == null || machinePos == null) return null;
        for (DispatcherBlockEntity dispatcher : getDispatchers(level)) {
            BlockPos bound = dispatcher.getBoundMachinePos();
            if (bound != null && bound.equals(machinePos)) return dispatcher;
        }
        return null;
    }

    /**
     * 查询女仆在某打单机餐厅里的工位绑定。返回 null = 未认领（应跳过该女仆）。
     */
    public static DispatcherData.WorkstationBinding findBinding(ServerLevel level, BlockPos machinePos, UUID maidUuid) {
        DispatcherBlockEntity dispatcher = findForMachine(level, machinePos);
        if (dispatcher == null || maidUuid == null) return null;
        DispatcherData.WorkstationBinding binding = dispatcher.getBindingsMap().get(maidUuid);
        if (binding == null || binding.invalid || binding.stationPos == null) return null;
        return binding;
    }

    /**
     * 取女仆绑定的、且厨具类型匹配的工位坐标。
     *
     * @param requiredUid 配方所需厨具 UID（空字符串表示不校验类型）
     * @return null 表示该女仆不参与此任务（未认领 / 绑定的不是所需厨具类型）
     */
    public static BlockPos findBoundStation(ServerLevel level, BlockPos machinePos, UUID maidUuid, String requiredUid) {
        DispatcherData.WorkstationBinding binding = findBinding(level, machinePos, maidUuid);
        if (binding == null) return null;
        if (requiredUid != null && !requiredUid.isEmpty()
                && !requiredUid.equals(binding.stationUid)) {
            return null;
        }
        return binding.stationPos;
    }

    /** 女仆是否已在本餐厅认领工位（未认领 = 不可接单）。 */
    public static boolean isMaidClaimed(ServerLevel level, BlockPos machinePos, EntityMaid maid) {
        return maid != null && findBinding(level, machinePos, maid.getUUID()) != null;
    }

    /** 女仆当前绑定的工位坐标（忽略厨具类型）。 */
    public static BlockPos findBoundStationAny(ServerLevel level, BlockPos machinePos, UUID maidUuid) {
        DispatcherData.WorkstationBinding binding = findBinding(level, machinePos, maidUuid);
        return binding == null ? null : binding.stationPos;
    }

    public static void clearRuntimeState() {
        CACHE.clear();
        CACHE_TICK.clear();
        DispatcherOrderEvents.clearRuntimeState();
    }
}
