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

## Feature gaps

- **Antialiasing.** No MSAA / TAA / FXAA path. MC 26.2's forward pass renders into the
  main color attachment which we LOAD_OP_LOAD. Adding MSAA would require multisampled
  attachments at `PrimaryTerrainPass` pipeline create time + a resolve; TAA needs motion
  vectors and a history buffer.

## Config cleanup

- **`extraRd` — investigate and probably remove.** The option adds beyond-RD chunk streaming
  on vulkium's side (loads an `renderDistance + extraRd` radius of chunks), but the actual
  chunk loading is server-sided — `ChunkMapViewDistanceMixin` raises the server-side cap
  from 32 to 128, but the server still respects its own configured view distance. On
  multiplayer the extra radius is effectively ignored; on single-player it works but
  creates an expectation of extra chunks that doesn't carry to multiplayer. Either wire
  it properly with server-side cooperation or drop the field + its GUI slider. Likely
  drop.

## Performance

- **Region/section sorter ring-buffering** (done as of `6e7857c` / `908f7d3`) — both
  lists are triple-buffered to eliminate the CPU↔GPU WAR race. Preserve this invariant
  when adding new per-frame host-mapped BDA buffers; template off `OpaqueDispatchList` /
  `TranslucentSectionSorter`.

## Process / tooling

- Deep-test script (`scripts/vulkium-deep-test.sh`) is DEV_ONLY and should be stripped
  before release along with the `vulkium-*` trigger files and `devDraw*Pass` config
  flags.
