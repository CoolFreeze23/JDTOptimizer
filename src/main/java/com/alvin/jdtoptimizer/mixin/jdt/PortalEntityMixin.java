package com.alvin.jdtoptimizer.mixin.jdt;

import com.direwolf20.justdirethings.common.entities.PortalEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick optimisations for {@link PortalEntity}.
 *
 * <h2>Why this matters</h2>
 * Every active {@code PortalEntity} runs on the server tick loop twice per second (yes,
 * every tick), and active worlds can easily have tens of them (each portal-gun shot
 * spawns a pair, plus advanced portals sticking around for 100 ticks before expiring).
 * Vanilla's {@code tick()} does three things that are individually cheap but add up:
 *
 * <ol>
 *   <li>{@code refreshDimensions()} — recomputes the bounding box from {@code entityData}.
 *       The inputs ({@code DIRECTION}, {@code ALIGNMENT}) are set exactly once in the
 *       constructor (or once in {@code readAdditionalSaveData} on load), but JDT calls
 *       {@code refreshDimensions()} every single tick. Running it once at first-tick
 *       is functionally identical.</li>
 *   <li>{@code teleportCollidingEntities()} — scans
 *       {@code level.getEntities(this, this.getBoundingBox())}.</li>
 *   <li>{@code captureVelocity()} — scans
 *       {@code level.getEntities(this, getVelocityBoundingBox())} (a strict superset of
 *       the inner BB).</li>
 * </ol>
 *
 * <p>{@code Level.getEntities(Entity except, AABB area)} is the dominant cost here — it
 * walks the entity-section grid. Since the inner BB is contained in the velocity BB,
 * we can scan once with the larger BB and classify each entity locally, shaving one
 * full entity-grid walk per portal per tick.
 *
 * <h2>Semantics preserved</h2>
 * The consolidated loop preserves upstream ordering: teleports happen first (so
 * {@code calculateVelocity} reads {@code entityLastPosition} entries from <em>previous</em>
 * ticks, never from this tick), and velocity capture runs only for entities that
 * remained in this portal's level (i.e., weren't teleported). An entity that fails to
 * teleport (e.g., {@code teleportTo} returns false, or {@code isValidEntity} rejects it)
 * still participates in the velocity pass just as it does upstream.
 *
 * <p>Gameplay change: <b>none</b>. Net effect: one fewer {@code getEntities} scan per
 * portal per tick and one fewer {@code makeBoundingBox} computation per portal per tick.
 */
@Mixin(PortalEntity.class)
public abstract class PortalEntityMixin extends Entity {

    // @Shadow-ed state
    @Shadow public abstract PortalEntity getLinkedPortal();
    @Shadow public abstract AABB getVelocityBoundingBox();
    @Shadow public abstract void teleport(Entity entity);
    @Shadow public abstract boolean isValidEntity(Entity entity);
    @Shadow public abstract void tickCooldowns();
    @Shadow public abstract void tickDying();
    @Shadow public final Map<UUID, Integer> entityVelocityCooldowns = null;
    @Shadow public final Map<UUID, Vec3> entityLastPosition = null;
    @Shadow public final Map<UUID, Vec3> entityLastLastPosition = null;

    /**
     * One-shot flag guarding the first-tick {@code refreshDimensions()} call. Upstream
     * needs that call because {@code PortalEntity} sets {@code DIRECTION}/{@code ALIGNMENT}
     * in its constructor <em>after</em> the super-constructor computed the initial
     * bounding box — so the BB is stale until {@code refreshDimensions()} runs. After
     * that first refresh the inputs never change, so we can stop recomputing.
     *
     * <p>Not persisted — a freshly-loaded portal re-runs the first-tick refresh, which
     * is exactly what we want because {@code readAdditionalSaveData} is the other place
     * {@code DIRECTION}/{@code ALIGNMENT} can be written.
     */
    @Unique
    private boolean jdtopt$dimsRefreshed = false;

    protected PortalEntityMixin(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    /**
     * Overwrite of {@link PortalEntity#tick()}. Drops the per-tick
     * {@code refreshDimensions()} after the first call and folds the two server-side
     * {@code getEntities} scans into one.
     */
    @Overwrite
    @Override
    public void tick() {
        super.tick();

        if (!this.jdtopt$dimsRefreshed) {
            this.refreshDimensions();
            this.jdtopt$dimsRefreshed = true;
        }

        if (!level().isClientSide) {
            tickCooldowns();
            PortalEntity linked = getLinkedPortal();
            if (linked != null) {
                jdtopt$teleportAndCapture();
            }
        }
        tickDying();
    }

    /**
     * Consolidated server-side scan. Does the work of the vanilla
     * {@code teleportCollidingEntities()} + {@code captureVelocity()} pair in a single
     * {@code getEntities} call against the (larger) velocity bounding box.
     *
     * <p>Two passes are kept on the resulting list:
     * <ul>
     *   <li><b>Pass 1 (teleport):</b> entities whose BB intersects our inner BB get
     *       {@link PortalEntity#teleport} called on them. This preserves the upstream
     *       invariant that {@code teleport -> calculateVelocity} reads
     *       {@code entityLastPosition} entries written by <em>previous</em> ticks.</li>
     *   <li><b>Pass 2 (capture):</b> entities still in this portal's level (i.e., weren't
     *       successfully teleported) have their current position stored for the next
     *       tick's velocity calculation. Entities that left the level via {@code teleport}
     *       are skipped by the {@code entity.level() != this.level()} guard, which
     *       mirrors upstream's behaviour where the second {@code getEntities} call
     *       naturally excludes them.</li>
     * </ul>
     */
    @Unique
    private void jdtopt$teleportAndCapture() {
        AABB velocityBB = getVelocityBoundingBox();
        List<Entity> entities = level().getEntities(this, velocityBB);
        if (entities.isEmpty()) return;

        AABB innerBB = this.getBoundingBox();
        Level thisLevel = this.level();

        for (Entity entity : entities) {
            if (entity == this) continue;
            if (!isValidEntity(entity)) continue;
            if (innerBB.intersects(entity.getBoundingBox())) {
                teleport(entity);
            }
        }

        for (Entity entity : entities) {
            if (entity == this) continue;
            if (entity.level() != thisLevel) continue;
            if (!isValidEntity(entity)) continue;
            UUID id = entity.getUUID();
            Vec3 currentPos = entity.position();
            Vec3 prevLast = entityLastPosition.get(id);
            if (prevLast != null) {
                entityLastLastPosition.put(id, prevLast);
            }
            entityLastPosition.put(id, currentPos);
            entityVelocityCooldowns.put(id, 10);
        }
    }
}
