package com.icewolf.maidrestaurant.automation.menu;

import com.icewolf.maidrestaurant.automation.registry.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 调度显示器方块的只读信息菜单。
 * 该界面不放置任何槽位，只携带方块坐标，客户端屏幕再据此查询
 * {@code DispatcherBlockEntity} 的状态、员工总览与订单队列。
 */
public class DispatcherMenu extends AbstractContainerMenu {
    /** 玩家可操作界面的最远距离（8 格，平方距离 64）。 */
    private static final double MAX_DISTANCE_SQR = 64.0D;

    private final BlockPos blockPos;

    // 服务端构造函数
    public DispatcherMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenuTypes.DISPATCHER.get(), id);
        this.blockPos = pos;
    }

    // 客户端构造函数：从网络包读取方块位置
    public DispatcherMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, buf.readBlockPos());
    }

    public BlockPos getBlockPos() {
        return this.blockPos;
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.blockPos == null) {
            return false;
        }
        return player.distanceToSqr((double)this.blockPos.getX() + 0.5D, (double)this.blockPos.getY() + 0.5D, (double)this.blockPos.getZ() + 0.5D) <= MAX_DISTANCE_SQR;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 没有任何槽位，禁止物品搬运
        return ItemStack.EMPTY;
    }
}
