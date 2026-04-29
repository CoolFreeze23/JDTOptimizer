package com.alvin.jdtoptimizer.api;

/**
 * Mixin-injected interface on {@code EnergyTransmitterBE} that exposes a per-transmitter
 * FE/tick override.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code -1} (default) → use the global config value
 *       {@code Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK}.</li>
 *   <li>Any positive integer → use that value for {@code fePerTick()} on this specific
 *       transmitter.</li>
 *   <li>Zero or negative (other than the {@code -1} sentinel) → treated as "reset to
 *       default" by the setter.</li>
 * </ul>
 *
 * <p>The lookup is a single field read plus one branch, so the hot path
 * ({@code providePower → transmitPowerWithLoss → fePerTick}) stays allocation-free and
 * branch-predictable.
 */
public interface IFePerTickOverride {

    /** Sentinel that means "no override, use config default". */
    int NO_OVERRIDE = -1;

    /**
     * @return the current override, or {@link #NO_OVERRIDE} if this transmitter is using
     *         the config default.
     */
    int jdtopt_getFePerTickOverride();

    /**
     * Set (or clear) the override for this transmitter. Any {@code value <= 0} clears the
     * override (reverts to config default). Implementations must call
     * {@code markDirtyClient()} so the new value syncs to nearby clients.
     */
    void jdtopt_setFePerTickOverride(int value);
}
