package com.icewolf.maidrestaurant.automation.registry;

import com.icewolf.maidrestaurant.automation.block.DispatcherBlock;
import com.icewolf.maidrestaurant.automation.block.ScheduleBoardBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create((IForgeRegistry)ForgeRegistries.BLOCKS, (String)"maid_restaurant_automation");
    public static final RegistryObject<Block> SCHEDULE_BOARD = BLOCKS.register("schedule_board", ScheduleBoardBlock::new);
    public static final RegistryObject<Block> DISPATCHER = BLOCKS.register("dispatcher", DispatcherBlock::new);

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
