# Vulkium — known issues / feature backlog

Master list of things to fix or add. Priority-independent unless noted. Short one-line
hook per item plus what's known so far; fuller context lives in the linked file/commit.

## Known bugs

- **Clouds render in front of water/translucent.** Diagnosed: MC 26.2's transparency
  PostChain (`assets/minecraft/post_effect/transparency.json`) does a DEPTH-SORTED composite
  — `post/transparency` samples each of 6 per-layer color targets PLUS their 6 depth buffers
  (Main, Translucent, ItemEntity, Particles, Clouds, Weather) and blends per-pixel by depth.
  Vulkium writes translucent COLOR to `levelRenderer.translucentTarget()` but uses MAIN's
  depth as the depth attachment (`FrameDriver.java:236-248`), so `translucentTarget`'s own
  depth never receives translucent geometry's Z. PostChain samples `TranslucentDepth`,
  gets the cleared/far value, and composites clouds on top everywhere. Fix: when drawing
  translucent into `translucentTarget`, use `colorRt.getDepthTextureView()` (that target's
  own depth) instead of `mainRt.getDepthTextureView()`. Opaque-occlusion correctness is
  preserved by the PostChain depth-sort (main's opaque Z wins where opaque is closer).
  Depth-ordering semantics we want: translucent behind clouds = hidden, translucent in
  front of clouds = visible — the per-layer depth-sort gives this for free once the
  translucent target has real depth in it.

- **Immovable chunk slices on initial load.** When joining a world, some slices of
  chunks never render until the player moves a little. Moving seems to "remind" vulkium
  that those chunks need to be drawn — suggests the sections are captured + uploaded but
  don't make it into `OpaqueDispatchList` (or `VisibilityTracker`'s visible-region array)
  until something bumps the state. Likely fix is in the first-frame-after-capture path:
  either `visibility.update` doesn't include newly-captured regions, or the dirty-region
  drain runs after `OpaqueDispatchList.build` and the new section headers arrive on the
  GPU one frame late.

- **Crash when looking up with many regions loaded.** Tilting the camera upward (high
  pitch) with a large visible-region set reliably crashes the client. Likely tied to a
  limit somewhere that scales with visible-region count in the upward-facing frustum
  (the "look up" direction maximizes the number of sky-facing regions within the view
  volume). Suspect sites: `OpaqueDispatchList` capacity overflow, task-shader dispatch
  count above a driver cap, or a CPU-side array sized from `maxRegionIndex` that doesn't
  re-grow. First diagnostic step: log `visibleRegionCount` and `dispatched` right before
  the crash to see which number is spiking.

## Feature gaps

- **Antialiasing.** No MSAA / TAA / FXAA path. MC 26.2's forward pass renders into the
  main color attachment which we LOAD_OP_LOAD. Adding MSAA would require multisampled
  attachments at `PrimaryTerrainPass` pipeline create time + a resolve; TAA needs motion
  vectors and a history buffer.

## Performance

- **Per-frame host-mapped buffer triple-buffering** (done as of `6e7857c` / `908f7d3` /
  `b68b293`) — `OpaqueDispatchList`, `TranslucentSectionSorter`, and `SceneUniform` all
  hold 3 slots rotated once per frame, so the slot the CPU writes is never in-flight on
  the GPU. Preserve this invariant when adding new per-frame host-mapped buffers the GPU
  reads via BDA or descriptor binding; template off any of the three. NVIDIA Windows
  driver does NOT serialize the WAR race Linux hides — single-slot buffers of this kind
  reliably produce ~10s-of-good-rendering-then-DEVICE_LOST on Windows.

## Process / tooling

- Deep-test script (`scripts/vulkium-deep-test.sh`) is DEV_ONLY and should be stripped
  before release along with the `vulkium-*` trigger files and `devDraw*Pass` config
  flags.
