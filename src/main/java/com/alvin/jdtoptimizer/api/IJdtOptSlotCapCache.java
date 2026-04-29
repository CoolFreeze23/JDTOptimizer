package com.alvin.jdtoptimizer.api;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Mixin-injected interface on {@code BaseMachineBE} that caches per-slot-stack
 * capabilities on the block entity itself.
 *
 * <p>Rationale: {@code ItemStack.getCapability(Capabilities.EnergyStorage.ITEM)} is a
 * {@code PatchedDataComponentMap.get} hot path (0.78% of the reference spark profile).
 * It's called every tick from {@link
 * com.direwolf20.justdirethings.common.blockentities.basebe.PoweredMachineBE#chargeItemStack}
 * even though the slot's stack reference is almost always the same from tick to tick.
 *
 * <p>The cache keyed on identity ({@code stack == lastStack}) is safe because
 * {@code ItemStackHandler} returns the same {@link ItemStack} instance for a given slot
 * until it is replaced, so identity equality corresponds to "the slot contents were not
 * swapped out". Mutations to the stack's count or energy DataComponent do not change its
 * identity and don't require cache invalidation — the cached {@code IEnergyStorage}
 * wrapper reads the current component values at call time.
 */
public interface IJdtOptSlotCapCache {

    /**
     * @return the {@link IEnergyStorage} capability for {@code stack}, cached if the
     *         stack reference matches the last call. Returns {@code null} if the stack
     *         is empty or has no energy capability.
     */
    IEnergyStorage jdtopt_getItemEnergyCap(ItemStack stack);
}
