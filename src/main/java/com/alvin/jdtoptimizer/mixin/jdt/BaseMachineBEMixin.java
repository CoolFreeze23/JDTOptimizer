package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches the result of {@link BaseMachineBE#getMachineHandler()} on the block entity itself.
 *
 * <p>Profiling shows that JDT machines call {@code getMachineHandler()} many times per tick
 * (and its callees — {@code getTool()}, {@code getPlaceStack()}, {@code getClickStack()} etc.
 * are called in every validity check). Each call routes through NeoForge's
 * {@code IAttachmentHolder.getData(...)}, which costs a {@code Reference2ObjectArrayMap} lookup
 * (3.10% of the server thread in the reference spark profile).
 *
 * <p>The handler attachment is attached lazily to the BE once and then lives for the BE's
 * lifetime — it is never swapped out at runtime by JDT. So the cached reference is valid as
 * long as the BE instance is alive, and the BE instance being destroyed nulls the field out
 * anyway. No explicit invalidation is required.
 *
 * <p>Gameplay change: none. The returned object is identical to what the original method
 * would have returned.
 */
@Mixin(BaseMachineBE.class)
public abstract class BaseMachineBEMixin {

    @Unique
    private ItemStackHandler jdtopt$cachedMachineHandler;

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
}
