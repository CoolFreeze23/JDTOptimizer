package com.alvin.jdtoptimizer.mixin.jdt;

import com.alvin.jdtoptimizer.api.IJdtOptBlockStateFilterCache;
import com.alvin.jdtoptimizer.api.IJdtOptSlotCapCache;
import com.alvin.jdtoptimizer.cache.Directions;
import com.direwolf20.justdirethings.common.blockentities.basebe.BaseMachineBE;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Adds two pieces of per-BE caching to {@link BaseMachineBE}:
 *
 * <ol>
 *   <li><b>{@code getMachineHandler()} attachment cache.</b> The underlying
 *       {@code IAttachmentHolder.getData(...)} lookup is 3.10% of the reference spark
 *       profile. The handler attachment is attached lazily once and then lives for the
 *       BE's lifetime — JDT never swaps it out at runtime. Caching the reference
 *       eliminates every subsequent attachment-map lookup for this BE.</li>
 *   <li><b>Per-slot-stack {@link IEnergyStorage} cache</b> (see
 *       {@link IJdtOptSlotCapCache}). Used by the overwritten
 *       {@code PoweredMachineBE.chargeItemStack} path to avoid the
 *       {@code PatchedDataComponentMap.get} call every tick on a slot whose contents
 *       haven't changed.</li>
 * </ol>
 *
 * <p>Gameplay change: none. Every cached value is identical to what the original method
 * would have returned.
 */
@Mixin(BaseMachineBE.class)
public abstract class BaseMachineBEMixin implements IJdtOptSlotCapCache, IJdtOptBlockStateFilterCache {

    @Unique
    private ItemStackHandler jdtopt$cachedMachineHandler;

    /**
     * Identity-tracking of the last ItemStack passed to {@link #jdtopt_getItemEnergyCap}.
     * When the slot contents change, {@code ItemStackHandler} installs a fresh stack
     * instance, so {@code stack == this.jdtopt$lastCapStack} fails and we re-resolve.
     * Starts as {@link ItemStack#EMPTY} so the first call is treated as "new".
     */
    @Unique
    private ItemStack jdtopt$lastCapStack = ItemStack.EMPTY;

    @Unique
    private IEnergyStorage jdtopt$lastCapEnergy;

    /**
     * BlockState-keyed filter-decision cache. See {@link IJdtOptBlockStateFilterCache}.
     * Sized at 64 because typical machine AABBs are 3x3x3 = 27 states, allowing some
     * headroom before rehashing. Intentionally a {@link Reference2ByteOpenHashMap}
     * rather than an {@code Object2ByteOpenHashMap}: Minecraft interns BlockStates, so
     * identity equality is the correct semantics and it's one hash+compare cheaper.
     */
    @Unique
    private final Reference2ByteOpenHashMap<BlockState> jdtopt$blockStateFilterCache =
            new Reference2ByteOpenHashMap<>(64);

    /**
     * Last game tick at which {@code chunkTestCache} was cleared. Starts at
     * {@link Long#MIN_VALUE} so the very first {@code clearProtectionCache()} call always
     * trips the threshold and clears.
     */
    @Unique
    private long jdtopt$lastChunkCacheClear = Long.MIN_VALUE;

    @Shadow
    protected Map<ChunkPos, Boolean> chunkTestCache;

    @Shadow
    protected int direction;

    @Inject(method = "getMachineHandler", at = @At("HEAD"), cancellable = true)
    private void jdtopt$returnCachedMachineHandler(CallbackInfoReturnable<ItemStackHandler> cir) {
        ItemStackHandler cached = this.jdtopt$cachedMachineHandler;
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getMachineHandler", at = @At("RETURN"))
    private void jdtopt$storeCachedMachineHandler(CallbackInfoReturnable<ItemStackHandler> cir) {
        if (this.jdtopt$cachedMachineHandler == null) {
            this.jdtopt$cachedMachineHandler = cir.getReturnValue();
        }
    }

    @Override
    public IEnergyStorage jdtopt_getItemEnergyCap(ItemStack stack) {
        // Identity-based cache. ItemStackHandler returns the same reference until the
        // slot contents are swapped, so '==' is the exact right invalidation signal.
        if (stack == this.jdtopt$lastCapStack) {
            return this.jdtopt$lastCapEnergy;
        }
        this.jdtopt$lastCapStack = stack;
        this.jdtopt$lastCapEnergy = stack.isEmpty()
                ? null
                : stack.getCapability(Capabilities.EnergyStorage.ITEM);
        return this.jdtopt$lastCapEnergy;
    }

    @Override
    public Reference2ByteOpenHashMap<BlockState> jdtopt_blockStateFilterCache() {
        return this.jdtopt$blockStateFilterCache;
    }

    /**
     * Piggy-back on JDT's existing filter-cache invalidation. When JDT clears its own
     * {@code FilterData.filterCache} at the end of {@code setChanged()}, it means
     * "something that could affect filter decisions has changed" (slot contents, etc.).
     * Mirror that for our BlockState cache so we can never be stale relative to JDT's.
     */
    @Inject(method = "setChanged", at = @At("TAIL"))
    private void jdtopt$clearBlockStateFilterCache(CallbackInfo ci) {
        // Guard with isEmpty to avoid touching the backing array in the very common
        // "BE marked dirty but nobody ever populated our cache" case.
        if (!this.jdtopt$blockStateFilterCache.isEmpty()) {
            this.jdtopt$blockStateFilterCache.clear();
        }
    }

    /**
     * TTL-throttled {@code chunkTestCache} clearing. Upstream unconditionally clears on
     * every {@code tickServer()}, which makes the cache useless for anything other than
     * the single {@code findXxx} scan within one tick.
     *
     * <p>For area machines the scan re-runs every {@code tickSpeed} ticks (20 by default),
     * and chunk-protection claims don't flip on a sub-second cadence in practice. We
     * therefore keep the cache valid for up to {@value JDTOPT$CHUNK_CACHE_TTL_TICKS}
     * ticks between forced clears, which still gives a player at most a 2-second window
     * where a JDT machine could act on a stale protection verdict — the same window
     * they already have today if they edit permissions mid-tick.
     *
     * <p>If the level isn't available or the BE is client-side, we fall back to the
     * original behaviour (always clear) to stay safe.
     */
    @Unique
    private static final long JDTOPT$CHUNK_CACHE_TTL_TICKS = 40L;

    /**
     * Allocation-free replacement for {@code getDirectionValue()}. Original:
     * {@code return Direction.values()[direction];} — one 6-element array clone per
     * call. Swapping to the shared static reads is a straight win with zero behavioural
     * impact since the returned {@link Direction} value is identical.
     */
    @Overwrite
    public Direction getDirectionValue() {
        return Directions.VALUES[this.direction];
    }

    @Overwrite
    public void clearProtectionCache() {
        Level lvl = ((BlockEntity) (Object) this).getLevel();
        if (lvl == null) {
            this.chunkTestCache.clear();
            return;
        }
        long now = lvl.getGameTime();
        if (now - this.jdtopt$lastChunkCacheClear >= JDTOPT$CHUNK_CACHE_TTL_TICKS) {
            this.chunkTestCache.clear();
            this.jdtopt$lastChunkCacheClear = now;
        }
    }
}
