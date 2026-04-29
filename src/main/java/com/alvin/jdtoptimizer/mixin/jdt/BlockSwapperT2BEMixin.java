package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.BlockSwapperT1BE;
import com.direwolf20.justdirethings.common.blockentities.BlockSwapperT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.AreaAffectingBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Two overwrites on {@link BlockSwapperT2BE}:
 *
 * <ol>
 *   <li>{@link #findSpotsToSwap()} — {@code Stream.sorted().collect()} → pre-sized
 *       ArrayList + in-place sort.</li>
 *   <li>{@link #isBlockPosValid(ServerLevel, BlockPos)} — {@link BlockState}-keyed
 *       filter fast path, applied <em>after</em> the upstream bail-out checks
 *       ({@code super.isBlockPosValid}, {@code isInBothAreas}, air short-circuit) so
 *       we preserve the exact original control flow.</li>
 * </ol>
 *
 * <p>Gameplay impact: none.
 */
@Mixin(BlockSwapperT2BE.class)
public abstract class BlockSwapperT2BEMixin extends BlockSwapperT1BE {

    private BlockSwapperT2BEMixin() { super(null, null, null); }

    @Shadow public abstract boolean isInBothAreas(BlockPos blockPos);

    @Overwrite
    public List<BlockPos> findSpotsToSwap() {
        final BlockPos origin = getBlockPos();
        final AABB area = ((AreaAffectingBE) (Object) this).getAABB(origin);
        final int minX = (int) area.minX, minY = (int) area.minY, minZ = (int) area.minZ;
        final int maxX = (int) area.maxX - 1, maxY = (int) area.maxY - 1, maxZ = (int) area.maxZ - 1;
        final ServerLevel serverLevel = (ServerLevel) level;

        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (isBlockPosValid(serverLevel, p)) {
                out.add(p.immutable());
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)));
        return out;
    }

    @Overwrite
    public boolean isBlockPosValid(ServerLevel serverLevel, BlockPos blockPos) {
        if (!super.isBlockPosValid(serverLevel, blockPos)) return false;
        if (isInBothAreas(blockPos)) return false;

        BlockState blockState = serverLevel.getBlockState(blockPos);
        // Upstream returns TRUE for air without going through the filter. Preserve that,
        // but still cache so we short-circuit without a filter lookup next time.
        if (blockState.isAir()) return true;

        IJdtOptBlockStateFilterCache cache = (IJdtOptBlockStateFilterCache) (Object) this;
        byte cached = cache.jdtopt_getCachedFilter(blockState);
        if (cached != IJdtOptBlockStateFilterCache.MISS) {
            return cached == IJdtOptBlockStateFilterCache.TRUE;
        }

        ItemStack blockItemStack = blockState.getBlock().getCloneItemStack(serverLevel, blockPos, blockState);
        boolean result = ((FilterableBE) (Object) this).isStackValidFilter(blockItemStack);
        cache.jdtopt_cacheFilter(blockState, result);
        return result;
    }
}
