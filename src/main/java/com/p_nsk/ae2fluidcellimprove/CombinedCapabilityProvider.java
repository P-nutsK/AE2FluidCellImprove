package com.p_nsk.ae2fluidcellimprove;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CombinedCapabilityProvider implements ICapabilityProvider {
    private final ICapabilityProvider parent;
    private final LazyOptional<IFluidHandlerItem> fluid;

    public CombinedCapabilityProvider(ICapabilityProvider parent, ItemStack stack) {
        this.parent = parent;
        this.fluid = LazyOptional.of(() -> new PortableCellFluidHandler(stack, () -> {
            var server = ServerLifecycleHooks.getCurrentServer();
            return server != null ? server.getPlayerList().getPlayers() : List.of();
        }));
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER_ITEM) {
            AFCIMod.LOGGER.info("[AE2FluidCellImprove] -> returning fluid handler");
            return fluid.cast();
        }
        return parent != null ? parent.getCapability(cap, side) : LazyOptional.empty();
    }
}