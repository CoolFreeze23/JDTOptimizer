package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.events.EntityEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Caps the unbounded {@link EntityEvents#fluidCraftCache fluidCraftCache}.
 *
 * <p>Each entry is keyed by a {@code (BlockState, Item)} pair. In normal gameplay the
 * reachable set is small, but on a server with many modded fluids and many modded items
 * someone can realistically stream thousands of distinct keys through here just by
 * tossing stacks into liquids. The stock map is a plain unbounded {@code HashMap}
 * that's only cleared on a /reload or on server start — a slow but real memory leak.
 *
 * <p>Strategy: after every populate in {@code findRecipe}, if the cache has grown past
 * a generous cap, clear it. This is a "panic eviction" rather than an LRU — swapping
 * the field to a {@code LinkedHashMap} via mixin is awkward (it's static, initialised
 * in {@code <clinit>}), and for a lookup cache of this shape "clear-and-repopulate"
 * degrades gracefully: the worst case is one extra {@code RecipeManager} walk per hot
 * entry after a flush, which only happens once every ~1024 unique queries.
 *
 * <p>Gameplay impact: none — same recipe results, same side effects; only the memory
 * footprint is bounded.
 */
@Mixin(EntityEvents.class)
public abstract class EntityEventsMixin {

    /** Kept in sync with JDT's field name. */
    @Shadow
    static Map<EntityEvents.FluidInputs, BlockState> fluidCraftCache;

    /**
     * Generous ceiling. The realistic hot-set for a modded server is well under this;
     * we only want to stop unbounded growth by uniquely-componented items.
     */
    private static final int JDTOPT$CACHE_CAP = 1024;

    @Inject(
            method = "findRecipe",
            at = @At("TAIL"),
            remap = false
    )
    private static void jdtopt$capFluidCraftCache(BlockState blockState, ItemEntity entity,
                                                  CallbackInfoReturnable<BlockState> cir) {
        Map<EntityEvents.FluidInputs, BlockState> cache = fluidCraftCache;
        if (cache != null && cache.size() > JDTOPT$CACHE_CAP) {
            cache.clear();
        }
    }
}
