package com.icewolf.maidrestaurant.automation.client;

import com.icewolf.maidrestaurant.automation.client.screen.DispatcherScreen;
import com.icewolf.maidrestaurant.automation.client.screen.ScheduleBoardScreen;
import com.icewolf.maidrestaurant.automation.registry.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "maid_restaurant_automation", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MenuScreens.register(ModMenuTypes.SCHEDULE_BOARD.get(), ScheduleBoardScreen::new);
        MenuScreens.register(ModMenuTypes.DISPATCHER.get(), DispatcherScreen::new);
    }
}
