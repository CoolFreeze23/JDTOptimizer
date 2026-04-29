package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.cache.Directions;
import com.direwolf20.justdirethings.common.blockentities.GeneratorFluidT1BE;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same per-tick {@code Direction.values()} de-allocation as
 * {@link GeneratorT1BEMixin}, applied to the fluid-generator variant. See that class
 * for the rationale.
 */
@Mixin(GeneratorFluidT1BE.class)
public abstract class GeneratorFluidT1BEMixin {

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
