package com.alvin.jdtoptimizer.cache;

import com.direwolf20.justdirethings.setup.Config;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Centralised lazy cache for hot-path JDT {@code Config.*.get()} reads.
 *
 * <p><b>Why.</b> JDT's {@code Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK.get()},
 * {@code ..._LOSS_PER_BLOCK.get()}, and {@code ..._MAX_RF.get()} are each called
 * multiple times per tick per transmitter — {@code fePerTick()} and {@code
 * getMaxEnergy()} inside the per-tick {@code providePower()} loop, and
 * {@code calculateLoss()} once per target per tick. Each call walks a NightConfig
 * {@code Config} via the {@code ModConfigSpec} accessor, which is measurably slower
 * than a raw volatile {@code int} read.
 *
 * <p><b>How.</b> The first caller of each getter stamps the backing volatile with
 * {@code Config.*.get()}, and every subsequent reader just returns the cached value.
 * A single {@link ModConfigEvent.Reloading} listener registered at mod init bumps a
 * {@code generation} counter that the getters watch, forcing a re-read on the next
 * tick. That covers in-game config screen edits and server {@code /reload}s at full
 * correctness without adding a per-access lock.
 *
 * <p>Gameplay change: none — identical return values, just cached.
 */
public final class ConfigCache {

    private ConfigCache() {}

    /**
     * Bumped on every config reload. Getters compare their locally-captured
     * generation to this value and re-read {@code Config} if they disagree.
     * {@code volatile} is sufficient — we don't care about strict ordering, only
     * "eventually see the new value".
     */
    private static volatile int generation = 0;

    private static volatile int cachedTransmitterFePerTick = -1;
    private static volatile int cachedTransmitterFePerTickGen = -1;

    private static volatile int cachedTransmitterMaxRf = -1;
    private static volatile int cachedTransmitterMaxRfGen = -1;

    private static volatile double cachedTransmitterLossPerBlock = -1;
    private static volatile int cachedTransmitterLossPerBlockGen = -1;

    public static int transmitterFePerTick() {
        int g = generation;
        if (cachedTransmitterFePerTickGen != g) {
            cachedTransmitterFePerTick = Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK.get();
            cachedTransmitterFePerTickGen = g;
        }
        return cachedTransmitterFePerTick;
    }

    public static int transmitterMaxRf() {
        int g = generation;
        if (cachedTransmitterMaxRfGen != g) {
            cachedTransmitterMaxRf = Config.ENERGY_TRANSMITTER_T1_MAX_RF.get();
            cachedTransmitterMaxRfGen = g;
        }
        return cachedTransmitterMaxRf;
    }

    public static double transmitterLossPerBlock() {
        int g = generation;
        if (cachedTransmitterLossPerBlockGen != g) {
            cachedTransmitterLossPerBlock = Config.ENERGY_TRANSMITTER_T1_LOSS_PER_BLOCK.get();
            cachedTransmitterLossPerBlockGen = g;
        }
        return cachedTransmitterLossPerBlock;
    }

    /**
     * Wire the reload listener onto the provided mod event bus. Must be called exactly
     * once, from the mod entry point, before any BE mixin reads a cached value.
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(ConfigCache::onConfigReload);
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        generation++;
    }
}
