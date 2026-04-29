package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.ClickerT1BE;
import com.direwolf20.justdirethings.common.blockentities.ClickerT2BE;
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
 * {@link ClickerT2BE#findSpotsToClick(FakePlayer)} with a pre-sized array + in-place sort.
 *
 * <p>Called once per activation (when the position queue is empty and the machine can run).
 * For radius-5 T2 Clickers this is an 11×11×11 = 1,331-cell scan; the stream pipeline was
 * the dominant allocator on that path. Output list is identical in contents and order.
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
}
