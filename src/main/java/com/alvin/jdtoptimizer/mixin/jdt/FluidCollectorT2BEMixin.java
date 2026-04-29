package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.FluidCollectorT1BE;
import com.direwolf20.justdirethings.common.blockentities.FluidCollectorT2BE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the {@code Stream.sorted().collect()} pipeline in
 * {@link FluidCollectorT2BE#findSpotsToCollect(FakePlayer)} with a pre-sized array + in-place sort.
 *
 * <p>Note the argument order difference from Clicker: {@code isBlockPosValid(blockPos, fakePlayer)}
 * here versus {@code isBlockPosValid(fakePlayer, blockPos)} in Clicker.
 *
 * <p>Gameplay impact: none.
 */
@Mixin(FluidCollectorT2BE.class)
public abstract class FluidCollectorT2BEMixin extends FluidCollectorT1BE {

    @Shadow public abstract AABB getAABB(BlockPos relativePos);

    private FluidCollectorT2BEMixin() { super(null, null, null); }

    @Overwrite
    public List<BlockPos> findSpotsToCollect(FakePlayer fakePlayer) {
        final BlockPos origin = getBlockPos();
        final AABB area = getAABB(origin);
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
}
