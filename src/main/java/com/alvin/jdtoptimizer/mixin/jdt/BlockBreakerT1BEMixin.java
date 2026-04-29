package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.BlockBreakerT1BE;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

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
}
