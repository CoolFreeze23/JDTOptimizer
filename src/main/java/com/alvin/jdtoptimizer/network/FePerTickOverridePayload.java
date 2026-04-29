package com.alvin.jdtoptimizer.network;

import com.alvin.jdtoptimizer.JDTOptimizer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server payload that sets (or clears) the per-transmitter FE/tick override on
 * whichever {@code EnergyTransmitterBE} the sending player currently has a menu open on.
 *
 * <p>A {@code value} of {@code 0} or negative clears the override (reverts to the config
 * default). Positive values become the new per-tick cap for that transmitter.
 */
public record FePerTickOverridePayload(int value) implements CustomPacketPayload {

    public static final Type<FePerTickOverridePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(JDTOptimizer.MODID, "fe_per_tick_override")
    );

    public static final StreamCodec<FriendlyByteBuf, FePerTickOverridePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, FePerTickOverridePayload::value,
                    FePerTickOverridePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
