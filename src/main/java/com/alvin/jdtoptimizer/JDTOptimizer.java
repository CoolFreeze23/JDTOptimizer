package com.alvin.jdtoptimizer;

import com.alvin.jdtoptimizer.cache.ConfigCache;
import com.alvin.jdtoptimizer.network.FePerTickOverrideHandler;
import com.alvin.jdtoptimizer.network.FePerTickOverridePayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the JDT Optimizer mod.
 *
 * <p>Most behavior is applied via Mixins at class-load time (see
 * {@code jdtoptimizer.mixins.json}). This class additionally registers a small
 * client→server packet for the per-transmitter FE/tick override.
 */
@Mod(JDTOptimizer.MODID)
public final class JDTOptimizer {
    public static final String MODID = "jdtoptimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public JDTOptimizer(IEventBus modEventBus) {
        modEventBus.addListener(JDTOptimizer::registerPayloads);
        ConfigCache.register(modEventBus);
        LOGGER.info("JDT Optimizer loaded. Mixin patches will be applied to Just Dire Things at class load.");
    }

    /**
     * Register our custom payloads on the mod bus. Marked {@code optional()} so the
     * mod still loads even if a peer doesn't have this registrar installed (e.g. a
     * vanilla-JDT server + JDTOptimizer client, or vice versa) — in that scenario the
     * override simply can't be edited from the GUI.
     */
    @SubscribeEvent
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1.0").optional();
        registrar.playToServer(
                FePerTickOverridePayload.TYPE,
                FePerTickOverridePayload.STREAM_CODEC,
                FePerTickOverrideHandler::handle
        );
    }
}
