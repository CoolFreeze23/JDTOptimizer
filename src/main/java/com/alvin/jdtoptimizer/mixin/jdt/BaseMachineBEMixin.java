package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.alvin.jdtoptimizer.api.IJdtOptSlotCapCache;
import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds two pieces of per-BE caching to {@link BaseMachineBE}:
 *
 * <ol>
 *   <li><b>{@code getMachineHandler()} attachment cache.</b> The underlying
 *       {@code IAttachmentHolder.getData(...)} lookup is 3.10% of the reference spark
 *       profile. The handler attachment is attached lazily once and then lives for the
 *       BE's lifetime — JDT never swaps it out at runtime. Caching the reference
 *       eliminates every subsequent attachment-map lookup for this BE.</li>
 *   <li><b>Per-slot-stack {@link IEnergyStorage} cache</b> (see
 *       {@link IJdtOptSlotCapCache}). Used by the overwritten
 *       {@code PoweredMachineBE.chargeItemStack} path to avoid the
 *       {@code PatchedDataComponentMap.get} call every tick on a slot whose contents
 *       haven't changed.</li>
 * </ol>
 *
 * <p>Gameplay change: none. Every cached value is identical to what the original method
 * would have returned.
 */
@Mixin(BaseMachineBE.class)
public abstract class BaseMachineBEMixin implements IJdtOptSlotCapCache, IJdtOptBlockStateFilterCache {

    @Unique
    private ItemStackHandler jdtopt$cachedMachineHandler;

    /**
     * Identity-tracking of the last ItemStack passed to {@link #jdtopt_getItemEnergyCap}.
     * When the slot contents change, {@code ItemStackHandler} installs a fresh stack
     * instance, so {@code stack == this.jdtopt$lastCapStack} fails and we re-resolve.
     * Starts as {@link ItemStack#EMPTY} so the first call is treated as "new".
     */
    @Unique
    private ItemStack jdtopt$lastCapStack = ItemStack.EMPTY;

    @Unique
    private IEnergyStorage jdtopt$lastCapEnergy;

    /**
     * BlockState-keyed filter-decision cache. See {@link IJdtOptBlockStateFilterCache}.
     * Sized at 64 because typical machine AABBs are 3x3x3 = 27 states, allowing some
     * headroom before rehashing. Intentionally a {@link Reference2ByteOpenHashMap}
     * rather than an {@code Object2ByteOpenHashMap}: Minecraft interns BlockStates, so
     * identity equality is the correct semantics and it's one hash+compare cheaper.
     */
    @Unique
    private final Reference2ByteOpenHashMap<BlockState> jdtopt$blockStateFilterCache =
            new Reference2ByteOpenHashMap<>(64);

    @Inject(method = "getMachineHandler", at = @At("HEAD"), cancellable = true)
    private void jdtopt$returnCachedMachineHandler(CallbackInfoReturnable<ItemStackHandler> cir) {
        ItemStackHandler cached = this.jdtopt$cachedMachineHandler;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getMachineHandler", at = @At("RETURN"))
    private void jdtopt$storeCachedMachineHandler(CallbackInfoReturnable<ItemStackHandler> cir) {
        if (this.jdtopt$cachedMachineHandler == null) {
            this.jdtopt$cachedMachineHandler = cir.getReturnValue();
        }
    }

    @Override
    public IEnergyStorage jdtopt_getItemEnergyCap(ItemStack stack) {
        // Identity-based cache. ItemStackHandler returns the same reference until the
        // slot contents are swapped, so '==' is the exact right invalidation signal.
        if (stack == this.jdtopt$lastCapStack) {
            return this.jdtopt$lastCapEnergy;
        }
        this.jdtopt$lastCapStack = stack;
        this.jdtopt$lastCapEnergy = stack.isEmpty()
                ? null
                : stack.getCapability(Capabilities.EnergyStorage.ITEM);
        return this.jdtopt$lastCapEnergy;
    }

    @Override
    public Reference2ByteOpenHashMap<BlockState> jdtopt_blockStateFilterCache() {
        return this.jdtopt$blockStateFilterCache;
    }

    /**
     * Piggy-back on JDT's existing filter-cache invalidation. When JDT clears its own
     * {@code FilterData.filterCache} at the end of {@code setChanged()}, it means
     * "something that could affect filter decisions has changed" (slot contents, etc.).
     * Mirror that for our BlockState cache so we can never be stale relative to JDT's.
     */
    @Inject(method = "setChanged", at = @At("TAIL"))
    private void jdtopt$clearBlockStateFilterCache(CallbackInfo ci) {
        // Guard with isEmpty to avoid touching the backing array in the very common
        // "BE marked dirty but nobody ever populated our cache" case.
        if (!this.jdtopt$blockStateFilterCache.isEmpty()) {
            this.jdtopt$blockStateFilterCache.clear();
        }
    }
}
