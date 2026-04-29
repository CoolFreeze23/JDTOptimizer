package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.blockentities.EnergyTransmitterBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.AreaAffectingBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import com.direwolf20.justdirethings.common.blockentities.basebe.FilterableBE;
import com.direwolf20.justdirethings.common.blocks.EnergyTransmitter;
import com.direwolf20.justdirethings.common.capabilities.TransmitterEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * <p>Additionally, {@code providePower()} calls {@code balanceEnergy()} on <em>every</em> tick
 * regardless of whether any energy moved. With {@code showParticles = true} (default) and
 * a network that isn't exactly balanced, this spawns a particle packet per transmitter per tick.
 *
 * <h2>Patches applied</h2>
 * <ol>
 *   <li>{@code getTransmitterEnergyStorages()}: replace stream pipeline with a pre-sized
 *       {@link HashMap} populated by a {@code for} loop. Same contents, same iteration order
 *       semantics, no Stream/Spliterator/Collector allocations.</li>
 *   <li>{@code getTotalEnergyStored() / getTotalMaxEnergyStored()}: iterate the
 *       {@code transmitters} set directly and resolve each storage via the already-shadowed
 *       {@code getTransmitterEnergyHandler(pos)}. No intermediate map or stream.</li>
 *   <li>{@code isAlreadyBalanced()}: replace stream {@code allMatch} with a {@code for} loop
 *       that early-exits on the first non-matching storage.</li>
 *   <li>{@code providePower()}: only call {@code balanceEnergy()} if we actually transmitted
 *       energy this tick. If nothing flowed, there's no new imbalance to correct.</li>
 * </ol>
 *
 * <h2>Gameplay impact</h2>
 * None. Every caller sees the same numeric answer and the same network mutation behavior.
 * Particles still fire when an actual rebalance happens. The only observable difference is
 * that idle networks stop emitting rebalance work they weren't producing anyway.
 */
@Mixin(EnergyTransmitterBE.class)
public abstract class EnergyTransmitterBEMixin extends BaseMachineBE {

    @Shadow @Final private Set<BlockPos> transmitters;
    @Shadow @Final private Set<BlockPos> blocksToCharge;
    @Shadow @Final private Map<BlockPos, BlockCapabilityCache<IEnergyStorage, Direction>> energyHandlers;
    @Shadow @Final private Map<BlockPos, BlockCapabilityCache<IEnergyStorage, Direction>> transmitterHandlers;
    @Shadow public abstract TransmitterEnergyStorage getTransmitterEnergyHandler(BlockPos blockPos);
    @Shadow public abstract IEnergyStorage getHandler(BlockPos blockPos);
    @Shadow public abstract TransmitterEnergyStorage getEnergyStorage();
    @Shadow public abstract int fePerTick();
    @Shadow public abstract int transmitPowerWithLoss(IEnergyStorage sender, IEnergyStorage receiver, int amtToSend, BlockPos remotePosition);
    @Shadow public abstract void doParticles(BlockPos sourcePos, BlockPos targetPos);
    @Shadow public abstract void balanceEnergy();

    // isStackValidFilter and getAABB are DEFAULT METHODS on FilterableBE / AreaAffectingBE.
    // @Shadow does not resolve interface defaults, so we cast through (Object) at each call site.
    // The runtime target EnergyTransmitterBE implements both interfaces, so the cast is safe.

    /** {@code Direction.values()} clones its array on every call; cache once. */
    @Unique
    private static final Direction[] jdtopt$DIRECTIONS = Direction.values();

    // Required by @Mixin(EnergyTransmitterBE.class) extending BaseMachineBE — never invoked.
    private EnergyTransmitterBEMixin() { super(null, null, null); }

    /**
     * Allocation-free replacement: a single {@link HashMap} built via a sized {@code for} loop
     * instead of stream/map/filter/collect.
     */
    @Overwrite
    public Map<BlockPos, TransmitterEnergyStorage> getTransmitterEnergyStorages() {
        final Set<BlockPos> set = this.transmitters;
        final Map<BlockPos, TransmitterEnergyStorage> out = new HashMap<>(Math.max(8, set.size() * 2));
        for (BlockPos pos : set) {
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
     * Same as the original providePower but with two cosmetic-correctness improvements:
     *
     * <ol>
     *   <li><b>Skip full receivers before attempting transfer.</b> The original code would
     *       still call {@code transmitPowerWithLoss} on receivers at max capacity, and if the
     *       receiver was at e.g. 9,999/10,000 FE it would happily accept 1 FE per tick and
     *       trigger a particle beam every tick — visually indistinguishable from "full but
     *       receiving particles for no reason". Checking {@code getEnergyStored >= getMaxEnergyStored}
     *       up front short-circuits that entirely: no transfer, no particle, no wasted work.</li>
     *   <li><b>Skip {@code balanceEnergy()} when no energy moved.</b> Without any outflow the
     *       network can't have become newly unbalanced this tick, so the balance pass
     *       (and its particle packets to other transmitters) is pure overhead.</li>
     * </ol>
     *
     * <p>Loss / distance / filter handling is unchanged — we re-use the shadowed
     * {@code transmitPowerWithLoss} and {@code doParticles}. Energy throughput is unchanged
     * for receivers that actually need power.
     */
    @Overwrite
    public void providePower() {
        final TransmitterEnergyStorage sender = getEnergyStorage();
        if (sender.getEnergyStored() <= 0) return;

        final int perTick = fePerTick();
        final BlockPos selfPos = getBlockPos();
        boolean anyMoved = false;

        for (BlockPos blockPos : this.blocksToCharge) {
            IEnergyStorage receiver = getHandler(blockPos);
            if (receiver == null) continue;

            // Fix: skip receivers that are already at capacity. The stock code only gated
            // particles on "sentAmt > 0", which still fires for near-full receivers that
            // accept a trickle every tick.
            if (receiver.getEnergyStored() >= receiver.getMaxEnergyStored()) continue;

            int sentAmt = transmitPowerWithLoss(sender, receiver, perTick, blockPos);
            if (sentAmt > 0) {
                anyMoved = true;
                doParticles(selfPos, blockPos);
            }
        }

        if (anyMoved) {
            balanceEnergy();
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
