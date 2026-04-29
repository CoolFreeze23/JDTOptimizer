package com.alvin.jdtoptimizer.api;

import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mixin-injected interface on {@code BaseMachineBE}: exposes a {@link BlockState}-keyed
 * filter decision cache.
 *
 * <p>Blockstates are interned singletons in Minecraft — two positions holding the same
 * block+properties produce the exact same {@link BlockState} reference. So a reference
 * map keyed by {@code BlockState} (computed via identity hash, not
 * {@code ItemStack.isSameItemSameComponents}) is a zero-allocation drop-in replacement
 * for the stock {@code FilterData.filterCache} whenever the filter decision is
 * block-derived.
 *
 * <p>The cache stores a three-state byte per state:
 * <ul>
 *   <li>{@link #MISS} — not yet computed</li>
 *   <li>{@link #TRUE} / {@link #FALSE} — cached decision</li>
 * </ul>
 * Why not a {@code Boolean}? Autoboxing on the hot path is exactly what we're avoiding,
 * and {@code Object2BooleanOpenHashMap.getBoolean} returns {@code false} both for
 * "missing" and "cached false", so we need the tri-state.
 *
 * <p>Invalidation: cleared from {@code BaseMachineBE.setChanged()} alongside JDT's own
 * {@code FilterData.filterCache.clear()}, which already handles every "filter slots
 * might have changed" code path JDT cares about.
 */
public interface IJdtOptBlockStateFilterCache {

    byte MISS = 0;
    byte FALSE = 1;
    byte TRUE = 2;

    Reference2ByteOpenHashMap<BlockState> jdtopt_blockStateFilterCache();

    default byte jdtopt_getCachedFilter(BlockState state) {
        Reference2ByteOpenHashMap<BlockState> c = jdtopt_blockStateFilterCache();
        return c.isEmpty() ? MISS : c.getByte(state);
    }

    default void jdtopt_cacheFilter(BlockState state, boolean result) {
        jdtopt_blockStateFilterCache().put(state, result ? TRUE : FALSE);
    }
}
