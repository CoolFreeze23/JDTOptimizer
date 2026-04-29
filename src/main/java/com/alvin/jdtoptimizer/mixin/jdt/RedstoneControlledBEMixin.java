package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.basebe.RedstoneControlledBE;
import com.direwolf20.justdirethings.common.blocks.BlockBreakerT1;
import com.direwolf20.justdirethings.util.MiscHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Two improvements to {@link RedstoneControlledBE}'s default methods:
 *
 * <ol>
 *   <li><b>Skip the setBlock when ACTIVE isn't actually changing.</b> Upstream
 *       unconditionally calls {@code setBlockAndUpdate} any time
 *       {@code evaluateRedstone} fires and the block has the ACTIVE property. In the
 *       common case ("redstone state just got re-checked, ACTIVE value is already
 *       correct") that's a no-op blockstate swap that still triggers a light engine
 *       recheck and a client sync. We guard on {@code blockState.getValue(ACTIVE) !=
 *       newActive} before touching the world.</li>
 *   <li><b>{@code setBlock(pos, state, 2)} instead of {@code setBlockAndUpdate}.</b>
 *       {@code setBlockAndUpdate} uses flag 3 = BLOCK_UPDATE | NOTIFY_NEIGHBORS, which
 *       propagates a neighbor-updated shape-update to all six adjacent blocks. The
 *       ACTIVE property is a purely cosmetic flag — no comparator or vanilla redstone
 *       mechanism reads it — so the neighbor update is wasted work that redstone-
 *       pulsing setups do every pulse. Flag 2 (BLOCK_UPDATE only) still syncs the new
 *       state to clients for model re-bake, which is all we need.</li>
 * </ol>
 *
 * <p>Gameplay impact: visually and mechanically identical. The only observable change
 * is that nearby redstone components no longer receive a spurious shape-update
 * notification on every flicker of a nearby JDT machine's ACTIVE bit.
 */
@Mixin(RedstoneControlledBE.class)
public interface RedstoneControlledBEMixin extends RedstoneControlledBE {

    @Overwrite
    default void setRedstoneSettings(int redstoneMode) {
        getRedstoneControlData().redstoneMode = MiscHelpers.RedstoneMode.values()[redstoneMode];
        BlockEntity be = getBlockEntity();
        if (be instanceof com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE baseMachineBE) {
            baseMachineBE.markDirtyClient();
        }
        jdtopt$applyActiveIfChanged(be);
    }

    @Overwrite
    default void evaluateRedstone() {
        if (getRedstoneControlData().checkedRedstone) return;
        BlockEntity be = getBlockEntity();
        Level level = be.getLevel();
        if (level == null) return;

        boolean newRedstoneSignal = level.hasNeighborSignal(be.getBlockPos());
        if (getRedstoneControlData().redstoneMode.equals(MiscHelpers.RedstoneMode.PULSE)
                && !getRedstoneControlData().receivingRedstone
                && newRedstoneSignal) {
            getRedstoneControlData().pulsed = true;
        }
        getRedstoneControlData().receivingRedstone = newRedstoneSignal;
        getRedstoneControlData().checkedRedstone = true;
        jdtopt$applyActiveIfChanged(be);
    }

    /**
     * Applies {@code ACTIVE = isActiveRedstoneTestOnly()} to the backing block IFF:
     * <ul>
     *   <li>the block has an {@link BlockBreakerT1#ACTIVE} property, AND</li>
     *   <li>the current value differs from the computed one.</li>
     * </ul>
     * Uses {@code level.setBlock(pos, state, 2)} to skip the neighbor-update pass.
     */
    @Unique
    default void jdtopt$applyActiveIfChanged(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) return;
        BlockPos pos = be.getBlockPos();
        BlockState blockState = be.getBlockState();
        if (!blockState.hasProperty(BlockBreakerT1.ACTIVE)) return;

        boolean newActive = isActiveRedstoneTestOnly();
        if (blockState.getValue(BlockBreakerT1.ACTIVE) == newActive) return;

        // Flag 2 = BLOCK_UPDATE (client sync), no NOTIFY_NEIGHBORS.
        level.setBlock(pos, blockState.setValue(BlockBreakerT1.ACTIVE, newActive),
                Block.UPDATE_CLIENTS);
    }
}
