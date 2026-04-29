package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Sanity-check mixin target: proves our build can see JDT classes and weave mixins
 * into them. Does nothing observable yet — replaced/extended by the real optimization
 * mixins in follow-up PRs (tick two-laning, attachment caching, etc.).
 */
@Mixin(BaseMachineBE.class)
public abstract class ExampleBaseMachineBEMixin {
    // Intentionally empty: just verifies the @Mixin reference links during refmap/classload.
}
