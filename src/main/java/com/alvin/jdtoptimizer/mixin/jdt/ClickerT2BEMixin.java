package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.direwolf20.justdirethings.common.blockentities.ClickerT1BE;
import com.direwolf20.justdirethings.common.blockentities.ClickerT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Two overwrites on {@link ClickerT2BE}:
 *
 * <ol>
 *   <li>{@link #findSpotsToClick(FakePlayer)} — replaces the
 *       {@code Stream.sorted().collect()} pipeline with a pre-sized array + in-place sort.
 *       For radius-5 clickers this is an 11×11×11 = 1,331-cell scan; the stream pipeline
 *       was the dominant allocator on that path. Output list is identical in contents and
 *       order.</li>
 *   <li>{@link #isBlockPosValid(FakePlayer, BlockPos)} — adds a {@link BlockState}-keyed
 *       fast path that short-circuits before the per-position
 *       {@code getCloneItemStack(...)} allocation + {@code new ItemStackKey(...)} +
 *       {@code HashMap} lookup. Cache lives on the BE, invalidates whenever JDT clears
 *       its own {@code FilterData.filterCache} (see
 *       {@link com.alvin.jdtoptimizer.mixin.jdt.BaseMachineBEMixin}). Output is identical
 *       because BlockStates are interned — same state reference ⇒ same cloned stack ⇒
 *       same filter decision.</li>
 * </ol>
 *
 * <p>Gameplay impact: none.
 */
@Mixin(ClickerT2BE.class)
public abstract class ClickerT2BEMixin extends ClickerT1BE {

    @Shadow public abstract AABB getAABB();

    private ClickerT2BEMixin() { super(null, null, null); }

    @Overwrite
    public List<BlockPos> findSpotsToClick(FakePlayer fakePlayer) {
        final AABB area = getAABB();
        final int minX = (int) area.minX, minY = (int) area.minY, minZ = (int) area.minZ;
        final int maxX = (int) area.maxX - 1, maxY = (int) area.maxY - 1, maxZ = (int) area.maxZ - 1;
        final BlockPos origin = getBlockPos();

        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (isBlockPosValid(fakePlayer, p)) {
                out.add(p.immutable());
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)));
        return out;
    }

    /**
     * @see IJdtOptBlockStateFilterCache
     */
    @Overwrite
    public boolean isBlockPosValid(FakePlayer fakePlayer, BlockPos blockPos) {
        // super -> ClickerT1BE.isBlockPosValid: mayInteract + chunk checks. Cheap.
        if (!super.isBlockPosValid(fakePlayer, blockPos)) return false;

        BlockState blockState = level.getBlockState(blockPos);

        // Liquids keep the original path — ItemStackKey allocation is already rare
        // here (a LiquidBlock is one of a handful of singletons) and the cached key
        // lookup in FilterData.filterCache covers it fine.
        if (blockState.getBlock() instanceof LiquidBlock liquidBlock) {
            return ((FilterableBE) (Object) this).isStackValidFilter(liquidBlock);
        }

        // BlockState fast path.
        IJdtOptBlockStateFilterCache cache = (IJdtOptBlockStateFilterCache) (Object) this;
        byte cached = cache.jdtopt_getCachedFilter(blockState);
        if (cached != IJdtOptBlockStateFilterCache.MISS) {
            return cached == IJdtOptBlockStateFilterCache.TRUE;
        }

        // Miss: fall back to JDT's full getCloneItemStack + isStackValidFilter path,
        // then cache the verdict by BlockState identity.
        ItemStack blockItemStack = blockState.getCloneItemStack(
                new BlockHitResult(Vec3.ZERO, getDirectionValue(), blockPos, false),
                level, blockPos, fakePlayer);
        boolean result = ((FilterableBE) (Object) this).isStackValidFilter(blockItemStack);
        cache.jdtopt_cacheFilter(blockState, result);
        return result;
    }
}
