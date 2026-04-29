# JDT Optimizer

A NeoForge 1.21.1 companion mod that applies performance patches to
[Just Dire Things](https://github.com/Direwolf20-MC/JustDireThings) via
SpongePowered Mixins. **Drop this jar into your `mods/` folder alongside
JustDireThings — no JDT source changes required.**

## Goals

Target JDT `1.5.7` on NeoForge `21.1.209`. Patches aim to preserve gameplay
mechanics exactly while eliminating unnecessary work on the server tick.

Planned fixes (see `analysis/` for the spark-profiler-driven audit):

- Two-lane `BaseMachineBE.tickServer()` → per-tick vs periodic lanes.
- Cache NeoForge data attachments as BE fields.
- Cache `EnergyTransmitterBE.getTransmitterEnergyStorages()` network map.
- Replace `Stream.sorted().collect()` pipelines with pre-sized loops.
- `Map<BlockState, Boolean>` filter cache for all `FilterableBE` callers.
- Slot-keyed capability cache (`IEnergyStorage` / `IFluidHandlerItem`).
- Cache block facing, config values, fake player reference.
- `PortalEntity` single-scan tick.

## Dev setup

1. Build JustDireThings once so we have an artifact to compile against:

   ```pwsh
   cd ../JDT
   ./gradlew.bat jar
   ```

2. Copy the resulting jar into `extra-api/` and `extra-mods/`:

   ```pwsh
   cp ../JDT/build/libs/justdirethings-1.5.7.jar extra-api/
   cp ../JDT/build/libs/justdirethings-1.5.7.jar extra-mods/
   ```

3. Build this mod:

   ```pwsh
   ./gradlew.bat build
   ```

   Output: `build/libs/jdtoptimizer-<version>.jar`.

4. To test in dev, `./gradlew.bat runClient` launches a client with both JDT and
   JDT Optimizer loaded.

## How patches are applied

Mixin config: `src/main/resources/jdtoptimizer.mixins.json`. Each patch is a
class under `com.alvin.jdtoptimizer.mixin.jdt.*` targeting a JDT class.
Runtime loading is wired in `META-INF/neoforge.mods.toml`:

```toml
[[mixins]]
config = "jdtoptimizer.mixins.json"
```

NeoForge 1.21 does not require refmaps for deobfuscated runtimes, but one is
written anyway to aid debugging.
