package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.FluidPlacerT1BE;
import com.direwolf20.justdirethings.common.blockentities.FluidPlacerT2BE;
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
 * Mirrors the {@code BlockPlacerT2BEMixin} treatment for {@link FluidPlacerT2BE}:
 * stream → loop in {@code findSpotsToPlace}, and a {@link BlockState}-keyed filter
 * fast path on {@code isBlockPosValid} (keyed on the neighbour's state, since that's
 * what the upstream code clones for the filter check).
 *
 * <p>Argument order on {@code isBlockPosValid(BlockPos, FakePlayer)} follows
 * {@link FluidPlacerT1BE}'s signature — reversed from Clicker's.
 *
 * <p>Gameplay impact: none.
 */
@Mixin(FluidPlacerT2BE.class)
public abstract class FluidPlacerT2BEMixin extends FluidPlacerT1BE {

    private FluidPlacerT2BEMixin() { super(null, null, null); }

    @Overwrite
    public List<BlockPos> findSpotsToPlace(FakePlayer fakePlayer) {
        final BlockPos origin = getBlockPos();
        final AABB area = ((AreaAffectingBE) (Object) this).getAABB(origin);
        final int minX = (int) area.minX, minY = (int) area.minY, minZ = (int) area.minZ;
        final int maxX = (int) area.maxX - 1, maxY = (int) area.maxY - 1, maxZ = (int) area.maxZ - 1;

        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (isBlockPosValid(p, fakePlayer)) {
                out.add(p.immutable());
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)));
        return out;
    }

    @Overwrite
    public boolean isBlockPosValid(BlockPos blockPos, FakePlayer fakePlayer) {
        if (!super.isBlockPosValid(blockPos, fakePlayer)) return false;

        Direction facing = getDirectionValue();
        BlockState neighbourState = level.getBlockState(blockPos.relative(facing));

        IJdtOptBlockStateFilterCache cache = (IJdtOptBlockStateFilterCache) (Object) this;
        byte cached = cache.jdtopt_getCachedFilter(neighbourState);
        if (cached != IJdtOptBlockStateFilterCache.MISS) {
            return cached == IJdtOptBlockStateFilterCache.TRUE;
        }

        // Upstream passes {@code null} for the player here; preserve that.
        ItemStack blockItemStack = neighbourState.getCloneItemStack(
                new BlockHitResult(Vec3.ZERO, facing, blockPos, false),
                level, blockPos, null);
        boolean result = ((FilterableBE) (Object) this).isStackValidFilter(blockItemStack);
        cache.jdtopt_cacheFilter(neighbourState, result);
        return result;
    }
}
