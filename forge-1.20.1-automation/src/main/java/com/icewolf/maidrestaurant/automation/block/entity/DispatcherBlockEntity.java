package com.icewolf.maidrestaurant.automation.block.entity;

import com.icewolf.maidrestaurant.automation.core.DispatcherData;
import com.icewolf.maidrestaurant.automation.core.DispatcherLogic;
import com.icewolf.maidrestaurant.automation.menu.DispatcherMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 餐厅调度显示器方块实体。
 *
 * <p>开发文档 二 / 4.3①：调度显示器是餐厅自动化唯一控制与观测中心，主动绑定一台未被绑定的打单机，
 * 持有订单状态机、工位绑定表、重试计数、员工总览快照、操作台订单队列。</p>
 *
 * <p>状态完整持久化到 NBT；区块卸载 / 服务器重启后中间态订单降级回 {@code QUEUED}（见
 * {@link DispatcherData.TrackedOrder#load(CompoundTag)}），由 {@link DispatcherLogic} 重新调度。</p>
 */
public class DispatcherBlockEntity extends BlockEntity implements MenuProvider {

    public static final int STATUS_UNBOUND = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_MACHINE_LOST = 2;
    public static final int STATUS_NO_STAFF = 3;

    private static final int BIND_RADIUS = 16;
    private static final int WORK_RADIUS = 24;

    @Nullable
    private BlockPos boundMachinePos;
    private int boundMachineId = -1;
    /** 调度显示器观测范围内的打单机丢失提示时间（避免刷屏）。 */
    private long lastMachineLostNotifyTick = -1L;

    private final Map<String, DispatcherData.TrackedOrder> trackedOrders = new LinkedHashMap<>();
    private final Map<UUID, DispatcherData.WorkstationBinding> bindings = new LinkedHashMap<>();
    private final Map<Long, UUID> stationOwners = new LinkedHashMap<>();
    /** 已失败 / 已闭环的订单 ID，避免重复采用（内存态，重启后自然清空）。 */
    private final java.util.Set<String> ignoredOrderIds = new java.util.HashSet<>();

    private int statusMode = STATUS_UNBOUND;

    // ===== 客户端展示快照（服务端生成，随方块更新包同步） =====
    private final List<String> headerLines = new ArrayList<>();
    private final List<String> noticeLines = new ArrayList<>();
    private final List<String> staffLines = new ArrayList<>();
    private final List<String> queueLines = new ArrayList<>();
    /** 员工总览：店长（单独一行居中，由 GUI 排版）。 */
    private String bossName = "";
    /** 员工总览：其他玩家（女仆主人）名单，GUI 横向排列。 */
    private final List<String> playerNames = new ArrayList<>();
    /** 员工总览：女仆条目（Name / Profession / Owner / Station / Claimed），GUI 悬浮表格展示后三项。 */
    private final List<CompoundTag> maidEntries = new ArrayList<>();
    /** 员工总览底部统计（另有 N 名女仆未认领工位）。 */
    private String staffFooter = "";
    /** 订单队列：以订单为单位的展示条目（Text / Hover1-3），按价格降序。 */
    private final List<CompoundTag> orderEntries = new ArrayList<>();
    /** 食物队列：不分订单，只逐份显示食物与对应厨师。 */
    private final List<CompoundTag> foodEntries = new ArrayList<>();

    private long lastEvaluateTick = -1L;
    private long lastBindMaintainTick = -1L;
    private long lastOrderMaintainTick = -1L;
    private long lastSnapshotTick = -1L;

    public DispatcherBlockEntity(BlockPos pos, BlockState state) {
        super(com.icewolf.maidrestaurant.automation.registry.ModBlockEntities.DISPATCHER.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DispatcherBlockEntity entity) {
        if (level.isClientSide) return;
        if (level instanceof ServerLevel serverLevel) {
            // 防御：任何调度逻辑异常都不允许把异常抛进区块 ticker（否则会直接崩服/崩客户端集成服务器）。
            try {
                DispatcherLogic.tick(entity, serverLevel);
            } catch (Throwable t) {
                com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation.LOGGER.error(
                        "调度显示器 tick 异常 @ {}", pos, t);
            }
        }
    }

    // ===================== 服务端逻辑访问器 =====================

    public Map<String, DispatcherData.TrackedOrder> getTrackedOrders() {
        return trackedOrders;
    }

    public Map<UUID, DispatcherData.WorkstationBinding> getBindingsMap() {
        return bindings;
    }

    public Map<Long, UUID> getStationOwners() {
        return stationOwners;
    }

    public java.util.Set<String> getIgnoredOrderIds() {
        return ignoredOrderIds;
    }

    public long getLastEvaluateTick() {
        return lastEvaluateTick;
    }

    public void setLastEvaluateTick(long tick) {
        this.lastEvaluateTick = tick;
    }

    public long getLastBindMaintainTick() {
        return lastBindMaintainTick;
    }

    public void setLastBindMaintainTick(long tick) {
        this.lastBindMaintainTick = tick;
    }

    public long getLastOrderMaintainTick() {
        return lastOrderMaintainTick;
    }

    public void setLastOrderMaintainTick(long tick) {
        this.lastOrderMaintainTick = tick;
    }

    public long getLastSnapshotTick() {
        return lastSnapshotTick;
    }

    public void setLastSnapshotTick(long tick) {
        this.lastSnapshotTick = tick;
    }

    public long getLastMachineLostNotifyTick() {
        return lastMachineLostNotifyTick;
    }

    public void setLastMachineLostNotifyTick(long tick) {
        this.lastMachineLostNotifyTick = tick;
    }

    public int getWorkRadius() {
        return WORK_RADIUS;
    }

    public int getBindRadius() {
        return BIND_RADIUS;
    }

    public void setBoundMachine(BlockPos pos, int machineId) {
        this.boundMachinePos = pos == null ? null : pos.immutable();
        this.boundMachineId = machineId;
        this.setChanged();
        this.syncToClient();
    }

    public void clearBoundMachine() {
        this.boundMachinePos = null;
        this.boundMachineId = -1;
        this.setChanged();
        this.syncToClient();
    }

    public int getBoundMachineId() {
        return boundMachineId;
    }

    public void setBoundMachineId(int id) {
        this.boundMachineId = id;
    }

    // ===================== GUI / 展示快照 =====================

    public boolean hasBoundMachine() {
        return boundMachinePos != null;
    }

    @Nullable
    public BlockPos getBoundMachinePos() {
        return boundMachinePos;
    }

    public int getStatusMode() {
        return statusMode;
    }

    public void setStatusMode(int statusMode) {
        this.statusMode = statusMode;
    }

    public List<String> getHeaderLines() {
        return headerLines;
    }

    public List<String> getNoticeLines() {
        return noticeLines;
    }

    public List<String> getStaffLines() {
        return staffLines;
    }

    public List<String> getQueueLines() {
        return queueLines;
    }

    public void setDisplaySnapshot(List<String> header, List<String> notice) {
        this.headerLines.clear();
        this.headerLines.addAll(header);
        this.noticeLines.clear();
        this.noticeLines.addAll(notice);
        this.setChanged();
        this.syncToClient();
    }

    /** 服务端一次性写入结构化 GUI 数据（员工总览 / 订单队列 / 食物队列）。 */
    public void setStructuredSnapshot(String bossName, List<String> playerNames, List<CompoundTag> maidEntries,
                                      String staffFooter, List<CompoundTag> orderEntries, List<CompoundTag> foodEntries) {
        this.bossName = bossName == null ? "" : bossName;
        this.playerNames.clear();
        if (playerNames != null) this.playerNames.addAll(playerNames);
        this.maidEntries.clear();
        if (maidEntries != null) this.maidEntries.addAll(maidEntries);
        this.staffFooter = staffFooter == null ? "" : staffFooter;
        this.orderEntries.clear();
        if (orderEntries != null) this.orderEntries.addAll(orderEntries);
        this.foodEntries.clear();
        if (foodEntries != null) this.foodEntries.addAll(foodEntries);
        this.setChanged();
        this.syncToClient();
    }

    public String getBossName() {
        return bossName;
    }

    public List<String> getPlayerNames() {
        return playerNames;
    }

    public List<CompoundTag> getMaidEntries() {
        return maidEntries;
    }

    public String getStaffFooter() {
        return staffFooter;
    }

    public List<CompoundTag> getOrderEntries() {
        return orderEntries;
    }

    public List<CompoundTag> getFoodEntries() {
        return foodEntries;
    }

    /** 旧接口保留（返回空）：新 GUI 使用 getFoodEntries()。 */
    @Deprecated
    public List<String> getFoodLines() {
        return java.util.Collections.emptyList();
    }

    public void markDirtyAndSync() {
        this.setChanged();
        this.syncToClient();
    }

    public void syncToClient() {
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    // ===================== 持久化 =====================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (boundMachinePos != null) {
            tag.putLong("BoundMachine", boundMachinePos.asLong());
        }
        tag.putInt("BoundMachineId", boundMachineId);
        tag.put("Orders", DispatcherData.saveOrders(trackedOrders.values()));
        tag.put("Bindings", DispatcherData.saveBindings(bindings.values()));
        tag.putInt("StatusMode", statusMode);
        tag.put("HeaderLines", saveStringList(headerLines));
        tag.put("NoticeLines", saveStringList(noticeLines));
        tag.putString("BossName", bossName);
        tag.put("PlayerNames", saveStringList(playerNames));
        tag.put("MaidEntries", saveCompoundList(maidEntries));
        tag.putString("StaffFooter", staffFooter);
        tag.put("OrderEntries", saveCompoundList(orderEntries));
        tag.put("FoodEntries", saveCompoundList(foodEntries));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.boundMachinePos = tag.contains("BoundMachine") ? BlockPos.of(tag.getLong("BoundMachine")) : null;
        this.boundMachineId = tag.getInt("BoundMachineId");
        this.trackedOrders.clear();
        for (DispatcherData.TrackedOrder order : DispatcherData.loadOrders(tag.getList("Orders", 10))) {
            this.trackedOrders.put(order.orderId, order);
        }
        this.bindings.clear();
        this.stationOwners.clear();
        for (DispatcherData.WorkstationBinding binding : DispatcherData.loadBindings(tag.getList("Bindings", 10))) {
            if (!binding.isComplete()) continue;
            this.bindings.put(binding.maidUuid, binding);
            this.stationOwners.put(binding.stationPos.asLong(), binding.maidUuid);
        }
        this.statusMode = tag.getInt("StatusMode");
        loadStringList(tag.getList("HeaderLines", 8), headerLines);
        loadStringList(tag.getList("NoticeLines", 8), noticeLines);
        this.bossName = tag.getString("BossName");
        loadStringList(tag.getList("PlayerNames", 8), playerNames);
        loadCompoundList(tag.getList("MaidEntries", 10), maidEntries);
        this.staffFooter = tag.getString("StaffFooter");
        loadCompoundList(tag.getList("OrderEntries", 10), orderEntries);
        loadCompoundList(tag.getList("FoodEntries", 10), foodEntries);
    }

    private static ListTag saveStringList(List<String> lines) {
        ListTag list = new ListTag();
        for (String line : lines) {
            list.add(StringTag.valueOf(line));
        }
        return list;
    }

    private static void loadStringList(ListTag list, List<String> target) {
        target.clear();
        for (int i = 0; i < list.size(); i++) {
            target.add(list.getString(i));
        }
    }

    private static ListTag saveCompoundList(List<CompoundTag> list) {
        ListTag result = new ListTag();
        for (CompoundTag tag : list) {
            result.add(tag.copy());
        }
        return result;
    }

    private static void loadCompoundList(ListTag list, List<CompoundTag> target) {
        target.clear();
        for (int i = 0; i < list.size(); i++) {
            target.add(list.getCompound(i).copy());
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            this.load(pkt.getTag());
        }
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }

    // ===================== MenuProvider =====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.maid_restaurant_automation.dispatcher");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new DispatcherMenu(id, inventory, this.worldPosition);
    }
}
