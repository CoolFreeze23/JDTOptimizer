package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT1BE;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Overwrites {@link BlockBreakerT1BE#sendPackets(int, BlockPos, int)} to iterate only
 * the <em>current dimension's</em> player list.
 *
 * <p>Upstream iterates {@code level.getServer().getPlayerList().getPlayers()} — the
 * global, cross-dimension list — and then filters by {@code player.level() == level}.
 * On a multi-dimension server this scans every player in every dimension once per
 * break-progress tick per breaker. Switching to {@code ((ServerLevel) level).players()}
 * pre-filters to the right dimension and returns the same logical set.
 *
 * <p>Gameplay impact: none. Same packets get sent to the same set of players.
 *
 * <h2>Also: dedupe the double {@code getBlockState} in {@code mineBlock}.</h2>
 * Upstream reads {@code level.getBlockState(blockPos)} at the top of
 * {@code mineBlock(...)} into a local, then reads it <em>again</em> two lines later
 * inside the tracker-mismatch check. The world state cannot change between those
 * two reads within a single server tick, so the second read is pure waste. We
 * redirect it to return the local via {@link Local @Local}. In heavy mining scenes
 * where {@code mineBlock} runs for every in-progress break every tick, that's one
 * chunk-section {@code BlockState} fetch per break per tick we no longer pay.
 */
@Mixin(BlockBreakerT1BE.class)
public abstract class BlockBreakerT1BEMixin {

    @Overwrite
    public void sendPackets(int pBreakerId, BlockPos pPos, int pProgress) {
        // `this.level` is inherited from BlockEntity; narrow to ServerLevel so we can
        // use its dimension-local players() list. Breakers only tick server-side, so
        // the cast is always safe at the call-sites of sendPackets.
        BlockBreakerT1BE self = (BlockBreakerT1BE) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        List<ServerPlayer> players = serverLevel.players();
        final int size = players.size();
        for (int i = 0; i < size; i++) {
            ServerPlayer sp = players.get(i);
            if (sp == null || sp.getId() == pBreakerId) continue;
            double d0 = (double) pPos.getX() - sp.getX();
            double d1 = (double) pPos.getY() - sp.getY();
            double d2 = (double) pPos.getZ() - sp.getZ();
            if (d0 * d0 + d1 * d1 + d2 * d2 < 1024.0) {
                sp.connection.send(new ClientboundBlockDestructionPacket(pBreakerId, pPos, pProgress));
            }
        }
    }

    /**
     * Replace the second {@code level.getBlockState(blockPos)} in {@code mineBlock}
     * (ordinal 1 — after the first read is captured into the local {@code blockState})
     * with the already-resolved local. Single-threaded server tick guarantees the world
     * state hasn't changed between the two reads, so returning the captured value is
     * equivalent, avoids a chunk-section lookup, and keeps the existing {@code .equals}
     * comparison semantics.
     */
    @Redirect(
            method = "mineBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    ordinal = 1
            )
    )
    private BlockState jdtopt$reuseMineBlockState(Level level, BlockPos blockPos,
                                                  @Local(ordinal = 0) BlockState captured) {
        return captured;
    }
}
