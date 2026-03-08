package com.p_nsk.ae2fluidcellimprove;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKeyType;
import appeng.items.tools.powered.AbstractPortableCell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class PortableCellFluidHandler implements IFluidHandlerItem {
    private final ItemStack container;
    private final Supplier<Iterable<ServerPlayer>> playersSupplier;

    /**
     * 実際に insert に使った stack 参照。
     * generic な caller が getContainer() を見る場合に、可能ならこちらを返す。
     */
    @Nullable
    private ItemStack lastResolvedContainer;

    public PortableCellFluidHandler(ItemStack container, Supplier<Iterable<ServerPlayer>> playersSupplier) {
        this.container = container;
        this.playersSupplier = playersSupplier;
    }

    @Override
    public @NotNull ItemStack getContainer() {
        return container;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        // 汎用 fluid tank として完全には振る舞わない。
        return FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        // 正確な容量提示はしない。最終判断は fill(SIMULATE) に任せる。
        return tank == 0 ? Integer.MAX_VALUE : 0;
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        if (tank != 0 || stack.isEmpty()) {
            return false;
        }
        return simulateInsert(stack) > 0;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        AFCIMod.LOGGER.debug(
                "[AE2FluidCellImprove] fill stackId={} fluid={} amount={} action={}",
                System.identityHashCode(container),
                resource.getFluid(),
                resource.getAmount(),
                action
        );

        if (resource.isEmpty()) {
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] returning 0 because resource is empty");
            return 0;
        }

        if (!(container.getItem() instanceof AbstractPortableCell portableCell)) {
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] returning 0 because item is not AbstractPortableCell");
            return 0;
        }

        var resolved = resolveOwnership(container, playersSupplier.get());
        if (resolved == null) {
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] returning 0 because ownership could not be resolved safely");
            return 0;
        }

        ServerPlayer owner = resolved.player();
        ItemStack actualStack = resolved.actualStack();

        AFCIMod.LOGGER.debug(
                "[AE2FluidCellImprove] resolved owner={} originalStackId={} actualStackId={}",
                owner.getGameProfile().getName(),
                System.identityHashCode(container),
                System.identityHashCode(actualStack)
        );

        AEFluidKey key = toAeFluidKey(resource);
        AFCIMod.LOGGER.debug("[AE2FluidCellImprove] aeKey={}", key);

        long inserted = portableCell.insert(
                owner,
                actualStack,
                key,
                AEKeyType.fluids(),
                resource.getAmount(),
                action.execute() ? Actionable.MODULATE : Actionable.SIMULATE
        );

        AFCIMod.LOGGER.debug(
                "[AE2FluidCellImprove] inserted={} action={} actualStackId={}",
                inserted,
                action,
                System.identityHashCode(actualStack)
        );

        if (inserted > 0) {
            lastResolvedContainer = actualStack;
        }

        int result = clampToInt(inserted);
        AFCIMod.LOGGER.debug("[AE2FluidCellImprove] fill returning {}", result);
        return result;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        return FluidStack.EMPTY;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        return FluidStack.EMPTY;
    }

    private int simulateInsert(@NotNull FluidStack resource) {
        if (!(container.getItem() instanceof AbstractPortableCell portableCell)) {
            return 0;
        }

        var resolved = resolveOwnership(container, playersSupplier.get());
        if (resolved == null) {
            return 0;
        }

        long inserted = portableCell.insert(
                resolved.player(),
                resolved.actualStack(),
                toAeFluidKey(resource),
                AEKeyType.fluids(),
                resource.getAmount(),
                Actionable.SIMULATE
        );

        return clampToInt(inserted);
    }

    /**
     * 安全に player と actual stack を特定できるときだけ返す。
     *
     * 優先順位:
     * 1. carried stack の参照一致
     * 2. carried stack の item+tag 一致（ただし候補が一意なときだけ）
     * 3. inventory/offhand/armor の item+tag 一致（ただし候補が一意なときだけ）
     *
     * 曖昧なら null を返す。
     */
    @Nullable
    public static ResolvedOwnership resolveOwnership(ItemStack target, Iterable<ServerPlayer> players) {
        // 1. carried stack の参照一致は最優先で採用
        for (ServerPlayer player : players) {
            AbstractContainerMenu menu = player.containerMenu;
            //noinspection ConstantValue
            if (menu == null) {
                continue;
            }

            ItemStack carried = menu.getCarried();
            AFCIMod.LOGGER.debug(
                    "[AE2FluidCellImprove] carried stackId={} target stackId={} sameRef={} sameTags={}",
                    System.identityHashCode(carried),
                    System.identityHashCode(target),
                    carried == target,
                    sameItemSameTagsNonEmpty(carried, target)
            );

            if (carried == target) {
                AFCIMod.LOGGER.debug("[AE2FluidCellImprove] resolved by carried reference owner={}",
                        player.getGameProfile().getName());
                return new ResolvedOwnership(player, carried);
            }
        }

        // 2. carried stack の fuzzy 一致。候補が複数なら拒否
        List<ResolvedOwnership> carriedCandidates = new ArrayList<>();
        for (ServerPlayer player : players) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null) {
                continue;
            }

            ItemStack carried = menu.getCarried();
            if (sameItemSameTagsNonEmpty(carried, target)) {
                carriedCandidates.add(new ResolvedOwnership(player, carried));
            }
        }

        if (carriedCandidates.size() == 1) {
            var resolved = carriedCandidates.get(0);
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] resolved by unique carried fuzzy owner={}",
                    resolved.player().getGameProfile().getName());
            return resolved;
        }

        if (carriedCandidates.size() > 1) {
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] multiple carried candidates, refusing");
            return null;
        }

        // 3. inventory/offhand/armor の fuzzy 一致。候補が複数なら拒否
        ResolvedOwnership inventoryMatch = null;

        for (ServerPlayer player : players) {
            ItemStack found = findUniqueMatchingInventoryReference(player, target);
            boolean matched = found != null;

            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] checking inventory candidate {} matched={}",
                    player.getGameProfile().getName(), matched);

            if (found != null) {
                if (inventoryMatch != null) {
                    AFCIMod.LOGGER.debug("[AE2FluidCellImprove] multiple inventory candidates, refusing");
                    return null;
                }
                inventoryMatch = new ResolvedOwnership(player, found);
            }
        }

        if (inventoryMatch != null) {
            AFCIMod.LOGGER.debug("[AE2FluidCellImprove] resolved by unique inventory owner={}",
                    inventoryMatch.player().getGameProfile().getName());
        }

        return inventoryMatch;
    }

    @Nullable
    private static ItemStack findUniqueMatchingInventoryReference(ServerPlayer player, ItemStack target) {
        ItemStack match = null;

        for (ItemStack stack : player.getInventory().items) {
            match = accumulateUniqueMatch(match, stack, target);
            if (match == AMBIGUOUS) {
                return null;
            }
        }

        for (ItemStack stack : player.getInventory().offhand) {
            match = accumulateUniqueMatch(match, stack, target);
            if (match == AMBIGUOUS) {
                return null;
            }
        }

        for (ItemStack stack : player.getInventory().armor) {
            match = accumulateUniqueMatch(match, stack, target);
            if (match == AMBIGUOUS) {
                return null;
            }
        }

        return match;
    }

    /**
     * null      = まだ候補なし
     * AMBIGUOUS = 複数候補あり
     * その他    = 一意候補
     */
    @Nullable
    private static ItemStack accumulateUniqueMatch(@Nullable ItemStack current, ItemStack candidate, ItemStack target) {
        if (!sameItemSameTagsNonEmpty(candidate, target)) {
            return current;
        }

        if (current == null) {
            return candidate;
        }

        // すでに別候補がいるなら曖昧
        if (current != candidate) {
            return AMBIGUOUS;
        }

        return current;
    }

    private static boolean sameItemSameTagsNonEmpty(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameTags(a, b);
    }

    private static int clampToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static AEFluidKey toAeFluidKey(FluidStack stack) {
        return AEFluidKey.of(stack.getFluid(), stack.getTag());
    }

    /**
     * 曖昧マーカー用の番兵。
     * 実スタックとして使わない。
     */
    private static final ItemStack AMBIGUOUS = new ItemStack(net.minecraft.world.item.Items.BARRIER);
}