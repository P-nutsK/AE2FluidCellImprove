package com.p_nsk.ae2fluidcellimprove;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public record ResolvedOwnership(ServerPlayer player, ItemStack actualStack) {
}