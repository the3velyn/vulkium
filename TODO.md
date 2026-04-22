# Vulkium — known issues / feature backlog

Master list of things to fix or add. Priority-independent unless noted. Short one-line
hook per item plus what's known so far; fuller context lives in the linked file/commit.

## Known bugs

- **Camera-dependent over-culling of regions.** Some regions get culled when they shouldn't
  depending on camera orientation — visible terrain vanishes as the player turns. Likely
  in `VisibilityTracker.update` (frustum + distance cull) or the HZB region-cull compute
  path. Repro: face one direction, observe chunks present; rotate and they disappear
  rather than going off-screen. Needs per-region cull-reason logging to isolate.

- **0.3× performance after first F3+A on world join.** Reloading chunks the first time via
  F3+A drops steady-state FPS to ~30% of pre-F3+A. Does not recover without client
  restart. Related to but distinct from the in-game master-toggle regression fixed in
  `P0-3`. Probably a handle/descriptor/buffer not being released on `queueFlushAll` /
  inline eviction, or MC's own section-compile path re-binding a resource vulkium still
  expects to own. Instrumentation: log per-frame `live.size()`, arena usage, descriptor
  pool stats before/after F3+A.

- **`translucencySortingLevel=NONE` disables translucent rendering.** `FrameDriver:162-165`
  gates the translucent draw on `TranslucentSectionSorter.count()`. With `NONE`, sort()
  never runs so count stays 0 → early return → no translucent dispatch. Fix: either
  populate an unsorted list when NONE, or replace the gate with "have translucent
  sections?" rather than "sorter has entries?". Cosmetic bug in NONE mode only — sort
  defaults to QUADS so normal users don't hit it.

- **Clouds render in front of water/translucent.** Our translucent pass fires at
  `AFTER_TRANSLUCENT_TERRAIN` which happens BEFORE cloud render in MC's sequence. Clouds
  then overwrite translucent's depth. Fix: move translucent draw to `END_MAIN` (after
  clouds have written depth) so translucent depth-tests against cloud depth correctly.
  See `FrameDriver.register()`.

- **Mipmap toggle does nothing visually.** The sampler and config both claim mipmap
  support but enabling/disabling MC's mipmap option has no visible effect on vulkium-
  rendered terrain. Possible: wrong `maxLod` on our sampler, or `VkImageView` created
  against a non-mip texture view of Mojang's atlas. Check `MojangAtlasTap.blockAtlasMipLevels()`
  vs. the sampler's `maxLod`, and whether we're sampling through `textureLod(…, mipLevel)`
  with a mip level derived from screen-space derivatives.

## Feature gaps

- **Antialiasing.** No MSAA / TAA / FXAA path. MC 26.2's forward pass renders into the
  main color attachment which we LOAD_OP_LOAD. Adding MSAA would require multisampled
  attachments at `PrimaryTerrainPass` pipeline create time + a resolve; TAA needs motion
  vectors and a history buffer.

- **Meshlet face-bin cull.** Infrastructure landed (`fdbbf9c`, `03a69d9`), currently
  disabled pending GPU-side axis diagnosis. Re-enable once diagnosed. File:
  `SectionManager.ingest` writes 0s to `renderRanges.xyz`; flip to the commented-out
  per-face packing to re-enable.

## Performance

- **Region/section sorter ring-buffering** (done as of `6e7857c` + this commit) — both
  lists are now triple-buffered to eliminate the CPU↔GPU WAR race. Preserve this invariant
  when adding new per-frame host-mapped BDA buffers. If another subsystem needs one,
  template off `OpaqueDispatchList` / `TranslucentSectionSorter`.

## Process / tooling

- Deep-test script (`scripts/vulkium-deep-test.sh`) is DEV_ONLY and should be stripped
  before release along with the `vulkium-*` trigger files and `devDraw*Pass` config
  flags.
