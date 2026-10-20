package com.icewolf.maidrestaurant.automation.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.core.CookingSupplyGuard;
import com.mastermarisa.maid_restaurant.api.IMaidStorage;
import com.mastermarisa.maid_restaurant.api.StepResult;
import com.mastermarisa.maid_restaurant.maid.task.cook.MaidGetFromStorageTask;
import com.mastermarisa.maid_restaurant.utils.component.StackPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 自动炒锅取料使用配方缺口，防止大容量容器的一整组材料进入背包。 */
@Mixin(MaidGetFromStorageTask.class)
public class MaidGetFromStorageTaskMixin {

    @Redirect(method = "accept", at = @At(value = "INVOKE",
            target = "Lcom/mastermarisa/maid_restaurant/utils/ItemHandlerUtils;tryTakeFrom(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lcom/mastermarisa/maid_restaurant/api/IMaidStorage;Lnet/minecraftforge/items/IItemHandler;Lcom/mastermarisa/maid_restaurant/utils/component/StackPredicate;I)V"),
            remap = false, require = 1)
    private void automation$takeOnlyMissingIngredients(ServerLevel level, BlockPos pos, IMaidStorage storage,
                                                       IItemHandler inventory, StackPredicate predicate, int count,
                                                       ServerLevel taskLevel, EntityMaid maid, StepResult result) {
        CookingSupplyGuard.takeIngredients(level, pos, storage, inventory, predicate, count, maid);
    }
}
