# Wakes

NeoForge 1.21.1 mod that adds wave-driven buoyancy and matching ocean shaders so **Create Aeronautics** ships and vanilla boats rock with the swell instead of sitting on a flat plane.

## Build

The mixins compile against jars from Create, Create Aeronautics, Sable, Sodium, and Iris. Run:

```
just fetch-libs
just build
```

`fetch-libs` resolves each dependency against the Modrinth API at the version named in `gradle.properties`, downloads it into `libs/`, and extracts the jar-in-jars Gradle's fileTree can't see. All `compileOnly` — runtime resolves them from the actual installed mods.

Note that several of these ship the classes we compile against as jar-in-jars, so `libs/` ends up with more jars than were downloaded:

- **Sable** bundles companion-math (`Pose3d`, `BoundingBox3dc`), Veil, and rapier.
- **Sodium 0.8+** bundles the entire mod; the outer jar is only a NeoForge locator stub.
- **Create Aeronautics** bundles the real `aeronautics` mod, plus offroad and simulated.

To change versions, edit `gradle.properties` and re-run `just fetch-libs` (use `just clean-libs` first to drop the old jars).

## Runtime requirements

Versions below are what the mod is currently built and tested against.

| Mod | Required | Notes |
|---|---|---|
| NeoForge 21.1.250 | yes | |
| Create 6.0.10+ | yes | |
| Create Aeronautics 1.3.2+ | yes | mixin target |
| Sable 2.0.5+ | optional | enables wave buoyancy on assembled SubLevels |
| Sodium 0.8.x | optional | patches its chunk shader for animated water |
| Iris 1.8.x | optional | patches the loaded shader pack's water vsh |

## Config

`config/wakes-client.toml`:

- `enabled` — master toggle
- `samplePointsPerAxis` — hull sample grid (1 = single-point bob, 3 = ship-like rocking)
- `heaveScale` / `pitchScale` / `rollScale` — per-axis multipliers
- `requireWaterUnderneath` — only apply when contraption footprint actually overlaps water
- `affectAllContraptions` — Phase 1 only targets propeller-bearing contraptions
- `debugParticles` — dust particles at each wave-force sample point; noisy in normal play
- `debugLog` — per-ship, per-second physics diagnostics (mass, angular velocity, net torque)

### `[buoyancy]`

Depth-proportional lift for blocks tagged `#wherestherum:floats` — the full-cube coral
blocks by default. Unlike Aeronautics levitite this adds **no** rotational drag, so a hull
keeps rocking with the swell; the only damping is vertical and is applied at the centre of
mass (zero lever arm, so it cannot induce torque).

Lift scales with block **count**, not ship mass, and grows with submersion depth — so a hull
settles at its own waterline rather than launching, and a tilted hull's deeper side lifts
harder, giving a righting moment that falls out of the geometry.

- `enabled` — master toggle
- `liftPerBlock` — lift per floating block per block of submersion depth
- `maxDepth` — depth at which a block's lift stops growing. Set to `1.0` for a sharp,
  predictable waterline (a block then saturates once fully submerged, as real displacement
  does); larger values let a hull sink deeper before lift balances weight
- `verticalDamping` — vertical-only damping (1/s); `0` for a completely undamped hull

Minimum blocks to float a hull is `Weight / (liftPerBlock × maxDepth)` — below that, lift
saturates and she sinks regardless.

### `[hydrodynamics]`

Resistance to moving *through* the water. Without it a hull skates sideways as freely as it
moves ahead, because only wave drift acts on it.

Drag is **anisotropic in the hull frame**: low along the keel, high abeam. That single
asymmetry is what makes a ship carve a turn rather than slide through it. The keel axis is
detected from the hull's own footprint (whichever of local X/Z it is longer in). Drag scales
with the number of submerged blocks, so a laden ship is sluggish and a light one nimble.

- `longitudinal` / `lateral` — fore-aft vs. sideways drag. The **ratio** matters more than
  either value; `lateral` should be well above `longitudinal`
- `vertical` — damps slamming into swell
- `yawDamping` — rotational drag about the vertical axis **only**. Roll and pitch are left
  undamped on purpose, so the hull keeps rocking with the sea

### `[sails]`

Wind propulsion for blocks tagged `#wherestherum:sails` (wool and banners by default).

Thrust is applied **along the wind**, not along the keel — correct sailing behaviour then
emerges from the drag above rather than being scripted. The wind shoves the ship bodily
downwind, the hull refuses to go sideways, and what survives is motion along the keel. Point
the bow where you want to go and you make way; turn beam-on and you get shoved. Sailing dead
upwind is impossible without any rule saying so, because no force could do it.

Thrust is applied at each sail block's real position, so a tall rig heels the ship — and
since roll is never damped, that heel is visible.

- `thrustPerBlock` — thrust per sail block at full wind
- `minWindAlignment` — cosine of the closest angle to the wind a sail still draws.
  `0.0` allows a beam reach; positive values force real tacking
- `windSpeed` / `windVariability` — wind strength, and how much its direction wanders

Wind is a pure function of game time (like the wave field), so client and server agree
without any packets. Note the **swell direction is still a GLSL compile-time constant**, so
at high `windVariability` the wind and the visible wave direction drift apart.

### `[sound]`

Ambient ocean and hull sound driven by the live wave state. Uses vanilla `SoundEvents` only —
no custom assets — on `SoundSource.AMBIENT`, so the ambient slider governs it.

Volume tracks the wave **envelope**, not instantaneous wave height: height zero-crosses twice
per period, so driving volume from it would pump the bed to silence twice a wave even in a
gale. Creaks are driven by the wave-surface **gradient** rather than height, because rocking
comes from the slope under the hull, not its elevation.

- `enabled`, `waveVolume`, `creakVolume`

Note: the sound code hand-mirrors the amplitude constants from `WakesWaveFunction`, the same
lock-step coupling this codebase already carries between the CPU and GLSL wave functions.
Change the wave amplitudes and this needs updating too.

### `[entities]`

Boats, players and mobs ride the wave surface instead of a flat sea.

Rather than a positional spring, this samples the water surface's own vertical velocity
following the entity and nudges `deltaMovement.y` toward it. That matters because vanilla
`Boat.floatBoat()` *already* runs a servo toward a flat cached water level — a second
positional controller on the same axis would be two servos fighting, which is exactly how
you get oscillation. With no setpoint of our own there is nothing to oscillate against.

Create contraptions are excluded: they already ride the wave field via their render
transform, and assembled ships get real forces in the physics tick. One wave source per
vessel.

- `enabled`, `strength`, `boatsOnly`

If bobbing reads as too subtle, raise the gain rather than the per-tick clamp — vanilla's own
damping resists, so the achieved amplitude is always below full wave amplitude.

### `[surf]`

Foam where waves meet shallow water, using the same depth map that scales wave amplitude, so
the foam line follows the real shoreline. Foam appears where depth is low but non-zero *and*
the local wave is near its crest, so it breaks on wave tops rather than forming a static ring.

- `enabled`, `intensity`

Two caveats. The config is **baked into the shader at patch time** (GLSL cannot read a
NeoForge config, and the uniform binding lives in a class the surf code does not own), so
changing either value needs a shader reload (F3+T) or a restart. And the water shader is
gated on `IS_TRANSLUCENT`, which is broader than "water" — ice or glass sitting near sea
level *in shallow water* can pick up foam. A frozen ocean surface is the worst case. Set
`surf.enabled = false` if it bothers you; a real fix needs a per-vertex "is this water"
signal that Sodium's translucent pass does not currently hand us.
