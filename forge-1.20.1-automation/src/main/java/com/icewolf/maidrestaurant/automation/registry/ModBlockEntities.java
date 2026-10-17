package com.icewolf.maidrestaurant.automation.registry;

import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import com.icewolf.maidrestaurant.automation.block.entity.ScheduleBoardBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create((IForgeRegistry)ForgeRegistries.BLOCK_ENTITY_TYPES, (String)"maid_restaurant_automation");
    public static final RegistryObject<BlockEntityType<ScheduleBoardBlockEntity>> SCHEDULE_BOARD = BLOCK_ENTITIES.register("schedule_board", () -> BlockEntityType.Builder.of(ScheduleBoardBlockEntity::new, (Block[])new Block[]{(Block)ModBlocks.SCHEDULE_BOARD.get()}).build(null));
    public static final RegistryObject<BlockEntityType<DispatcherBlockEntity>> DISPATCHER = BLOCK_ENTITIES.register("dispatcher", () -> BlockEntityType.Builder.of(DispatcherBlockEntity::new, (Block[])new Block[]{(Block)ModBlocks.DISPATCHER.get()}).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
