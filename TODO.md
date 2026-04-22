# Vulkium — known issues / feature backlog

Master list of things to fix or add. Priority-independent unless noted. Short one-line
hook per item plus what's known so far; fuller context lives in the linked file/commit.

## Known bugs

- **Clouds render in front of water/translucent.** Our translucent pass fires at
  `AFTER_TRANSLUCENT_TERRAIN` which happens BEFORE cloud render in MC's sequence. Clouds
  then overwrite translucent's depth. Fix: move translucent draw to `END_MAIN` (after
  clouds have written depth) so translucent depth-tests against cloud depth correctly.
  See `FrameDriver.register()`. Prior comment in `onAfterTranslucentTerrain` claims that
  location was the fix for this — apparently not, needs runtime experimentation.

- **Immovable chunk slices on initial load.** When joining a world, some slices of
  chunks never render until the player moves a little. Moving seems to "remind" vulkium
  that those chunks need to be drawn — suggests the sections are captured + uploaded but
  don't make it into `OpaqueDispatchList` (or `VisibilityTracker`'s visible-region array)
  until something bumps the state. Likely fix is in the first-frame-after-capture path:
  either `visibility.update` doesn't include newly-captured regions, or the dirty-region
  drain runs after `OpaqueDispatchList.build` and the new section headers arrive on the
  GPU one frame late.

- **Crash when looking upward with lots of regions loaded.** Bisected to HZB region cull
  (`enableHzbRegionCull=true`) as the trigger — disabling it avoids the crash, disabling
  the broader HZB build alone does not. Signature is a 5s VK semaphore timeout that
  surfaces in MC's `StagedVertexBuffer.tryRecycle` (hand/item rendering) after a ~2s GPU
  stall. Defensive push-constant bounds check added in `region_cull.comp` — if the bug
  was "threads past `maxRegionIndex` feed garbage AABBs into projection → NaN/Inf UVs →
  textureLod hang on Blackwell," this should fix it. If it still crashes after that,
  the root cause is elsewhere in the HZB region-cull path (barrier ordering across the
  HZB → region-cull → task-shader pipeline, or the depth-tap layout transition fighting
  Mojang's subsequent depth writes for hand-item rendering).

- **`regionKeepDistance=32` ("Vanilla") behaves identically to "Keep All".** The GUI
  label says 32 should evict sections whose owning chunk has been unloaded (vanilla
  behavior), but in practice vulkium never evicts at that setting. Likely a bug in the
  sweep path's threshold check or in how the unload signal reaches
  `SectionManager.sweepKeepDistance`. Acceptance: setting to 32, flying far, then
  returning to origin leaves `SectionManager.live.size()` well below the "keep all"
  high-water mark.

- **Translucent geometry outlives opaque at far distances (>~48 chunks).** Past the
  opaque eviction radius, opaque blocks unload but the translucent layer from the same
  chunk stays rendered — visually reads as "floating glass/water". Expected: opaque
  should stay as long as translucent, OR translucent should evict with opaque. The
  mismatch suggests the eviction path treats the two layers separately; the translucent
  branch isn't seeing the same distance test.

## Feature gaps

- **Antialiasing.** No MSAA / TAA / FXAA path. MC 26.2's forward pass renders into the
  main color attachment which we LOAD_OP_LOAD. Adding MSAA would require multisampled
  attachments at `PrimaryTerrainPass` pipeline create time + a resolve; TAA needs motion
  vectors and a history buffer.

## Performance

- **Per-frame host-mapped buffer triple-buffering** (done: `OpaqueDispatchList` 6e7857c,
  `TranslucentSectionSorter` 908f7d3, `SceneUniform` b68b293) — all three are triple-
  buffered to eliminate the CPU↔GPU WAR race. Preserve this invariant when adding new
  per-frame host-mapped BDA or UBO buffers; template off any of them. Symptom if missed:
  ~10s of correct rendering on NVIDIA Windows, then a 5s VK semaphore timeout. Linux
  hides the hazard via stricter driver serialization.

## Process / tooling

- Deep-test script (`scripts/vulkium-deep-test.sh`) is DEV_ONLY and should be stripped
  before release along with the `vulkium-*` trigger files and `devDraw*Pass` config
  flags.
