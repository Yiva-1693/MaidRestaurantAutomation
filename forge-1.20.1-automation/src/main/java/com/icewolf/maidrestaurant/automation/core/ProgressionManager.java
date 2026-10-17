package com.icewolf.maidrestaurant.automation.core;

import cn.breezeth.ordertocook.block.entity.OrderMachineBlockEntity;
import com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation;
import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 等级门槛。
 *
 * <p>开发文档核心决策：「调度显示器无等级，放置即全功能」。因此新 mod 中所有等级闸门一律放行，
 * 保留本类与原有调用点仅为最小化改动面（避免大范围删除调用点引入遗漏），
 * 打单机自身的 OTC 等级只影响订单内容与收益，不再限制自动化能力。</p>
 *
 * <p>同时移除原「每店员工上限 = 等级 + 1」的限制（与工位制多员工直接冲突），
 * 员工上限改由工位数量本身决定。</p>
 */
public final class ProgressionManager {
    public static final int LEVEL_DELIVERY = 0;
    public static final int LEVEL_COOK_AND_PREP = 1;
    public static final int LEVEL_DISHWASHING = 2;
    public static final int LEVEL_AUTO_ORDER = 3;
    public static final int LEVEL_AUTO_PRE_COOK = 4;

    /** 工位数上限（实际由认领到的工位数决定，这里只给一个足够大的安全上限）。 */
    private static final int MAX_WORKERS_UNCAPPED = 64;

    private ProgressionManager() {
    }

    public static int getRestaurantLevel(ServerLevel level, BlockPos machinePos) {
        BlockEntity be = level.getBlockEntity(machinePos);
        if (be instanceof OrderMachineBlockEntity machine) {
            String[] fieldNames = {"restaurantLevel", "level"};
            for (String fieldName : fieldNames) {
                try {
                    Field f = machine.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object result = f.get(machine);
                    if (result instanceof Integer value) {
                        return value;
                    }
                }
                catch (Throwable ignored) {
                }
            }
            MaidRestaurantAutomation.LOGGER.debug(
                    "Progression: failed to get restaurant level for machine at {}", machinePos);
        }
        return 0;
    }

    /** 等级闸门：调度显示器无等级，放置即全功能 → 恒为解锁。 */
    public static boolean isUnlocked(ServerLevel level, BlockPos machinePos, int requiredLevel) {
        return true;
    }

    public static boolean isDeliveryUnlocked(ServerLevel level, BlockPos machinePos) {
        return true;
    }

    public static boolean isCookAndPrepUnlocked(ServerLevel level, BlockPos machinePos) {
        return true;
    }

    public static boolean isDishwashingUnlocked(ServerLevel level, BlockPos machinePos) {
        return true;
    }

    public static boolean isAutoOrderUnlocked(ServerLevel level, BlockPos machinePos) {
        return true;
    }

    public static boolean isAutoPreCookingUnlocked(ServerLevel level, BlockPos machinePos) {
        return true;
    }

    /** 员工上限：工位制下由认领到的工位数量决定，不再按打单机等级限制。 */
    public static int getMaxWorkers(ServerLevel level, BlockPos machinePos) {
        return MAX_WORKERS_UNCAPPED;
    }
}
