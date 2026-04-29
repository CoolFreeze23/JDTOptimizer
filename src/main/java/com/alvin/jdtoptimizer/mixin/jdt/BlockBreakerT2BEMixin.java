package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT1BE;
import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import com.direwolf20.justdirethings.util.interfacehelpers.FilterData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adds the {@link BlockState}-keyed filter fast path to
 * {@link BlockBreakerT2BE#isBlockValid(FakePlayer, BlockPos)}.
 *
 * <p>{@code BlockBreakerT2BE.tickServer} is the single largest JDT entry in the reference
 * profile (2.89% inclusive). Most of that is {@code findBlocksToMine} calling
 * {@code isBlockValid} for every cell in the area, which in turn calls
 * {@code getCloneItemStack} + {@code isStackValidFilter} on each cell. Since blockstates
 * are interned, we can cache the filter decision by identity and skip both allocations
 * on subsequent scans.
 *
 * <p>Only activates in "block comparison" filter mode ({@code filterData.blockItemFilter
 * == 0}). The "drop comparison" mode depends on the held tool (enchants, ability flags,
 * RNG drops via {@code Block.getDrops}) — not a safe target for a stateless BlockState
 * cache.
 *
 * <p>Gameplay impact: none. Every cached decision is identical to a freshly computed
 * one for the same BlockState, and the cache is invalidated any time JDT clears its own
 * {@code FilterData.filterCache} via
 * {@link com.alvin.jdtoptimizer.mixin.jdt.BaseMachineBEMixin}.
 */
@Mixin(BlockBreakerT2BE.class)
public abstract class BlockBreakerT2BEMixin extends BlockBreakerT1BE {

    @Shadow public FilterData filterData;

    private BlockBreakerT2BEMixin() { super(null, null, null); }

    /**
     * @see IJdtOptBlockStateFilterCache
     */
    @Overwrite
    public boolean isBlockValid(FakePlayer fakePlayer, BlockPos blockPos) {
        if (!super.isBlockValid(fakePlayer, blockPos)) return false;

        if (filterData.blockItemFilter == 0) {
            BlockState blockState = level.getBlockState(blockPos);

            IJdtOptBlockStateFilterCache cache = (IJdtOptBlockStateFilterCache) (Object) this;
            byte cached = cache.jdtopt_getCachedFilter(blockState);
            if (cached != IJdtOptBlockStateFilterCache.MISS) {
                return cached == IJdtOptBlockStateFilterCache.TRUE;
            }

            ItemStack blockItemStack = blockState.getCloneItemStack(
                    new BlockHitResult(Vec3.ZERO, Direction.UP, blockPos, false),
                    level, blockPos, fakePlayer);
            boolean result = ((FilterableBE) (Object) this).isStackValidFilter(blockItemStack);
            cache.jdtopt_cacheFilter(blockState, result);
            return result;
        }

        // Drop-comparison branch — punt to the original slow path. Drop lists depend on
        // the current tool (enchants, silk-touch, smelter ability, fortune RNG), so a
        // BlockState-only cache can't speak for them safely.
        return jdtopt$isBlockValidDropMode(fakePlayer, blockPos);
    }

    /**
     * Exact copy of the upstream drop-comparison branch. Kept in its own method so the
     * @Overwrite above is readable.
     */
    @org.spongepowered.asm.mixin.Unique
    private boolean jdtopt$isBlockValidDropMode(FakePlayer fakePlayer, BlockPos blockPos) {
        ItemStack tool = getTool();
        java.util.List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                level.getBlockState(blockPos),
                (net.minecraft.server.level.ServerLevel) level,
                blockPos, level.getBlockEntity(blockPos), fakePlayer, tool);
        for (ItemStack drop : drops) {
            if (tool.getItem() instanceof com.direwolf20.justdirethings.common.items.interfaces.ToggleableTool tt
                    && tt.canUseAbility(tool, com.direwolf20.justdirethings.common.items.interfaces.Ability.SMELTER)) {
                if (((FilterableBE) (Object) this).isStackValidFilter(
                        com.direwolf20.justdirethings.common.items.interfaces.Helpers.getSmeltedItem(level, drop))) return true;
            } else {
                if (((FilterableBE) (Object) this).isStackValidFilter(drop)) return true;
            }
        }
        return false;
    }
}
