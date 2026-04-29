package com.alvin.jdtoptimizer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the JDT Optimizer mod.
 *
 * This mod does not register its own blocks/items/etc. — all behavior is applied
 * via Mixins at class-load time (see {@code jdtoptimizer.mixins.json}).
 */
@Mod(JDTOptimizer.MODID)
public final class JDTOptimizer {
    public static final String MODID = "jdtoptimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public JDTOptimizer(IEventBus modEventBus) {
        LOGGER.info("JDT Optimizer loaded. Mixin patches will be applied to Just Dire Things at class load.");
    }
}
