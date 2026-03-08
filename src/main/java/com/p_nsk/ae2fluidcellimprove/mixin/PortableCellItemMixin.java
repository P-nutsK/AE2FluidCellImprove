package com.p_nsk.ae2fluidcellimprove.mixin;

import appeng.items.tools.powered.AbstractPortableCell;
import appeng.items.tools.powered.PortableCellItem;
import com.p_nsk.ae2fluidcellimprove.AFCIMod;
import com.p_nsk.ae2fluidcellimprove.CombinedCapabilityProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PortableCellItem.class)
public abstract class PortableCellItemMixin extends AbstractPortableCell {

    public PortableCellItemMixin(MenuType<?> menuType, Properties props, int defaultColor) {
        super(menuType, props, defaultColor);
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, CompoundTag nbt) {
        ICapabilityProvider original = super.initCapabilities(stack, nbt);
        return new CombinedCapabilityProvider(original, stack);
    }
}
