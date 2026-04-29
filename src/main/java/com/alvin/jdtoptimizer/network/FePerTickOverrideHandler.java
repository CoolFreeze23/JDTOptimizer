package com.alvin.jdtoptimizer.network;

import com.alvin.jdtoptimizer.api.IFePerTickOverride;
import com.direwolf20.justdirethings.common.blockentities.EnergyTransmitterBE;
import com.direwolf20.justdirethings.common.containers.basecontainers.BaseMachineContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side handler for {@link FePerTickOverridePayload}. Mirrors JDT's own pattern
 * (see {@code EnergyTransmitterPacket}, {@code TickSpeedPacket}): validate that the
 * sender currently has an Energy Transmitter menu open, then apply the setting on that
 * BE via the mixin-injected {@link IFePerTickOverride} interface.
 *
 * <p>This container-menu check is important for security — without it a client could
 * send this packet to modify <em>any</em> transmitter in the world. With it, players can
 * only edit the transmitter they're actively looking at.
 */
public final class FePerTickOverrideHandler {

    private FePerTickOverrideHandler() {}

    public static void handle(FePerTickOverridePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player sender = context.player();
            if (sender == null) return;
            AbstractContainerMenu menu = sender.containerMenu;
            if (!(menu instanceof BaseMachineContainer baseMachineContainer)) return;
            if (!(baseMachineContainer.baseMachineBE instanceof EnergyTransmitterBE be)) return;
            if (!(be instanceof IFePerTickOverride override)) return;
            override.jdtopt_setFePerTickOverride(payload.value());
        });
    }
}
