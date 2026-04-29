package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.FluidCollectorT1BE;
import com.direwolf20.justdirethings.common.blockentities.FluidCollectorT2BE;
import com.direwolf20.justdirethings.common.blockentities.basebe.AreaAffectingBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the {@code Stream.sorted().collect()} pipeline in
 * {@link FluidCollectorT2BE#findSpotsToCollect(FakePlayer)} with a pre-sized array + in-place sort.
 *
 * <p>Note: {@code getAABB(BlockPos)} is a <b>default method</b> on the {@link AreaAffectingBE}
 * interface. {@code @Shadow} cannot resolve interface default methods, so we cast through
 * {@code (Object)} to {@code AreaAffectingBE} at the call site. The runtime target
 * {@link FluidCollectorT2BE} implements {@link AreaAffectingBE}, so the cast always succeeds.
 *
 * <p>Argument order for {@code isBlockPosValid(blockPos, fakePlayer)} intentionally matches
 * {@link FluidCollectorT1BE}'s signature — not Clicker's flipped order.
 *
 * <p>Gameplay impact: none.
 */
@Mixin(FluidCollectorT2BE.class)
public abstract class FluidCollectorT2BEMixin extends FluidCollectorT1BE {

    private FluidCollectorT2BEMixin() { super(null, null, null); }

    @Overwrite
    public List<BlockPos> findSpotsToCollect(FakePlayer fakePlayer) {
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
}
