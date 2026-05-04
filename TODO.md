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

- **Toggle-in-session perf regression (still unresolved 2026-05-04).** Re-enabling
  vulkium after a disable window drops gpu.opaqueDraw + gpu.translucentDraw ~15× on the
  same scene (post: ~14 ms vs pre: ~0.8 ms; gpu.hzbBuild and gpu.regionCull unchanged),
  doesn't recover without full client restart, F3+A doesn't fix. Confirmed independent
  of vulkium's toggle-handler choices: tested {shutdown + flushAll + allChanged}, {no
  shutdown, flushAll + allChanged}, {only allChanged}, and {pure flag flip with no
  side-effects} — all four variants give the same ~60 fps post-toggle. Cost lives in
  MC-side GPU state created while MC's terrain pipeline runs during the disable
  window. Suspect MC's section uber-buffer / Dynamic Uniforms growth (logs show
  "Resizing Chunk Sections UBO" repeatedly during the off period); freeing it on
  re-enable would require Mojang-internal API. Resume here when looking at MC's
  ChunkSectionsToRender / SectionRenderDispatcher lifecycle.

- **Sub-chunk doesn't show its final change when that change empties the section.**
  Removing the last block in a section (e.g. the lone block that makes the section
  non-empty) leaves the previously-rendered geometry on screen instead of clearing the
  section. Suspected: MC's compile path doesn't fire `CompiledSectionMeshMixin` for a
  newly-empty section (no `Results` to capture), so vulkium never gets a "this is now
  empty" signal and `SectionManager.live` retains the stale entry until something else
  evicts it. Fix probably needs to mirror MC's empty-section detection in our extractor /
  `SectionCapture` and call `SectionManager.evictLive(key)` when the compile result is
  empty.

- ~~**Immovable chunk slices on initial load.**~~ FIXED. Root cause was
  `IdProvider` recycling region ids across evict/allocate: a freshly-allocated region
  inherited the host-visible `regionVisibilityReadback` byte from its previous
  occupant, which was typically the "occluded" 0 written just before eviction.
  `OpaqueDispatchList.build` then skipped the new region at list-build time and its
  sections never reached the task shader until the next cull round-trip (several
  frames later, accelerated by camera motion on NVIDIA — hence "player moves, slices
  appear"). Fix in `Renderer.onRegionActivated(regionId)` called from
  `SectionManager.ingest` whenever `regionSectionCount(regionId) == 1` after
  `allocateSection`: stamps both the region and section readback bytes for that slot
  back to 0xFF so this frame's dispatch build includes the region.

- ~~**Crash when looking upward with lots of regions loaded.**~~ FIXED. Root cause was
  `region_cull.comp` dispatching threads past `maxRegionIndex`, feeding garbage AABBs
  into the HZB projection → NaN/Inf UVs → `textureLod` hang on NVIDIA. Defensive push-
  constant bounds check in `region_cull.comp` (commit `1d6f28d`) resolves it.


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
