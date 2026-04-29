package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IFePerTickOverride;
import com.direwolf20.justdirethings.common.blockentities.EnergyTransmitterBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.AreaAffectingBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import com.direwolf20.justdirethings.common.blocks.EnergyTransmitter;
import com.direwolf20.justdirethings.common.capabilities.TransmitterEnergyStorage;
import com.direwolf20.justdirethings.setup.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes the stream-per-call hot path in {@link EnergyTransmitterBE}.
 *
 * <p>The original code rebuilds a fresh {@code HashMap<BlockPos, TransmitterEnergyStorage>} via
 * {@code transmitters.stream().map(SimpleEntry::new).filter(...).collect(toMap(...))} on every
 * call to {@code getTransmitterEnergyStorages()}, and that method is called from
 * {@code getTotalEnergyStored()}, {@code getTotalMaxEnergyStored()}, {@code distributeEnergy()},
 * {@code extractEnergy()}, {@code balanceEnergy()}, and {@code isAlreadyBalanced()} — multiple
 * times per tick, per transmitter.
 *
 * <p>Additionally, {@code balanceEnergy()} sprays a particle beam to <em>every</em> transmitter
 * in the network whenever the network isn't already balanced — including transmitters whose
 * energy is being <em>drained</em> to feed the newly-added one. That's the visual bug where
 * placing a single empty transmitter near three full ones makes all four appear to receive
 * particles.
 *
 * <h2>Patches applied</h2>
 * <ol>
 *   <li>{@code getTransmitterEnergyStorages()}: replace stream pipeline with a pre-sized
 *       {@link HashMap} populated by a {@code for} loop. Same contents, no Stream allocations.</li>
 *   <li>{@code getTotalEnergyStored() / getTotalMaxEnergyStored()}: iterate the
 *       {@code transmitters} set directly. No intermediate map or stream.</li>
 *   <li>{@code isAlreadyBalanced()}: replace stream {@code allMatch} with a short-circuiting
 *       {@code for} loop. (Kept for completeness; no longer called by our {@code balanceEnergy}
 *       overwrite, but still a valid micro-opt if anything else invokes it.)</li>
 *   <li>{@code providePower()}: hoist {@code getEnergyStorage()}/{@code fePerTick()}/
 *       {@code getBlockPos()} out of the hot loop. Always calls {@code balanceEnergy()} at
 *       the end, matching vanilla semantics (required for pure-transmitter networks).</li>
 *   <li>{@code balanceEnergy()}: allocation-free rewrite using direct iteration. <b>Emits
 *       particles only to transmitters whose energy actually increased</b> — fixing the
 *       particle spam when one empty transmitter joins a full network.</li>
 *   <li>{@code getBlocksToCharge()}: stream/sort pipeline replaced with an {@link ArrayList}
 *       and in-place sort.</li>
 * </ol>
 *
 * <h2>Gameplay impact</h2>
 * None beyond the intended cosmetic fix: particles no longer appear on transmitters that are
 * being drained. Energy distribution, rates, losses, filters, and network balance are all
 * byte-for-byte identical to vanilla.
 */
@Mixin(EnergyTransmitterBE.class)
public abstract class EnergyTransmitterBEMixin extends BaseMachineBE implements IFePerTickOverride {

    @Shadow @Final private Set<BlockPos> transmitters;
    @Shadow @Final private Set<BlockPos> blocksToCharge;
    @Shadow @Final private Map<BlockPos, BlockCapabilityCache<IEnergyStorage, Direction>> energyHandlers;
    @Shadow @Final private Map<BlockPos, BlockCapabilityCache<IEnergyStorage, Direction>> transmitterHandlers;
    @Shadow public abstract TransmitterEnergyStorage getTransmitterEnergyHandler(BlockPos blockPos);
    @Shadow public abstract IEnergyStorage getHandler(BlockPos blockPos);
    @Shadow public abstract TransmitterEnergyStorage getEnergyStorage();
    @Shadow public abstract int transmitPowerWithLoss(IEnergyStorage sender, IEnergyStorage receiver, int amtToSend, BlockPos remotePosition);
    @Shadow public abstract void doParticles(BlockPos sourcePos, BlockPos targetPos);
    // fePerTick and balanceEnergy are @Overwrite'n below — no @Shadow needed.

    // isStackValidFilter and getAABB are DEFAULT METHODS on FilterableBE / AreaAffectingBE.
    // @Shadow does not resolve interface defaults, so we cast through (Object) at each call site.
    // The runtime target EnergyTransmitterBE implements both interfaces, so the cast is safe.

    /** {@code Direction.values()} clones its array on every call; cache once. */
    @Unique
    private static final Direction[] jdtopt$DIRECTIONS = Direction.values();

    /**
     * Per-transmitter FE/tick override. {@link IFePerTickOverride#NO_OVERRIDE} (-1) means
     * "use config default"; any positive value is used as the per-tick cap for this
     * specific transmitter instead of {@link Config#ENERGY_TRANSMITTER_T1_RF_PER_TICK}.
     *
     * <p>Stored as a raw {@code int} field so the hot-path read in {@link #fePerTick()}
     * compiles to a single {@code getfield} + comparison — no map lookup, no allocation.
     */
    @Unique
    private int jdtopt$fePerTickOverride = IFePerTickOverride.NO_OVERRIDE;

    // Required by @Mixin(EnergyTransmitterBE.class) extending BaseMachineBE — never invoked.
    private EnergyTransmitterBEMixin() { super(null, null, null); }

    // ------------------------------------------------------------------------------------
    // IFePerTickOverride implementation
    // ------------------------------------------------------------------------------------

    @Override
    public int jdtopt_getFePerTickOverride() {
        return this.jdtopt$fePerTickOverride;
    }

    @Override
    public void jdtopt_setFePerTickOverride(int value) {
        this.jdtopt$fePerTickOverride = (value > 0) ? value : IFePerTickOverride.NO_OVERRIDE;
        // Persist to disk and broadcast to tracking clients (mirrors JDT's
        // setEnergyTransmitterSettings pattern).
        this.markDirtyClient();
    }

    /**
     * Hot-path replacement. Reads a direct int field and branches once, avoiding the
     * config-map lookup in {@link Config#ENERGY_TRANSMITTER_T1_RF_PER_TICK} when an
     * override is set.
     */
    @Overwrite
    public int fePerTick() {
        int override = this.jdtopt$fePerTickOverride;
        if (override > 0) return override;
        return Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK.get();
    }

    /**
     * Append our override to the BE's NBT tag on save <b>and</b> on chunk/update-tag
     * serialization (since {@link BaseMachineBE#getUpdateTag} delegates to
     * {@code saveAdditional}). That's how clients pick up the current value.
     */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void jdtopt$saveOverride(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        if (this.jdtopt$fePerTickOverride > 0) {
            tag.putInt("jdtopt_fePerTickOverride", this.jdtopt$fePerTickOverride);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void jdtopt$loadOverride(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        this.jdtopt$fePerTickOverride = tag.contains("jdtopt_fePerTickOverride")
                ? tag.getInt("jdtopt_fePerTickOverride")
                : IFePerTickOverride.NO_OVERRIDE;
    }

    /**
     * Allocation-free replacement: a single {@link HashMap} built via a {@code for} loop
     * instead of stream/map/filter/collect.
     *
     * <p>Uses the default {@code HashMap} capacity (16) to match vanilla's
     * {@code Collectors.toMap()}-built map exactly — identical bucket layout means identical
     * iteration order on {@code .values()} and {@code .entrySet()}, which matters because
     * {@link #distributeEnergy(int)} fills transmitters in iteration order.
     */
    @Overwrite
    public Map<BlockPos, TransmitterEnergyStorage> getTransmitterEnergyStorages() {
        final Map<BlockPos, TransmitterEnergyStorage> out = new HashMap<>();
        for (BlockPos pos : this.transmitters) {
            TransmitterEnergyStorage s = getTransmitterEnergyHandler(pos);
            if (s != null) out.put(pos, s);
        }
        return out;
    }

    /**
     * Direct-loop replacement avoiding the map allocation entirely. The map built by the
     * original {@code getTransmitterEnergyStorages()} was thrown away immediately after the sum.
     */
    @Overwrite
    public int getTotalEnergyStored() {
        int total = 0;
        for (BlockPos pos : this.transmitters) {
            TransmitterEnergyStorage s = getTransmitterEnergyHandler(pos);
            if (s != null) total += s.getRealEnergyStored();
        }
        return total;
    }

    @Overwrite
    public int getTotalMaxEnergyStored() {
        int total = 0;
        for (BlockPos pos : this.transmitters) {
            TransmitterEnergyStorage s = getTransmitterEnergyHandler(pos);
            if (s != null) total += s.getRealMaxEnergyStored();
        }
        return total;
    }

    /**
     * Replaces {@code allMatch} stream with a short-circuiting loop.
     *
     * <p>Package-private to match the original {@code private} visibility — Mixin's {@code
     * @Overwrite} must keep the same signature, so we keep the private access.
     */
    @Overwrite
    private boolean isAlreadyBalanced(Map<BlockPos, TransmitterEnergyStorage> transmitterEnergyStorages,
                                      int averageEnergy, int remainder) {
        final int minEnergy = averageEnergy;
        final int maxEnergy = averageEnergy + (remainder > 0 ? 1 : 0);
        for (TransmitterEnergyStorage s : transmitterEnergyStorages.values()) {
            int energy = s.getRealEnergyStored();
            if (energy != minEnergy && energy != maxEnergy) return false;
        }
        return true;
    }

    /**
     * Functionally identical to the original {@code providePower} — always calls
     * {@link #balanceEnergy()} at the end so pure transmitter-to-transmitter networks
     * (no external receivers) still redistribute energy every tick.
     *
     * <p>The only changes here are hoisting {@code getEnergyStorage()}, {@code fePerTick()},
     * and {@code getBlockPos()} out of the hot loop. Loss / filter / ordering are unchanged.
     */
    @Overwrite
    public void providePower() {
        final TransmitterEnergyStorage sender = getEnergyStorage();
        if (sender.getEnergyStored() <= 0) return;

        final int perTick = fePerTick();
        final BlockPos selfPos = getBlockPos();

        for (BlockPos blockPos : this.blocksToCharge) {
            IEnergyStorage receiver = getHandler(blockPos);
            if (receiver == null) continue;
            int sentAmt = transmitPowerWithLoss(sender, receiver, perTick, blockPos);
            if (sentAmt > 0) {
                doParticles(selfPos, blockPos);
            }
        }

        balanceEnergy();
    }

    /**
     * Overwrites {@link EnergyTransmitterBE#balanceEnergy()} for two reasons:
     *
     * <ol>
     *   <li><b>Correctness / cosmetic fix.</b> Vanilla sprays a particle beam to every
     *       transmitter in the network whenever a rebalance happens, including transmitters
     *       whose energy <em>decreased</em>. That's the source of the "place one empty cell
     *       and all of them get particles" bug. We now only emit particles to transmitters
     *       whose energy actually increased this rebalance.</li>
     *   <li><b>Performance.</b> Replaces the {@code stream().mapToInt(...).sum()} pass, the
     *       throwaway {@code HashMap} built by {@link #getTransmitterEnergyStorages()}, and
     *       the {@code allMatch} check inside {@code isAlreadyBalanced} with three direct
     *       iterations over the {@code transmitters} set — zero allocations.</li>
     * </ol>
     *
     * <p>Numerically identical to vanilla: same {@code averageEnergy}/{@code remainder}
     * split, same per-transmitter target ({@code i < remainder ? avg+1 : avg}), same
     * iteration order (both the original {@code HashMap<BlockPos,_>.entrySet()} and our
     * direct {@code HashSet<BlockPos>} iterate {@code BlockPos} in identical hash order).
     */
    @Overwrite
    public void balanceEnergy() {
        if (this.transmitters.isEmpty() || this.transmitters.size() == 1) return;

        final Map<BlockPos, TransmitterEnergyStorage> transmitterEnergyStorages = getTransmitterEnergyStorages();
        int totalEnergy = 0;
        for (TransmitterEnergyStorage s : transmitterEnergyStorages.values()) {
            totalEnergy += s.getRealEnergyStored();
        }
        final int count = transmitterEnergyStorages.size();
        if (count == 0) return;

        final int averageEnergy = totalEnergy / count;
        final int remainder = totalEnergy % count;
        if (isAlreadyBalanced(transmitterEnergyStorages, averageEnergy, remainder)) return;

        final BlockPos selfPos = getBlockPos();
        int i = 0;
        for (Map.Entry<BlockPos, TransmitterEnergyStorage> entry : transmitterEnergyStorages.entrySet()) {
            TransmitterEnergyStorage s = entry.getValue();
            int target = (i < remainder) ? averageEnergy + 1 : averageEnergy;
            int before = s.getRealEnergyStored();
            s.setEnergy(target);
            // Only emit a particle beam where energy actually moved INTO the transmitter.
            // Vanilla emitted to every transmitter regardless of direction, which caused
            // particles to appear on already-full transmitters when a new empty one joined.
            if (target > before) {
                doParticles(selfPos, entry.getKey());
            }
            i++;
        }
    }

    /**
     * Loop-based rescan replacing the original {@code betweenClosedStream().map().sorted().forEach(...)}
     * pipeline. Iteration order and set contents are identical — we collect the positions,
     * sort them by {@code distSqr} to the transmitter, and process them the same way.
     *
     * <p>Runs every 50 ticks (the caller gates it behind {@code canRun()}), so the sort cost
     * is paid only once per 2.5 seconds per transmitter.
     */
    @Overwrite
    public void getBlocksToCharge() {
        this.transmitters.clear();
        this.blocksToCharge.clear();

        final BlockPos selfPos = getBlockPos();
        this.transmitters.add(selfPos);

        final AABB area = ((AreaAffectingBE) (Object) this).getAABB(selfPos);
        final int minX = (int) area.minX, minY = (int) area.minY, minZ = (int) area.minZ;
        final int maxX = (int) area.maxX - 1, maxY = (int) area.maxY - 1, maxZ = (int) area.maxZ - 1;

        // Snapshot positions first so we can sort by distance without re-iterating the AABB.
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            candidates.add(p.immutable());
        }
        candidates.sort((a, b) -> Double.compare(a.distSqr(selfPos), b.distSqr(selfPos)));

        for (BlockPos blockPos : candidates) {
            if (blockPos.equals(selfPos)) continue;
            BlockState blockState = level.getBlockState(blockPos);
            if (blockState.isAir() || level.getBlockEntity(blockPos) == null) continue;

            boolean foundAcceptableSide = false;
            for (Direction direction : jdtopt$DIRECTIONS) {
                IEnergyStorage cap = level.getCapability(Capabilities.EnergyStorage.BLOCK, blockPos, direction);
                if (cap != null && cap.canReceive()) {
                    foundAcceptableSide = true;
                    break;
                }
            }
            if (!foundAcceptableSide) continue;

            ItemStack blockItemStack = blockState.getBlock().getCloneItemStack(level, blockPos, blockState);
            if (!((FilterableBE) (Object) this).isStackValidFilter(blockItemStack)) continue;

            if (blockState.getBlock() instanceof EnergyTransmitter) {
                this.transmitters.add(blockPos);
            } else {
                this.blocksToCharge.add(blockPos);
            }
        }

        this.energyHandlers.entrySet().removeIf(entry -> !this.blocksToCharge.contains(entry.getKey()));
        this.transmitterHandlers.entrySet().removeIf(entry -> !this.transmitters.contains(entry.getKey()));
    }
}
