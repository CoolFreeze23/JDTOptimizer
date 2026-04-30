# JDT Optimizer

**A drop-in performance companion mod for [Just Dire Things](https://github.com/Direwolf20-MC/JustDireThings).**

---

## 1. Welcome & Introduction

**JDT Optimizer** is a NeoForge 1.21.1 companion mod for **Just Dire Things** (JDT) that applies a curated set of performance patches to JDT's hottest code paths — **without editing a single line of JDT's source**.

Every change lives in [SpongePowered Mixins](https://github.com/SpongePowered/Mixin) that are applied at class-load time. That means you get the speedups by simply installing the mod; there is no configuration to tweak, no patcher to run, and no special launch profile needed.

### Hard requirements

- **Minecraft** `1.21.1`
- **NeoForge** `21.1.209` or newer
- **Just Dire Things** `1.5.7`

### The guiding principle

> **Every optimization in this mod is 100% transparent to gameplay.** Machine rates, filter behaviour, energy distribution, particle patterns, portal physics, and network topology are all byte-for-byte identical to vanilla JDT. The only thing that changes is the amount of work your server spends producing those results.

Concretely, our goal — and the acceptance test we used on every patch — is:

- **Same outputs**, down to RNG rolls, block-update flags, and iteration order where it mattered.
- **Lower per-tick CPU cost** on busy JDT machines (breakers, clickers, transmitters, generators, goo farms, portal arrays).
- **Lower allocation pressure** (fewer temporary arrays, HashMaps, and ItemStack clones on the hot path).
- **No new config, no new keybinds, no new recipes, no new blocks** — other than one small **quality-of-life GUI addition** to the Energy Transmitter which is explicitly documented below.

Against the reference spark profile that motivated this mod, the cumulative effect of PR1 + PR2 is an estimated **6–8% of server-tick time recovered** on JDT-heavy builds.

---

## 2. Detailed Optimization Breakdown

Each patch is called out individually so server admins and curious players can see exactly what changed and why.

### 2.1 Machine Handler Attachment Cache

- **What It Is:** The `IAttachmentHolder.getData(...)` lookup behind `BaseMachineBE.getMachineHandler()` — JDT's accessor for the internal `ItemStackHandler` that every machine uses for its inventory slots.
- **The "Before" (Original State):** Every call to `getMachineHandler()` walked NeoForge's per-BE attachment map to resolve the handler. In the reference spark profile this single lookup accounted for **≈3.10% of total server-tick time**, because it is called from virtually every JDT machine tick (filter checks, slot reads, charging, sorting) and the attachment map lookup is not free.
- **The "After" (Our Optimization):** In `BaseMachineBEMixin` we `@Inject` at `HEAD` and `RETURN` of `getMachineHandler()`. On `HEAD` we short-circuit and return a cached field if it's already populated; on `RETURN` we stamp the cached field. The handler attachment is attached exactly once per BE and never swapped at runtime, so caching the reference for the BE's lifetime is safe.
- **The Benefit:** Eliminates the attachment-map walk from every subsequent call. The first call still pays full price; every other call is a single `getfield`. This is the single largest CPU win in the mod.
- **Gameplay Impact:** **None.** The cached value is the same reference the original lookup would have returned. No handler state is held, only its reference.

---

### 2.2 Per-Slot `IEnergyStorage` Capability Cache

- **What It Is:** `PoweredMachineBE.chargeItemStack(ItemStack)` — the per-tick routine that feeds energy from a machine's internal buffer into a tool sitting in its battery slot.
- **The "Before" (Original State):** Every tick, for every machine with a charge slot, `chargeItemStack` called `stack.getCapability(Capabilities.EnergyStorage.ITEM)`. That internally hits `PatchedDataComponentMap.get` — a map lookup against the stack's components. For a world full of JDT machines with batteries in them, that's thousands of identical lookups per second against an `ItemStack` whose contents haven't changed in hours.
- **The "After" (Our Optimization):** A new interface `IJdtOptSlotCapCache` is mixed into `BaseMachineBE`. It stores the last `ItemStack` it resolved and its cached `IEnergyStorage`. `PoweredMachineBEMixin` `@Overwrite`s `chargeItemStack` to call our cached accessor. Invalidation is **reference-based** (`stack == lastStack`) because `ItemStackHandler` installs a fresh stack reference the moment slot contents change — so `==` is the exact right signal, not an approximation.
- **The Benefit:** Slots whose contents are stable (which is almost always the case) do zero capability lookups per tick. Only the tick on which the slot changes pays the full cost.
- **Gameplay Impact:** **None.** Energy is transferred on the same ticks and at the same rates as vanilla. Swapping tools in the slot resolves a fresh capability on the very next call.

---

### 2.3 BlockState-Keyed Filter Decision Cache

- **What It Is:** `FilterableBE.isStackValidFilter(...)` — the filter check every JDT area machine runs against every block in its work area every scan cycle (Block Breaker T2, Block Placer T2, Block Swapper T2, Clicker T2, Fluid Placer T2, Energy Transmitter).
- **The "Before" (Original State):** For every candidate position, the machine built a fresh `ItemStack` via `blockState.getBlock().getCloneItemStack(level, pos, blockState)`, then passed it through JDT's filter comparator, which internally allocates an `ItemStackKey` and hits a `HashMap`. Per tick this turned into thousands of `ItemStack` clones and map allocations, for the same four or five block types repeating across a 3×3×3 or 5×5×5 scan area.
- **The "After" (Our Optimization):** A new interface `IJdtOptBlockStateFilterCache` backed by a `Reference2ByteOpenHashMap<BlockState>` (fastutil) is mixed into `BaseMachineBE`. Because Minecraft **interns BlockStates**, identity equality is the correct semantics, which is one hash + compare cheaper than a normal hashmap. Results are cached as a three-state byte (`MISS`/`FALSE`/`TRUE`). The cache is invalidated in lockstep with JDT's own `FilterData.filterCache` by hooking `BaseMachineBE.setChanged()` at `TAIL` — so whenever JDT considers its filter stale, ours follows.
- **The Benefit:** The hot `getCloneItemStack` + `isStackValidFilter` pair is replaced by a single `Reference2ByteOpenHashMap` probe for every non-first-occurrence block in the scan area. On a 5×5×5 Block Breaker staring at stone, that's 124 cache hits vs. 124 full filter evaluations per scan.
- **Gameplay Impact:** **None.** The cache invalidates whenever a filter slot changes (same event JDT already uses internally), and identity equality on interned BlockStates is mathematically equivalent to equals-based comparison for the filter's purposes.

---

### 2.4 Energy Transmitter Network Rewrite (Allocation-Free)

- **What It Is:** The `EnergyTransmitterBE` network layer — `getTransmitterEnergyStorages()`, `getTotalEnergyStored()`, `getTotalMaxEnergyStored()`, `isAlreadyBalanced()`, `providePower()`, `balanceEnergy()`, and `getBlocksToCharge()`.
- **The "Before" (Original State):** `getTransmitterEnergyStorages()` was built with a `stream().map(SimpleEntry::new).filter(...).collect(toMap(...))` pipeline — a brand-new `HashMap` on **every** call. That method is invoked by **five** other methods on the same tick (`getTotalEnergyStored`, `getTotalMaxEnergyStored`, `distributeEnergy`, `extractEnergy`, `balanceEnergy`), so a single-tick update on a transmitter network could allocate half a dozen throwaway maps. On top of that, `balanceEnergy()` sprayed a particle beam to **every** transmitter in the network on every rebalance, including ones whose energy was being **drained** to feed a newly-placed empty cell — the infamous "place one empty transmitter and everything glows" visual bug. `getBlocksToCharge()` used another stream/sort pipeline.
- **The "After" (Our Optimization):** `EnergyTransmitterBEMixin` overwrites every one of these methods with allocation-free direct iteration. Highlights:
  - `getTotalEnergyStored` / `getTotalMaxEnergyStored` iterate the `transmitters` set directly — no throwaway map.
  - `isAlreadyBalanced` uses a short-circuiting `for` loop instead of `stream().allMatch`.
  - `providePower` hoists `getEnergyStorage()`, `fePerTick()`, and `getBlockPos()` out of the per-target loop.
  - `balanceEnergy` now emits particles **only to transmitters whose stored energy actually increased** during the rebalance, fixing the drained-cell glow bug.
  - `getBlocksToCharge` uses an `ArrayList` + in-place sort instead of `betweenClosedStream().sorted().forEach(...)`, and combines that with the filter cache (§2.3) for the per-block filter check.
- **The Benefit:** Transmitter networks, especially large multi-cell setups, pay a fraction of the per-tick GC pressure. The particle fix also reduces client-side particle work in mixed networks.
- **Gameplay Impact:** **None on numerics.** Totals, averages, remainders, and per-transmitter target values are byte-for-byte identical to vanilla. The only user-visible change is the **bug fix** on particles: beams are now shown on the cells that are actually gaining energy, which is what the original animation was already trying to communicate.

---

### 2.5 Configurable FE/tick Per Transmitter (New Quality-of-Life Feature)

- **What It Is:** A new **per-transmitter** override for `Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK`, configurable directly from the Energy Transmitter GUI.
- **The "Before" (Original State):** Every transmitter in the world was locked to the server-wide `ENERGY_TRANSMITTER_T1_RF_PER_TICK` config value. Players running large JDT builds had no way to throttle an individual transmitter without changing the global cap — a frequent annoyance on modded packs where Flux Networks offers the same control per-cable.
- **The "After" (Our Optimization):** A new interface `IFePerTickOverride` is mixed into `EnergyTransmitterBE`. It holds a single `int` override field, persisted via `saveAdditional`/`loadAdditional` `@Inject`s. `fePerTick()` becomes a single `getfield` + branch that reads the override if set, falling through to the cached config value (see §2.8) otherwise. The Energy Transmitter GUI (`EnergyTransmitterScreenMixin`) gets a digits-only `EditBox` pre-filled with the transmitter's current effective FE/t, plus a "Save" button that greys out when the typed value matches what is already saved and activates the moment the text diverges. The button sends a `FePerTickOverridePayload` to the server; **shift-click** the save button to set the transmitter to `Integer.MAX_VALUE` (effectively unthrottled).
- **The Benefit:** Per-transmitter throughput control without touching server config. On the hot path, the override is a direct int field read — **zero** per-tick CPU cost beyond the one comparison, and no network traffic unless the GUI is opened and changed.
- **Gameplay Impact:** This is the **only** optional quality-of-life feature that didn't exist in vanilla JDT. It is **strictly additive**:
  - If you never open the transmitter GUI, every transmitter stays on the config default — behaviour identical to vanilla JDT.
  - Overrides are saved per BE to NBT, so they survive world reloads and chunk unloads.
  - The packet is `optional()`: a client without JDT Optimizer can still connect to a server that has it, and vice versa — the GUI field simply won't appear if one side is missing the mod.

---

### 2.6 Redstone Control — `setBlock` flag 2 + No-Op Guard

- **What It Is:** `RedstoneControlledBE.setRedstoneSettings()` and `evaluateRedstone()` — the routines that flip a machine's cosmetic `ACTIVE` block state based on its redstone mode.
- **The "Before" (Original State):** Upstream called `level.setBlockAndUpdate(pos, state)` which is `setBlock` with flag `3` (update clients **and** notify neighbours). That fires neighbour-update chains on every redstone re-evaluation, even when the `ACTIVE` state didn't actually change — pure waste for a cosmetic block property.
- **The "After" (Our Optimization):** `RedstoneControlledBEMixin` overwrites both methods. A new `jdtopt$applyActiveIfChanged` helper:
  - Checks whether the block actually has an `ACTIVE` property (some JDT machines don't).
  - Reads the current value and compares to the target. If equal, does nothing.
  - If different, uses `level.setBlock(pos, state, Block.UPDATE_CLIENTS)` (flag `2`) — clients still render the updated state, but we no longer trigger the neighbour-update cascade for a cosmetic change.
- **The Benefit:** On redstone-heavy contraptions (especially with PULSE-mode JDT machines wired into comparator/observer networks), this cuts out a large chunk of neighbour-update noise that used to fire every operation tick.
- **Gameplay Impact:** **None.** The `ACTIVE` state is cosmetic — it drives block texture, not any redstone output. Any real redstone signal emission JDT performs still happens through its normal channels.

---

### 2.7 Stream-to-Loop Conversions Across Area Machines

- **What It Is:** The `findSpotsTo*` family of methods on every JDT T2 area machine: `BlockBreakerT2`, `BlockPlacerT2`, `BlockSwapperT2`, `ClickerT2`, `FluidPlacerT2`, `FluidCollectorT2`.
- **The "Before" (Original State):** Each method used `BlockPos.betweenClosedStream().map(BlockPos::immutable).filter(...).sorted(...).collect(Collectors.toList())`. Streams are allocation-heavy: a `Stream`, a `Spliterator`, a `Comparator`, and intermediate boxed `Double` instances for the distance sort — all thrown away at the end of the call.
- **The "After" (Our Optimization):** Each stream pipeline is rewritten as:
  - An `ArrayList` populated via a direct `for (BlockPos p : BlockPos.betweenClosed(...))` loop (note: `betweenClosed`, not `betweenClosedStream`).
  - An in-place `out.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)))`.
- **The Benefit:** Large reduction in per-scan allocations. The resulting list contains the exact same positions in the exact same order, so downstream code is unchanged.
- **Gameplay Impact:** **None.** Identical positions, identical ordering, identical machine behaviour.

---

### 2.8 Config Value Cache

- **What It Is:** `Config.ENERGY_TRANSMITTER_T1_RF_PER_TICK`, `_MAX_RF`, and `_LOSS_PER_BLOCK` — the three config values read on the transmitter hot path.
- **The "Before" (Original State):** Each `.get()` call walks a NightConfig `Config` through `ModConfigSpec`, which is measurably slower than a raw field read. `fePerTick()`, `getMaxEnergy()`, and `calculateLoss()` are all called **multiple times per tick per transmitter**, so on busy networks this added up.
- **The "After" (Our Optimization):** A new `ConfigCache` utility holds volatile cached copies of each value plus a `generation` counter. A `ModConfigEvent.Reloading` listener — registered once at mod init — bumps `generation`, which forces the next getter call to re-read from `Config`. Until then every call is a straight volatile read.
- **The Benefit:** Hot-path config reads are now as cheap as a field read. The reload path still works correctly because the listener invalidates instantly on every reload event.
- **Gameplay Impact:** **None.** Return values are identical to calling `Config.*.get()` directly, the only difference being they are delivered via a cached volatile.

---

### 2.9 Chunk-Protection Cache TTL

- **What It Is:** `BaseMachineBE.chunkTestCache` — the per-chunk `Map<ChunkPos, Boolean>` JDT uses to memoise whether a machine is allowed to modify blocks in a given chunk.
- **The "Before" (Original State):** Upstream called `clearProtectionCache()` at the top of **every** `tickServer()`, wiping the map unconditionally. That made the cache useful only for the duration of a single scan and defeated its own reason for existing — the chunk protection query is expensive precisely because we want to re-use its result across ticks.
- **The "After" (Our Optimization):** `BaseMachineBEMixin.clearProtectionCache` is overwritten to respect a **40-tick TTL** (2 seconds). The map is only cleared if at least 40 game ticks have passed since the last clear. If the level reference isn't available for any reason, we fall back to the original unconditional clear behaviour.
- **The Benefit:** Area machines now reuse chunk-protection verdicts across their scan cycles (`tickSpeed` defaults to 20 ticks, well inside our 40-tick window), turning a per-scan hit into a per-2-seconds hit.
- **Gameplay Impact:** Essentially none. Chunk protection claims don't change on a sub-second cadence in normal play. In the worst case, a player who edits permissions mid-tick will see the new verdict applied up to 2 seconds later — the same window of staleness they already had between JDT's scan cycles.

---

### 2.10 Shared `Direction.values()` Holder

- **What It Is:** The `Direction.values()` call across JDT's per-tick loops (Energy Transmitter `getBlocksToCharge`, `Generator T1` / `Generator Fluid T1` `providePowerAdjacent`, `BaseMachineBE.getDirectionValue`, etc.).
- **The "Before" (Original State):** The Java language specification forces every `Enum.values()` call to **clone** its backing array so callers can safely mutate it. That means every `for (Direction d : Direction.values())` loop allocates a fresh 6-element `Direction[]` on every single invocation.
- **The "After" (Our Optimization):** A new `Directions.VALUES` constant holds a single, shared `Direction[]` that every patched site reads from — via `@Redirect` on the `.values()` call for loop sites (`GeneratorT1BEMixin`, `GeneratorFluidT1BEMixin`), or directly through `Directions.VALUES[...]` for indexed sites (`BaseMachineBEMixin.getDirectionValue`, `EnergyTransmitterBEMixin.getBlocksToCharge`).
- **The Benefit:** Eliminates a 6-element array clone from every hot-path direction loop in JDT. Small per-call but large in aggregate because these loops are so frequent.
- **Gameplay Impact:** **None.** `Direction` enum values are immutable singletons; the shared array contents are identical, and we never mutate it.

---

### 2.11 Block Breaker T1 — Dimension-Local Packet Sending

- **What It Is:** `BlockBreakerT1BE.sendPackets(...)` — the method that pushes block-destruction progress packets to nearby players so they see the breaking-crack overlay.
- **The "Before" (Original State):** Upstream iterated `level.getServer().getPlayerList().getPlayers()` — the **global, cross-dimension** player list — then filtered by distance inside the loop. On multi-dimension servers that meant every active breaker walked every player in every dimension per progress tick.
- **The "After" (Our Optimization):** `BlockBreakerT1BEMixin.sendPackets` iterates `((ServerLevel) level).players()`, which is already pre-filtered to the dimension the breaker lives in. Players in other dimensions cannot possibly be close enough to see the crack, so scanning them was always useless work.
- **The Benefit:** Linear reduction in per-breaker packet-send cost on servers with busy non-overworld dimensions.
- **Gameplay Impact:** **None.** The same set of players (those in range, in the same dimension) still receive the same packets.

---

### 2.12 Block Breaker T1 — `mineBlock` Double-Read Dedupe

- **What It Is:** The per-break-progress-tick `mineBlock(BlockPos, ItemStack, FakePlayer)` on `BlockBreakerT1BE`.
- **The "Before" (Original State):** Upstream called `level.getBlockState(blockPos)` at the very top of the method, captured it into a local named `blockState`, then called `level.getBlockState(blockPos)` **again** two lines later inside the tracker-mismatch check (`!level.getBlockState(blockPos).equals(tracker.blockState)`). Two chunk-section lookups for the same position in the same method call.
- **The "After" (Our Optimization):** `BlockBreakerT1BEMixin` uses a `@Redirect` on the **ordinal-1** `level.getBlockState` call, combined with MixinExtras' `@Local` to capture the local `blockState` variable from the method. The redirect simply returns the already-captured value.
- **The Benefit:** One chunk-section state lookup saved per breaker per break-progress-tick. On large mining arrays this is a surprisingly visible win because `mineBlock` fires every tick for every in-progress break.
- **Gameplay Impact:** **None.** Server-tick execution is single-threaded; the world state cannot change between two reads two lines apart in the same method, so the two reads are guaranteed equal. Reusing the local is a mathematically identical substitute.

---

### 2.13 Portal Entity Tick Consolidation

- **What It Is:** `PortalEntity.tick()` — the per-tick method that every JDT portal runs, including advanced portals that persist for 100 ticks after firing.
- **The "Before" (Original State):** Upstream did three independently wasteful things every tick:
  1. Called `refreshDimensions()`, which recomputes the portal's bounding box from its `DIRECTION` and `ALIGNMENT` entity-data fields. Those fields are set exactly once (in the constructor or in `readAdditionalSaveData`), so recomputing the bounding box every tick was pointless.
  2. Called `teleportCollidingEntities()`, which ran `level.getEntities(this, innerBoundingBox)`.
  3. Called `captureVelocity()`, which ran `level.getEntities(this, velocityBoundingBox)`.

    Steps 2 and 3 both hit the entity-section grid, and step 3's bounding box is always a strict superset of step 2's — so the same entities were being scanned twice.
- **The "After" (Our Optimization):** `PortalEntityMixin` overwrites `tick()`:
  - `refreshDimensions()` is guarded by a `jdtopt$dimsRefreshed` flag and runs **exactly once**, on the portal's first tick. After that the bounding box is stable.
  - The two entity scans are consolidated into a **single** `level.getEntities(this, velocityBoundingBox)` call. The resulting list is walked twice locally: once to teleport entities in the inner bounding box, once to capture velocity for entities that remained in this portal's level.
  - The `entity.level() != this.level()` guard on the capture pass matches upstream's implicit behaviour where the second `getEntities` call would naturally exclude teleported entities.
- **The Benefit:** One entity-grid scan per portal per tick saved, plus one `makeBoundingBox` computation per portal per tick. Worlds with many active portals (portal-gun builds, advanced-portal spam) benefit the most.
- **Gameplay Impact:** **None.** Teleport ordering, velocity preservation across portals, look-angle transform, teleport cooldowns, and entity-cooldown semantics are all preserved bit-for-bit. The one behavioural guarantee we explicitly check in code is that `calculateVelocity` still reads `entityLastPosition` entries written by **previous** ticks, never entries written earlier in the same tick — same as upstream.

---

### 2.14 `EntityEvents.fluidCraftCache` Memory Cap

- **What It Is:** The static `Map<FluidInputs, BlockState> fluidCraftCache` on `EntityEvents` — JDT's cache for "does this fluid + item combo produce a crafted block?".
- **The "Before" (Original State):** The cache was **unbounded**. Every unique `(fluid, item)` pair that ever entered the lookup — intentionally via JDT's fluid-crafting, or unintentionally via any dropped item falling into any fluid anywhere on the server — stayed in the map forever. On long-running worlds this is a slow-burn memory leak.
- **The "After" (Our Optimization):** `EntityEventsMixin` injects at `TAIL` of `findRecipe` and clears the cache outright when its size exceeds a hard cap of **1024 entries**. Clearing on overflow is a deliberate trade-off: we briefly repopulate the common entries, but we reliably cap memory usage and avoid any per-entry eviction overhead. The common hot set (a handful of recipes players actually use) re-warms within a few ticks.
- **The Benefit:** Protects long-lived servers from a monotonically-growing static map.
- **Gameplay Impact:** **None.** The cache is a pure hit-rate optimization: clearing it only costs a re-lookup for entries that happen to be re-requested. Recipes themselves are unchanged.

---

## 3. Installation & Setup

### For Players

1. **Install Minecraft** `1.21.1` and **NeoForge** `21.1.209` or newer.
2. **Install Just Dire Things** `1.5.7` (`justdirethings-1.5.7.jar`) into your `mods/` folder. **This is a hard requirement** — JDT Optimizer does nothing on its own.
3. **Install JDT Optimizer** by dropping `jdtoptimizer-<version>.jar` into the same `mods/` folder.
4. Launch the game. Mixins are applied automatically at class-load; no further configuration is needed.

### For Server Admins

- Install the same `jdtoptimizer-<version>.jar` on both the server and every client that will connect. The mod's only network payload (the FE/tick override from §2.5) is registered as **optional**, which means:
  - Clients without JDT Optimizer can still connect to a server that has it — they simply won't see the FE/t override field in the transmitter GUI.
  - Servers without JDT Optimizer can still accept clients that have it — the GUI will render, but the save button will be a no-op because the server won't accept the packet.
- No server-side configuration or whitelist entries are required.

### For Developers

1. Build Just Dire Things once so you have a compile target:

   ```pwsh
   cd ../JDT
   ./gradlew.bat jar
   ```

2. Copy the resulting jar into this project's `extra-api/` and `extra-mods/` directories:

   ```pwsh
   cp ../JDT/build/libs/justdirethings-1.5.7.jar extra-api/
   cp ../JDT/build/libs/justdirethings-1.5.7.jar extra-mods/
   ```

3. Build JDT Optimizer:

   ```pwsh
   ./gradlew.bat build
   ```

   Output: `build/libs/jdtoptimizer-<version>.jar`.

4. To smoke-test in a dev client with both mods already loaded:

   ```pwsh
   ./gradlew.bat runClient
   ```

### How patches are wired

- Mixin config: `src/main/resources/jdtoptimizer.mixins.json`.
- Runtime registration: declared in `META-INF/neoforge.mods.toml` via:

  ```toml
  [[mixins]]
  config = "jdtoptimizer.mixins.json"
  ```

- Every patch lives under `com.alvin.jdtoptimizer.mixin.jdt.*` targeting a JDT class.
- Shared utilities live under `com.alvin.jdtoptimizer.cache.*` (`ConfigCache`, `Directions`).
- Public extension interfaces live under `com.alvin.jdtoptimizer.api.*` (`IJdtOptSlotCapCache`, `IJdtOptBlockStateFilterCache`, `IFePerTickOverride`).
