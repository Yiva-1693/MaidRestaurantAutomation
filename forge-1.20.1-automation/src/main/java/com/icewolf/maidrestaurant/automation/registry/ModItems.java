package com.icewolf.maidrestaurant.automation.registry;

import com.icewolf.maidrestaurant.automation.item.RestaurantMenuItem;
import com.icewolf.maidrestaurant.automation.item.TooltipBlockItem;
import com.icewolf.maidrestaurant.automation.registry.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create((IForgeRegistry)ForgeRegistries.ITEMS, (String)"maid_restaurant_automation");
    public static final RegistryObject<Item> SCHEDULE_BOARD = ITEMS.register("schedule_board", () -> new TooltipBlockItem((Block)ModBlocks.SCHEDULE_BOARD.get(), new Item.Properties(), "tooltips.maid_restaurant_automation.schedule_board"));
    public static final RegistryObject<Item> DISPATCHER = ITEMS.register("dispatcher", () -> new TooltipBlockItem((Block)ModBlocks.DISPATCHER.get(), new Item.Properties(), "tooltips.maid_restaurant_automation.dispatcher"));
    public static final RegistryObject<Item> RENDER_PROXY = ITEMS.register("render_proxy", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> RESTAURANT_MENU = ITEMS.register("restaurant_menu", () -> new RestaurantMenuItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
