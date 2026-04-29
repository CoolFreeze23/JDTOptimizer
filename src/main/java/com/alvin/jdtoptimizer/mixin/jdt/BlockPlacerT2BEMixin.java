package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.BlockPlacerT1BE;
import com.direwolf20.justdirethings.common.blockentities.BlockPlacerT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.AreaAffectingBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Two overwrites on {@link BlockPlacerT2BE}:
 *
 * <ol>
 *   <li>{@link #findSpotsToPlace(FakePlayer)} — {@code Stream.sorted().collect()} →
 *       pre-sized ArrayList + in-place sort, same shape as the ClickerT2 / FluidCollectorT2
 *       rewrites. Identical output ordering.</li>
 *   <li>{@link #isBlockPosValid(FakePlayer, BlockPos)} — adds the
 *       {@link BlockState}-keyed filter fast path described in
 *       {@link IJdtOptBlockStateFilterCache}. The cached decision is keyed on the
 *       {@em neighbour's} BlockState (the one whose clone-itemstack would be fed into
 *       the filter), which is the correct input — two positions whose neighbour is the
 *       same interned BlockState must produce the same filter verdict.</li>
 * </ol>
 *
 * <p>Gameplay impact: none.
 */
@Mixin(BlockPlacerT2BE.class)
public abstract class BlockPlacerT2BEMixin extends BlockPlacerT1BE {

    private BlockPlacerT2BEMixin() { super(null, null, null); }

    @Overwrite
    public List<BlockPos> findSpotsToPlace(FakePlayer fakePlayer) {
        final BlockPos origin = getBlockPos();
        final AABB area = ((AreaAffectingBE) (Object) this).getAABB(origin);
        final int minX = (int) area.minX, minY = (int) area.minY, minZ = (int) area.minZ;
        final int maxX = (int) area.maxX - 1, maxY = (int) area.maxY - 1, maxZ = (int) area.maxZ - 1;

        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (isBlockPosValid(fakePlayer, p)) {
                out.add(p.immutable());
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)));
        return out;
    }

    @Overwrite
    public boolean isBlockPosValid(FakePlayer fakePlayer, BlockPos blockPos) {
        if (!super.isBlockPosValid(fakePlayer, blockPos)) return false;

        // Note: the filter is evaluated against the *neighbour* (offset by facing),
        // which is the position where the item would be placed. Key the cache on that
        // BlockState, not on the origin blockPos's state.
        Direction facing = getDirectionValue();
        BlockState neighbourState = level.getBlockState(blockPos.relative(facing));

        IJdtOptBlockStateFilterCache cache = (IJdtOptBlockStateFilterCache) (Object) this;
        byte cached = cache.jdtopt_getCachedFilter(neighbourState);
        if (cached != IJdtOptBlockStateFilterCache.MISS) {
            return cached == IJdtOptBlockStateFilterCache.TRUE;
        }

        ItemStack blockItemStack = neighbourState.getCloneItemStack(
                new BlockHitResult(Vec3.ZERO, facing, blockPos, false),
                level, blockPos, fakePlayer);
        boolean result = ((FilterableBE) (Object) this).isStackValidFilter(blockItemStack);
        cache.jdtopt_cacheFilter(neighbourState, result);
        return result;
    }
}
