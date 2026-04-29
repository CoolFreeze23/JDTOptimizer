package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptSlotCapCache;
import com.direwolf20.justdirethings.common.blockentities.basebe.PoweredMachineBE;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Overwrites {@link PoweredMachineBE#chargeItemStack(ItemStack)} to use the per-BE slot
 * capability cache (see {@link IJdtOptSlotCapCache} / {@link BaseMachineBEMixin}).
 *
 * <p><b>Why.</b> The stock implementation calls
 * {@code itemStack.getCapability(Capabilities.EnergyStorage.ITEM)} <em>every tick</em>
 * from both {@code BlockBreakerT2BE.tickServer} and {@code ClickerT2BE.tickServer},
 * forcing a {@code PatchedDataComponentMap.get} lookup (0.78% of the reference profile)
 * even when the slot's tool hasn't changed since the last tick.
 *
 * <p><b>Semantics.</b> Identical. The cache invalidates the moment the slot's ItemStack
 * reference is swapped out ({@code ItemStackHandler} replaces the slot with a fresh stack
 * when contents change), so the cached wrapper is always the same instance the stock
 * code would have resolved on this tick. Mutations to the stack's internal state
 * (energy DataComponent, count) are transparent because the wrapper reads components at
 * operation time.
 *
 * <p><b>Fallback.</b> If {@code this} isn't a {@code BaseMachineBE}-subclass (no cache
 * interface available), we fall through to the original {@code getCapability} call. In
 * practice every JDT PoweredMachineBE implementor extends {@code BaseMachineBE}, so this
 * path is never taken at runtime.
 */
@Mixin(PoweredMachineBE.class)
public interface PoweredMachineBEMixin {

    @Overwrite
    default void chargeItemStack(ItemStack itemStack) {
        final IEnergyStorage slotEnergy;
        if (this instanceof IJdtOptSlotCapCache cache) {
            slotEnergy = cache.jdtopt_getItemEnergyCap(itemStack);
        } else {
            slotEnergy = itemStack.getCapability(Capabilities.EnergyStorage.ITEM);
        }
        if (slotEnergy != null) {
            int acceptedEnergy = slotEnergy.receiveEnergy(5000, true);
            if (acceptedEnergy > 0) {
                // this-cast: safe because chargeItemStack is only meaningful on a
                // PoweredMachineBE, which declares getEnergyStorage() abstract.
                int extractedEnergy = ((PoweredMachineBE) this)
                        .getEnergyStorage().extractEnergy(acceptedEnergy, false);
                slotEnergy.receiveEnergy(extractedEnergy, false);
            }
        }
    }
}
