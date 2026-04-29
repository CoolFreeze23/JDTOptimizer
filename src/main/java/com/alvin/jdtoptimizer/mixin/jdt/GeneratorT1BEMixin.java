package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.cache.Directions;
import com.direwolf20.justdirethings.common.blockentities.GeneratorT1BE;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Eliminates the per-tick {@code Direction.values()} clone inside
 * {@link GeneratorT1BE#providePowerAdjacent()}.
 *
 * <p>Upstream iterates {@code for (Direction direction : Direction.values())} every
 * server tick per generator. Each call allocates a fresh six-element {@code Direction[]}
 * that immediately gets GC'd. On worlds with dozens of active generators this shows
 * up as steady young-gen churn. We redirect the {@code values()} invocation to a
 * shared-array holder ({@link Directions#VALUES}) — same contents, zero allocation.
 *
 * <p>Gameplay change: none. The for-each protocol only reads from the array; returning
 * the shared reference is safe.
 */
@Mixin(GeneratorT1BE.class)
public abstract class GeneratorT1BEMixin {

    @Redirect(
            method = "providePowerAdjacent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"
            )
    )
    private Direction[] jdtopt$sharedDirectionValues() {
        return Directions.VALUES;
    }
}
