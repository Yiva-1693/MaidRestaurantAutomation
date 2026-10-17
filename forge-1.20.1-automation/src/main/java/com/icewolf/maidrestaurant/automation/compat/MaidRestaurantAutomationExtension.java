package com.icewolf.maidrestaurant.automation.compat;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.api.bauble.IMaidBauble;
import com.github.tartaricacid.touhoulittlemaid.item.bauble.BaubleManager;
import com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation;
import com.icewolf.maidrestaurant.automation.registry.ModItems;
import net.minecraft.world.item.Item;

@LittleMaidExtension
public class MaidRestaurantAutomationExtension
implements ILittleMaid {
    public void bindMaidBauble(BaubleManager manager) {
    }
}
