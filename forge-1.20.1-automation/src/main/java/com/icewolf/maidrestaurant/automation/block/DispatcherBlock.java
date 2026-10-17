package com.icewolf.maidrestaurant.automation.block;

import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import com.icewolf.maidrestaurant.automation.registry.ModItems;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * 餐厅调度显示器方块（整方块、无朝向）。
 * 右键打开只读的「餐厅调度中心」界面；被破坏时掉落自身。
 */
public class DispatcherBlock extends BaseEntityBlock {
    public DispatcherBlock() {
        super(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DispatcherBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, blockEntity) -> DispatcherBlockEntity.tick(lvl, pos, st, (DispatcherBlockEntity)blockEntity);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DispatcherBlockEntity)) {
            return InteractionResult.PASS;
        }
        DispatcherBlockEntity dispatcher = (DispatcherBlockEntity)be;
        if (player instanceof ServerPlayer serverPlayer) {
            // 右键时若尚未绑定打单机，立即就近绑定一次（修复"先放打单机、后放调度显示器"时的绑定延迟/失败）
            if (!dispatcher.hasBoundMachine() && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.icewolf.maidrestaurant.automation.core.DispatcherLogic.tryBindNearest(dispatcher, serverLevel);
            }
            NetworkHooks.openScreen(serverPlayer, dispatcher, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            popResource(level, pos, new ItemStack(ModItems.DISPATCHER.get()));
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
