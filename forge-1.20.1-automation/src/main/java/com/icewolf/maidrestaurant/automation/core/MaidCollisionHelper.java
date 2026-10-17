package com.icewolf.maidrestaurant.automation.core;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;

/**
 * 女仆碰撞优化：正在上岗（厨师 / 侍者等餐厅职业）的女仆无视其它实体的碰撞与推挤。
 *
 * <p>问题：餐厅里多只女仆同时去取菜 / 去工位时经常互相卡住（互相推挤、被同伴挡住）。
 * 处理：把"当前任务是餐厅职业"的女仆标记为<b>不参与实体碰撞</b>——
 * 其它实体可以穿过她、她也不会被推挤，从而在多女仆并行时不再互相卡位。
 * 方块碰撞（墙、门）不受影响。</p>
 */
public final class MaidCollisionHelper {

    private MaidCollisionHelper() {
    }

    /** 该实体是否是"上岗中的餐厅女仆"（当前任务为厨师 / 侍者 / 已注册的扩展餐厅职业）。 */
    public static boolean ignoresEntityCollision(Entity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            return false;
        }
        try {
            IMaidTask task = maid.getTask();
            if (task == null) {
                return false;
            }
            String uid = task.getUid() == null ? "" : task.getUid().toString();
            if (MaidUtils.TASK_COOK.equals(uid) || MaidUtils.TASK_WAITER.equals(uid)) {
                return true;
            }
            // 扩展餐厅职业（茶博士 / 调酒师等）：能识别为餐厅职业的也算上岗
            return StationSystem.professionOf(maid) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
