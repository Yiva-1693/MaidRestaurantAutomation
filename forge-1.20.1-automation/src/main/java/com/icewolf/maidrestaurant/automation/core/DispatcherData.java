package com.icewolf.maidrestaurant.automation.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * 调度显示器持久化数据结构：显式订单状态机 + 工位绑定表。
 *
 * <p>开发文档 T3 / T4：调度显示器 BE 上的 {@code OrderStateMachine} 完整状态流转，
 * 以及 {@code workstationBindings} 的原子认领 / 释放 / 失效检测 / 级联释放。</p>
 */
public final class DispatcherData {

    private DispatcherData() {
    }

    /** 订单状态机（开发文档 5.4 / 5.5 的状态集合）。 */
    public enum OrderState {
        /** 已进入调度显示器队列，等待分配到操作台。 */
        QUEUED,
        /** 订单已原子写入某操作台的订单槽，顾客已生成。 */
        PLACED,
        /** 正在制作（已发布烹饪委托或库存搬运中）。 */
        COOKING,
        /** 全部菜品已就位，等待装盘/上菜。 */
        READY,
        /** 已发布上菜委托给侍者。 */
        DELEGATED_TO_WAITER,
        /** 侍者正在送餐。 */
        DELIVERING,
        /** 已上菜（OTC 结算完成）。 */
        SERVED,
        /** 订单闭环。 */
        CLOSED,
        /** 失败（超时 / 顾客消失 / 重试超限）。 */
        FAILED;

        public boolean isTerminal() {
            return this == CLOSED || this == FAILED;
        }

        public boolean isActive() {
            return this != CLOSED && this != FAILED;
        }
    }

    /** 调度显示器跟踪的一张堂食订单。 */
    public static final class TrackedOrder {
        public String orderId = "";
        public String customerName = "";
        public int machineId = -1;
        public boolean walkIn;
        public long expiryTick = -1L;
        public long createTick;
        public long lastChangeTick;
        public long nextRetryTick;
        public int retryCount;
        public int stageRetryCount;
        public OrderState state = OrderState.QUEUED;
        public BlockPos counterPos;
        public UUID assignedMaid;
        public String assignedMaidName = "";
        public String failReason = "";
        public int prestige;
        /** 正在处理该订单的侍者/来源（订单队列显示用）。 */
        public String handledBy = "";
        public CompoundTag foods = new CompoundTag();
        /**
         * 每道菜的"逐份厨师分配历史"（itemId → 按份序的厨师名列表，长度 = 菜品需求份数）。
         *
         * <p>烹饪请求是瞬时的（做完就出队），只在界面上读取"当前请求"会导致同一道菜的多份
         * 一会儿显示 A、刷新后又显示 B（看起来像随机选一个）。这里按份累积记录第一次观察到
         * 的厨师，保证"3 份菜 = 3 个女仆"能稳定分别显示。</p>
         */
        public final java.util.Map<String, java.util.List<String>> dishAssignedNames = new java.util.HashMap<>();

        public int totalFoodCount() {
            int n = 0;
            for (String key : foods.getAllKeys()) {
                n += foods.getInt(key);
            }
            return n;
        }

        public boolean hasFood(String itemId) {
            return foods.contains(itemId) && foods.getInt(itemId) > 0;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("OrderId", orderId);
            tag.putString("CustomerName", customerName);
            tag.putInt("MachineId", machineId);
            tag.putBoolean("WalkIn", walkIn);
            tag.putLong("ExpiryTick", expiryTick);
            tag.putLong("CreateTick", createTick);
            tag.putLong("LastChangeTick", lastChangeTick);
            tag.putLong("NextRetryTick", nextRetryTick);
            tag.putInt("RetryCount", retryCount);
            tag.putInt("StageRetryCount", stageRetryCount);
            tag.putString("State", state.name());
            if (counterPos != null) tag.putLong("CounterPos", counterPos.asLong());
            if (assignedMaid != null) tag.putUUID("AssignedMaid", assignedMaid);
            tag.putString("AssignedMaidName", assignedMaidName);
            tag.putString("FailReason", failReason);
            tag.putInt("Prestige", prestige);
            tag.putString("HandledBy", handledBy);
            tag.put("Foods", foods.copy());
            return tag;
        }

        public static TrackedOrder load(CompoundTag tag) {
            TrackedOrder o = new TrackedOrder();
            o.orderId = tag.getString("OrderId");
            o.customerName = tag.getString("CustomerName");
            o.machineId = tag.getInt("MachineId");
            o.walkIn = tag.getBoolean("WalkIn");
            o.expiryTick = tag.getLong("ExpiryTick");
            o.createTick = tag.getLong("CreateTick");
            o.lastChangeTick = tag.getLong("LastChangeTick");
            o.nextRetryTick = tag.getLong("NextRetryTick");
            o.retryCount = tag.getInt("RetryCount");
            o.stageRetryCount = tag.getInt("StageRetryCount");
            try {
                o.state = OrderState.valueOf(tag.getString("State"));
            } catch (Throwable t) {
                o.state = OrderState.QUEUED;
            }
            // 区块卸载 / 服务器重启后：所有非终态中间态降级回 QUEUED（开发文档 5.5 状态自愈）
            if (!o.state.isTerminal() && o.state != OrderState.QUEUED) {
                o.state = OrderState.QUEUED;
                o.counterPos = null;
                o.assignedMaid = null;
                o.assignedMaidName = "";
            }
            if (tag.contains("CounterPos")) o.counterPos = BlockPos.of(tag.getLong("CounterPos"));
            if (tag.hasUUID("AssignedMaid")) o.assignedMaid = tag.getUUID("AssignedMaid");
            o.assignedMaidName = tag.getString("AssignedMaidName");
            o.failReason = tag.getString("FailReason");
            o.prestige = tag.getInt("Prestige");
            o.handledBy = tag.getString("HandledBy");
            o.foods = tag.getCompound("Foods").copy();
            return o;
        }
    }

    /** 工位绑定（女仆 ↔ 工作方块 + 板凳）。 */
    public static final class WorkstationBinding {
        public UUID maidUuid;
        public String maidName = "";
        public String profession = "";
        public String stationUid = "";
        public BlockPos stationPos;
        public BlockPos seatPos;
        public long bindTick;
        public boolean firstClaimNotified;
        /** 失效状态（工作方块被拆 / 板凳被占）：暂停接单，等待重新认领。 */
        public boolean invalid;
        /** 上次重新认领尝试的 tick（每 5 秒一次，避免刷屏）。 */
        public long lastReclaimTick;
        /** 失效提示是否已发过（避免反复气泡）。 */
        public boolean invalidNotified;
        /** 实体暂时找不到（区块/实体未加载）的起始 tick；超过宽限期才真正解绑。 */
        public long missingSinceTick;

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            if (maidUuid != null) tag.putUUID("Maid", maidUuid);
            tag.putString("MaidName", maidName);
            tag.putString("Profession", profession);
            tag.putString("StationUid", stationUid);
            if (stationPos != null) tag.putLong("StationPos", stationPos.asLong());
            if (seatPos != null) tag.putLong("SeatPos", seatPos.asLong());
            tag.putLong("BindTick", bindTick);
            tag.putBoolean("FirstClaimNotified", firstClaimNotified);
            return tag;
        }

        public static WorkstationBinding load(CompoundTag tag) {
            WorkstationBinding b = new WorkstationBinding();
            if (tag.hasUUID("Maid")) b.maidUuid = tag.getUUID("Maid");
            b.maidName = tag.getString("MaidName");
            b.profession = tag.getString("Profession");
            b.stationUid = tag.getString("StationUid");
            if (tag.contains("StationPos")) b.stationPos = BlockPos.of(tag.getLong("StationPos"));
            if (tag.contains("SeatPos")) b.seatPos = BlockPos.of(tag.getLong("SeatPos"));
            b.bindTick = tag.getLong("BindTick");
            b.firstClaimNotified = tag.getBoolean("FirstClaimNotified");
            return b;
        }

        public boolean isValidFor(EntityMaidHolder holder) {
            return holder != null;
        }

        public boolean isComplete() {
            return maidUuid != null && stationPos != null;
        }
    }

    /** 给绑定判断用的轻量占位接口（避免在数据类里直接依赖 EntityMaid）。 */
    public interface EntityMaidHolder {
        UUID getMaidUUID();
    }

    /** 员工总览的一条记录（服务端生成，客户端只渲染）。 */
    public static final class StaffEntry {
        public String name = "";
        public String role = "";
        public String detail = "";
        public int sortGroup; // 0=店长 1=玩家 2=女仆

        public String toLine() {
            return role + " " + name + (detail.isEmpty() ? "" : "  " + detail);
        }
    }

    /** 操作台订单队列里的一条菜品。 */
    public static final class QueueEntry {
        public BlockPos counterPos;
        public String counterLabel = "";
        public String dishName = "";
        public int count;
        public String waiterName = "";
        public boolean expired;
    }

    public static ListTag saveOrders(Iterable<TrackedOrder> orders) {
        ListTag list = new ListTag();
        for (TrackedOrder order : orders) {
            list.add(order.save());
        }
        return list;
    }

    public static java.util.List<TrackedOrder> loadOrders(ListTag list) {
        java.util.List<TrackedOrder> result = new java.util.ArrayList<>();
        if (list == null) return result;
        for (Tag tag : list) {
            if (tag instanceof CompoundTag compound) {
                result.add(TrackedOrder.load(compound));
            }
        }
        return result;
    }

    public static ListTag saveBindings(Iterable<WorkstationBinding> bindings) {
        ListTag list = new ListTag();
        for (WorkstationBinding binding : bindings) {
            if (binding != null && binding.isComplete()) {
                list.add(binding.save());
            }
        }
        return list;
    }

    public static java.util.List<WorkstationBinding> loadBindings(ListTag list) {
        java.util.List<WorkstationBinding> result = new java.util.ArrayList<>();
        if (list == null) return result;
        for (Tag tag : list) {
            if (tag instanceof CompoundTag compound) {
                result.add(WorkstationBinding.load(compound));
            }
        }
        return result;
    }
}
