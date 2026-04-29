package com.alvin.jdtoptimizer.cache;

import net.minecraft.core.Direction;

/**
 * Shared, immutable {@link Direction#values()} snapshot.
 *
 * <p>Calling {@code Direction.values()} clones a six-element array on every invocation
 * (Java language requires enum {@code values()} to return a fresh array so callers can
 * safely mutate it). JDT has several per-tick or per-scan loops — generator power
 * distribution, sensor neighbour scans, transmitter AABB sweeps — that iterate
 * {@code Direction.values()} directly in their {@code for-each} headers. Routing them
 * through this constant eliminates the per-call allocation. {@link Direction} itself
 * is a singleton enum so sharing the array reference is safe; callers must not mutate
 * it.
 */
public final class Directions {

    private Directions() {}

    /**
     * The canonical six-element array: {@code DOWN, UP, NORTH, SOUTH, WEST, EAST}.
     * Order is stable across Minecraft versions per {@link Direction}'s declaration
     * order.
     */
    public static final Direction[] VALUES = Direction.values();
}
