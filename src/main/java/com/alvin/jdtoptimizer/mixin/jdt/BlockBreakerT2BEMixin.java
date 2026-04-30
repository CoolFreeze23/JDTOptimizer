package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT1BE;
import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import com.direwolf20.justdirethings.common.items.interfaces.Ability;
import com.direwolf20.justdirethings.common.items.interfaces.Helpers;
import com.direwolf20.justdirethings.common.items.interfaces.ToggleableTool;
import com.direwolf20.justdirethings.util.interfacehelpers.FilterData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Adds the {@link BlockState}-keyed filter fast path to
 * {@link BlockBreakerT2BE#isBlockValid(FakePlayer, BlockPos)}.
 *
 * <p>{@code BlockBreakerT2BE.tickServer} is the single largest JDT entry in the reference
 * profile. Most of that is {@code findBlocksToMine} calling {@code isBlockValid} for
 * every cell in the area, which in turn calls {@code getCloneItemStack} +
 * {@code isStackValidFilter} (block-compare) or {@code Block.getDrops} +
 * {@code isStackValidFilter} (drop-compare) on each cell. BlockStates are interned, so
 * we can cache the filter decision by identity and skip the allocations on subsequent
 * scans.
 *
 * <h3>Block-comparison mode (filterData.blockItemFilter == 0)</h3>
 * Fully cached. {@code getCloneItemStack} is a pure function of {@code BlockState} +
 * {@code level}+{@code pos} (the {@code BlockHitResult} is a stub), and the filter
 * decision in turn is a pure function of the resulting stack. Cache-invalidated on
 * {@code setChanged()} via {@link com.alvin.jdtoptimizer.mixin.jdt.BaseMachineBEMixin}.
 *
 * <h3>Drop-comparison mode (filterData.blockItemFilter == 1)</h3>
 * Cached only when {@code state.getBlock() instanceof EntityBlock}. Rationale:
 * <ul>
 *   <li>BE-class blocks (AE2 cables/drives/controllers, Create kinetics, Mekanism tanks,
 *       JDT's own machines, …) have deterministic drops — they drop the block-item with
 *       the BE data encoded as a {@code DataComponent}, and do not consult the loot
 *       table's RNG machinery for item identity. In the reference profile these account
 *       for <b>~67% of this hotspot</b>.</li>
 *   <li>Non-BE blocks can have RNG-dependent loot tables (leaves dropping apples at
 *       ~0.5%, gravel dropping flint at ~10%, etc.). Vanilla {@code findBlocksToMine}
 *       iterates every position in the scan area and rolls {@code Block.getDrops}
 *       independently per cell, so a 100-position area naturally has a
 *       {@code 1 - (1 - p)^100} chance of catching rare drops. Collapsing that to a
 *       single per-BlockState roll would measurably nerf those filters — a gameplay
 *       change we're unwilling to ship. So non-BE blocks fall through to the uncached
 *       path exactly like upstream.</li>
 * </ul>
 *
 * <p>Gameplay impact: none. BE-block drops are deterministic functions of
 * {@code BlockState}+inventory, and inventory changes to filter/tool/output slots all
 * route through {@code setChanged()}, which already clears the cache.
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

        BlockState blockState = level.getBlockState(blockPos);

        if (filterData.blockItemFilter == 0) {
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

        return jdtopt$isBlockValidDropMode(fakePlayer, blockPos, blockState);
    }

    /**
     * Drop-comparison branch. Cached for {@link EntityBlock}-class blocks only (see
     * class javadoc for the correctness argument); uncached for the rest.
     *
     * <p>Also hoists the {@code SMELTER} ability check out of the drop-iteration loop:
     * upstream reads {@code tool.getItem() instanceof ToggleableTool && canUseAbility}
     * once per drop in the list, but the answer is a pure function of {@code tool} and
     * so can be evaluated once per call. In the reference profile
     * {@code ToggleableTool.canUseAbility} + {@code hasAbility} cost ~120 ms inside this
     * loop — zero-risk to hoist.
     */
    @Unique
    private boolean jdtopt$isBlockValidDropMode(FakePlayer fakePlayer, BlockPos blockPos, BlockState blockState) {
        boolean cacheable = blockState.getBlock() instanceof EntityBlock;

        IJdtOptBlockStateFilterCache cache = null;
        if (cacheable) {
            cache = (IJdtOptBlockStateFilterCache) (Object) this;
            byte cached = cache.jdtopt_getCachedFilter(blockState);
            if (cached != IJdtOptBlockStateFilterCache.MISS) {
                return cached == IJdtOptBlockStateFilterCache.TRUE;
            }
        }

        ItemStack tool = getTool();
        boolean smelterActive = tool.getItem() instanceof ToggleableTool tt
                && tt.canUseAbility(tool, Ability.SMELTER);

        List<ItemStack> drops = Block.getDrops(
                blockState,
                (ServerLevel) level,
                blockPos, level.getBlockEntity(blockPos), fakePlayer, tool);

        FilterableBE filterable = (FilterableBE) (Object) this;
        boolean result = false;
        for (ItemStack drop : drops) {
            ItemStack probe = smelterActive ? Helpers.getSmeltedItem(level, drop) : drop;
            if (filterable.isStackValidFilter(probe)) {
                result = true;
                break;
            }
        }

        if (cacheable) {
            cache.jdtopt_cacheFilter(blockState, result);
        }
        return result;
    }
}
