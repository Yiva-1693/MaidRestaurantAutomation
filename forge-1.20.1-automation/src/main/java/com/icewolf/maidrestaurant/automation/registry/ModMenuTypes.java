package com.icewolf.maidrestaurant.automation.registry;

import com.icewolf.maidrestaurant.automation.menu.DispatcherMenu;
import com.icewolf.maidrestaurant.automation.menu.ScheduleBoardMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create((IForgeRegistry)ForgeRegistries.MENU_TYPES, (String)"maid_restaurant_automation");
    public static final RegistryObject<MenuType<ScheduleBoardMenu>> SCHEDULE_BOARD = MENU_TYPES.register("schedule_board", () -> IForgeMenuType.create(ScheduleBoardMenu::new));
    public static final RegistryObject<MenuType<DispatcherMenu>> DISPATCHER = MENU_TYPES.register("dispatcher", () -> IForgeMenuType.create(DispatcherMenu::new));

    public static void register(IEventBus eventBus) {
        MENU_TYPES.register(eventBus);
    }
}
