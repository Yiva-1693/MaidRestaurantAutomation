package com.icewolf.maidrestaurant.automation.core;

import cn.breezeth.ordertocook.api.OrderLifecycleApi;
import com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTC 公开订单生命周期事件桥（硬依赖 Order To Cook 1.3.6+）。
 *
 * <p>开发文档 3.3：订单完成 / 失败感知。OTC 1.3.6 提供公开的 {@link OrderLifecycleApi}，
 * 因此无需任何 mixin，直接注册监听器，把事件放进待处理队列，由调度显示器在下一个评估周期消费。</p>
 */
public final class DispatcherOrderEvents {

    private static final Map<ResourceKeyHolder, List<OrderLifecycleApi.Event>> PENDING = new ConcurrentHashMap<>();
    private static boolean registered;

    private DispatcherOrderEvents() {
    }

    /** 在 mod 构造阶段调用一次。 */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        try {
            OrderLifecycleApi.register(new ResourceLocation(MaidRestaurantAutomation.MOD_ID, "dispatcher"), 0,
                    DispatcherOrderEvents::onEvent);
            MaidRestaurantAutomation.LOGGER.info("调度显示器已注册 OTC 订单生命周期监听器");
        } catch (Throwable t) {
            MaidRestaurantAutomation.LOGGER.error("注册 OTC 订单生命周期监听器失败（订单完成感知将降级为观察模式）", t);
        }
    }

    private static void onEvent(OrderLifecycleApi.Event event) {
        if (event == null || event.world() == null || event.order() == null) return;
        try {
            PENDING.computeIfAbsent(new ResourceKeyHolder(event.world().dimension().toString(), event.order().machineId()),
                    k -> new ArrayList<>()).add(event);
        } catch (Throwable ignored) {
        }
    }

    /** 取出并清空某维度、某打单机的待处理事件。 */
    public static List<OrderLifecycleApi.Event> drain(ServerLevel level, int machineId) {
        if (level == null) return List.of();
        List<OrderLifecycleApi.Event> list = PENDING.remove(new ResourceKeyHolder(level.dimension().toString(), machineId));
        return list == null ? List.of() : list;
    }

    public static void clearRuntimeState() {
        PENDING.clear();
    }

    private record ResourceKeyHolder(String dimension, int machineId) {
    }
}
