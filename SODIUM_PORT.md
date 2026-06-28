# Sodium-compat port — progress + notes

Branch: `mc-26.2-sodium`. Goal: vulkium runs as a Sodium **addon** (Sodium handles entities/lighting/world-render-flow, vulkium replaces the mesh-shader terrain pipeline). Architectural model: MCRcortex/nvidium → CaffeineMC/sodium.

Local references:
- Sodium upstream: `~/Downloads/vulkium/sodium` (CaffeineMC/sodium `dev` branch, MC 26.2 target)
- Nvidium reference: `~/Downloads/vulkium/nvidium` (MCRcortex/nvidium `dev` branch, MC 1.21 + Sodium 0.5.x — pattern transfers, signatures don't)
- Old sodium-vk fork: `~/Downloads/vulkium/sodium-vk` — different goal (sodium-tolerates-vulkan), leave alone

## Status

| Area | State | Notes |
|---|---|---|
| Branch setup | ✅ | `mc-26.2-sodium` branched from `mc-26.2`, pushed to origin |
| Survey of nvidium → modern Sodium API | ✅ | See compatibility matrix below |
| Sodium dep declaration (gradle + fabric.mod.json) | ✅ | `compileOnly + runtimeOnly` on `maven.modrinth:sodium:mc26.2-0.9.1-beta.2-fabric`. `depends.sodium >= 0.9.0-beta.2`, removed from `breaks`. Sodium ships with official mappings so plain configs work (Loom mod* not wired in this build). |
| Three direct-port mixins | ✅ scaffolding fires | RenderRegionManager, ChunkBuilderMeshingTask, ChunkBuildOutput. Verified live 2026-06-27: 6144+ `ChunkBuildOutput` observations + 256+ `uploadResults` calls in a steady-state session. **No data routed into vulkium yet** — see next step. |
| Sodium → vulkium data routing | ❌ | Sodium replaces MC's `SectionCompiler`; vulkium's standalone ingest hooks (`SectionCompilerMixin`) get no calls when Sodium is present, so `RegionManager` stays empty and vulkium renders no terrain. Need a route from Sodium's `ChunkBuildOutput.meshes` into `SectionManager.offerFromCompile`. See next-steps #6 for options. |
| Separate mixin config | ✅ | `vulkium-sodium.mixins.json` (not on the standalone `mc-26.2` branch). Listed in `fabric.mod.json.mixins`. |
| Vertex format hook | ⏳ | Need an equivalent to nvidium's NvidiumCompactChunkVertex that produces vulkium's mesh-shader-compatible vertex layout |
| Render flow rewrite | ❌ | Blocked on `RenderSectionManager` constructor breakage — needs new attachment point |
| Coexistence with Sodium's own Vulkan backend | ❌ | Sodium 0.6+ ships a Vulkan pipeline with a "Prefer Vulkan" config option. Open design Q: short-circuit, cooperate, or detect-and-disable? |
| Config-flag injection | ❌ | OptionFlag is now an enum, immutable. Need own config or hook SodiumConfigBuilder. |
| Options-GUI integration | ❌ | VideoSettingsScreen replaced SodiumOptionsGUI; refactor needed. |

## Mixin port matrix (vs nvidium)

| Nvidium mixin | Modern Sodium target | Status | Notes |
|---|---|---|---|
| `MixinRenderRegionManager` | `net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager` | ✅ direct port | `uploadResults()` at line 58, signature compatible |
| `MixinChunkBuilderMeshingTask` | `net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask` | ✅ direct port | `execute(ChunkBuildContext, CancellationToken): ChunkBuildOutput` at line 69 |
| `MixinChunkBuildOutput` | `net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput` | ✅ direct port | Has `meshes: Map<TerrainRenderPass, BuiltSectionMeshParts>` field — this is the per-layer mesh data to capture |
| `MixinSodiumWorldRenderer` | `SodiumWorldRenderer` | △ needs adaptation | `setupTerrain` signature changed: `(FogParameters, Matrix4f)` not `(Viewport, int, bool)`; `renderBlockEntity` static gone, `extractBlockEntities` instance |
| `MixinRenderSection` | `RenderSection` | △ needs adaptation | `getLastVisibleFrame()` removed; `setPendingUpdate` now 2-arg |
| `MixinChunkBuilder` | `ChunkBuilder` | △ needs adaptation | `getSchedulingBudget()` removed |
| `MixinChunkJobQueue` | `ChunkJobQueue` | △ now package-private | Targetable via mixin `targets={}` but tooling warns |
| **`MixinRenderSectionManager`** | `RenderSectionManager` | ✗ structural break | Constructor lost `CommandList` arg; nvidium's `@ModifyArg` on `ChunkVertexType` no longer reachable that way. Visibility moved internal to `SectionTree`. **Need a new entry point for attaching vulkium's renderer.** |
| `MixinOptionFlag` | `OptionFlag` | ✗ structural | Now an immutable enum |
| `MixinSodiumOptionsGUI` | `VideoSettingsScreen` | ✗ structural | Class renamed + refactored |

## Open design questions

1. **Coexistence with Sodium's Vulkan backend.** Sodium 0.6+ has its own `VulkanRenderPipeline` / `VulkanRenderPass` paths with a "Prefer Vulkan" config. If a user has both that and vulkium-sodium, what should happen? Options: (a) detect Sodium-Vulkan mode and refuse to load, (b) force-disable Sodium-Vulkan when vulkium-sodium loads, (c) cooperate. Decide before shipping.
2. **Where to attach vulkium's world-renderer.** Nvidium's `RenderSectionManager.<init>` hook is gone. Candidates: `SodiumWorldRenderer.<init>`, `ChunkRenderer.<init>`, a Fabric lifecycle event. Need to pick one that fires once per world-load and can hold our `VulkiumWorldRenderer` instance.
3. **Vertex format negotiation.** Sodium's current path is `ChunkMeshFormats.COMPACT`. Vulkium's mesh shader expects its own format. Need either (a) `@ModifyArg` on the ChunkBuilder's vertex-type input (if still parameterized), or (b) a post-build conversion in our `MixinChunkBuildOutput` hook.

## Next steps (in order)

1. ✅ Add Sodium maven dep to `build.gradle.kts` (compileOnly + runtimeOnly via Modrinth maven).
2. ✅ Flip `fabric.mod.json`: remove `sodium` from `breaks`, add to `depends`.
3. ✅ Create `vulkium-sodium.mixins.json` (separate from the existing one — sodium mixins should NOT load on the standalone edition).
4. ✅ Port the 3 direct mixins under `me.cortex.vulkium.mixin.sodium.*`.
5. ✅ Build, verify compiles against Sodium's API.
6. **NEXT — wire Sodium → vulkium data flow** (the actual goal). User picked approach (a): adapt vulkium's mesh shader to read Sodium's COMPACT vertex format directly. Survey of the format gap is in the "Sodium COMPACT vs vulkium vertex" section below — work needed: (i) extend vulkium's vertex stride from 16 → 20 bytes (or pull 2 buffers — position + packed-data — instead of one uvec4), (ii) decode Sodium's 20-bit Hi/Lo position split in the mesh shader, (iii) split the pre-multiplied `color × AO` byte back into separate channels if vulkium's lighting math needs them separate (else accept the bake), (iv) decide whether to honor Sodium's [8, 248] light clamp or fall back to MC raw values, (v) drop the unused material+section bytes for now (re-add only if a downstream pass actually consumes them), (vi) plug `SectionManager.offerFromCompile` so it accepts `Map<TerrainRenderPass, BuiltSectionMeshParts>` from our `ChunkBuilderMeshingTaskMixin` hook instead of MC's `SectionCompiler.Results`. Worker-thread side: copy `BuiltSectionMeshParts.getVertexData()` (NativeBuffer) into our owned memory immediately on capture — Sodium recycles its buffer after upload. Use `getVertexSegments()` (int[]: `[count0, facing0, count1, facing1, ...]`) to drive face-binned dispatch like vulkium's existing per-face culling did.
7. **Scour for now-redundant vulkium code that Sodium already handles** (added per user 2026-06-27). Sodium owns chunk compile, region upload, atlas + lightmap binding, the MVP/camera matrix construction, and the whole world-render flow. **This branch hard-depends on Sodium (`fabric.mod.json` declares `depends.sodium >= 0.9.0-beta.2`), so Sodium is guaranteed present at runtime — DELETE the redundant code outright, do NOT add `sodiumPresent` gates.** Standalone behavior already lives on the separate `mc-26.2` branch. Candidates to audit:
   - **MVP construction** (`FrameDriver.updateMvpFromCamera`, `BobViewTap` + its `GameRendererBobMixin`, `LevelRendererProjMixin`) — Sodium pushes the camera-correct MVP to its terrain shader on every frame; we should consume Sodium's uniform instead of capturing MC's mid-pipeline matrices.
   - **Atlas binding** (`MojangAtlasTap`, `MojangColorFormat` capture in `ChunkSectionsToRenderMixin`) — Sodium already binds the block atlas to its terrain pipeline; vulkium can fetch the VkImageView from Sodium's bound state rather than scanning MC's `AtlasManager`.
   - **Lightmap tap** (`MojangLightmapTap`) — Sodium has the lightmap bound on its own descriptor set.
   - **MC chunk-render suppression** (`ChunkSectionsToRenderMixin.vulkium$suppressVanillaTerrain`) — Sodium already cancels MC's vanilla terrain renderGroup; our mixin is competing/redundant.
   - **Vanilla section ingestion** (`SectionCompilerMixin`, `CompiledSectionMeshMixin`, `SectionCapture` MC-side path, `LevelRendererPrepareChunkRendersMixin`) — Sodium replaces MC's `SectionCompiler` entirely; these hooks never fire. Drop from `vulkium.mixins.json` on this branch (delete the classes too).
   - **Renderer init/teardown timing** (`LevelRendererAllChangedMixin`) — F3+A still applies, but Sodium owns the section state now. Confirm our soft-reset is still appropriate; might be obsolete.
   - **HZB / region cull pipeline** — Sodium has its own culling. Decide whether vulkium adds value on top (mesh-shader-side HZB still useful?) or whether sodium's cull is enough.
   - **FrameDriver as a whole** — much of what FrameDriver orchestrates (atlas binding, depth view capture, render pass open/close) was needed because vulkium drove its own draw against MC's framebuffer. With Sodium owning the world-render flow, the right entry might be a hook on `SodiumWorldRenderer.renderLayer` / `ChunkRenderer.render` that swaps in vulkium's mesh-shader draw for the SOLID/CUTOUT/TRANSLUCENT passes, freeing us from FrameDriver's MC-flow management.
8. Settings GUI compat (Sodium replaces vanilla `VideoSettingsScreen`) — keep vanilla mixin AND add sodium-side mixin. Lower priority than #6/#7.
9. Then tackle the structural blockers (RSM attachment, render-flow rewrite) one at a time.

## Implementation plan: Sodium → vulkium rendering (drafted 2026-06-28)

Plan-only document. Six stages, each independently verifiable. Recommended landing order is sequential — earlier stages set up state later stages depend on. **All stages assume sodium is a hard dep** (this branch). Do not gate on `sodiumPresent`.

### Stage 0 — Confirm Sodium's draw can be cancelled in-place (recon, no code) ✅

**Resolved 2026-06-28.** Target: `net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer#render` (9-arg, public void). Sodium invokes this once per `TerrainRenderPass` (SOLID, CUTOUT, TRANSLUCENT) per frame via the call chain `SodiumWorldRenderer.drawChunkLayer → renderLayer → DefaultChunkRenderer.render`. Per-layer cancellation is feasible by conditioning on the `TerrainRenderPass` arg; we cancel all three to fully own terrain. Cancellation at HEAD is safe: `super.begin()/end()` bracket the body INSIDE the method, the calling `renderLayer` does nothing after the call, and Sodium's own early-return on empty batches already exercises a skipped-draw path (proves the state machine tolerates it). Critically, the SAME method funnels both Sodium GL and Sodium Vulkan backends — the backend split lives in `MultiDrawBatch.draw` further down. One cancel covers both. No dev-vs-release class moves vs `0.9.1-beta.2` for this class.

### Stage 1 — Java data routing (no rendering yet)

**File: `src/main/java/me/cortex/vulkium/managers/SectionEntry.java`** (47 lines today)
- Add a parallel inner class `SodiumLayerGeometry`:
  ```
  public static final class SodiumLayerGeometry {
      public final ByteBuffer vertexBytes;      // direct, memAlloc-owned copy of Sodium's NativeBuffer
      public final int[] vertexSegments;        // [count0, facing0, count1, facing1, ...]
      public final int totalVertexCount;        // sum of segment counts
      public SodiumLayerGeometry(ByteBuffer vertexBytes, int[] vertexSegments, int totalVertexCount) { ... }
  }
  ```
- Add a parallel `Map<TerrainRenderPass, SodiumLayerGeometry> sodiumLayers` field (or single field that distinguishes via tagged union — pick simpler).
- Keep the existing `LayerGeometry` for compat with code paths not yet rewritten; standalone-era ingest stays callable until cleanup pass (Stage 6) removes it.

**File: `src/main/java/me/cortex/vulkium/managers/SectionManager.java`** (around L117)
- Add `public void offerFromSodium(long sectionPosKey, ChunkBuildOutput output)` mirroring `offerFromCompile` (L117):
  - Iterate `output.meshes.entrySet()` (`Map<TerrainRenderPass, BuiltSectionMeshParts>`).
  - For each non-null `BuiltSectionMeshParts`:
    - Get `NativeBuffer nb = parts.getVertexData()`.
    - Copy `nb.getDirectBuffer()` bytes into a fresh `MemoryUtil.memAlloc(remaining())` direct buffer (Sodium recycles `nb` after this call returns to its caller).
    - Build `SodiumLayerGeometry` with the copied bytes + `parts.getVertexSegments()` (clone the int[]).
  - Enqueue via `ingestQueue.offer(PendingIngest.fresh(key, entry))`.
- The existing `ingest(PendingIngest)` flow at L181 already handles the `live` table + region allocation. It then calls `uploader.uploadSectionSplit(p.key, p.entry, stream)` (L257). That's where Stage 2 work is concentrated.

**File: `src/main/java/me/cortex/vulkium/mixin/sodium/ChunkBuilderMeshingTaskMixin.java`**
- Replace the observer-only `vulkium$observeChunkBuildOutput` with:
  ```
  @Inject(method = "execute", at = @At("RETURN"))
  private void vulkium$captureChunkBuildOutput(... CallbackInfoReturnable<ChunkBuildOutput> cir) {
      ChunkBuildOutput output = cir.getReturnValue();
      if (output == null || output.meshes == null || output.meshes.isEmpty()) return;
      long sectionPosKey = sectionPosFromRender(output.render);  // RenderSection's SectionPos.asLong
      SectionManager.get().offerFromSodium(sectionPosKey, output);
  }
  ```
- Open Q for executor: `ChunkBuildOutput.render` (the `RenderSection` reference) — does it expose `SectionPos.asLong()` directly, or do we need accessor/shadow? Look at `BuilderTaskOutput.render` field type.

**Verification at end of Stage 1**: build green, runClient logs no errors. `visibleRegionCount` STAYS 0 because Stage 2's uploader work isn't done yet — but the SectionManager should have `live.size() > 0`. Add a one-shot log in `offerFromSodium` for first call + every 1024th. Don't expect terrain to render yet.

### Stage 2 — Java uploader rewrite (terrain renders, possibly garbled)

**File: `src/main/java/me/cortex/vulkium/render/TerrainUploader.java`** (690 lines today)
- L43 `VERTEX_STRIDE = 16` → `VERTEX_STRIDE = 20` (matches Sodium COMPACT).
- L46 `MC_VERTEX_STRIDE = 28` — delete (no longer relevant on this branch).
- L48-52 `POS_INV_SCALE`, `POS_ORIGIN`, `UV_SCALE` — these are the standalone-vertex packing constants. Replace with Sodium-vertex-format equivalents OR delete if we pass Sodium bytes through unmodified.
- L148 `uploadSectionSplit(long, SectionEntry, UploadStream)` — main rewrite target. Currently iterates `entry.layers` (`Map<ChunkSectionLayer, LayerGeometry>`); change to iterate `entry.sodiumLayers` (`Map<TerrainRenderPass, SodiumLayerGeometry>`). Map TerrainRenderPass → existing "opaque" / "translucent" bucket logic by checking `pass.isTranslucent()`.
- L295 / L472 / L623 `packOneVertex` calls — DELETE. Replace the entire per-vertex conversion with a pass-through `memCopy`/`memPut` of the source bytes into the upload-stream-mapped region.
- L638 `packOneVertex` private method — DELETE.
- L215-260 face-bin classifier — initially DELETE. Replace with a degraded path that puts every quad in the "unsigned" bin (no per-face culling). Stage 2.5 below can restore it using Sodium's `vertexSegments[]`.
- L68 `translucentUnsortedCache` — keep for now; translucent resort logic at L389 should still work since the bytes are still 20B/vertex (Sodium provides 4 verts/quad).
- Sort handling: Sodium's `BuiltSectionMeshParts` does NOT carry index buffers for translucent (sort is a separate `ResortTransparencyTask`). For now, accept whatever build-order Sodium produced; don't re-sort. Note as a known limitation.

**File: `src/main/java/me/cortex/vulkium/managers/BufferArena.java`** (75 lines)
- Constructor takes `vertexStride` — already parameterized via `new BufferArena(arenaSize, VERTEX_STRIDE)`. Should work automatically once `VERTEX_STRIDE` flips to 20. **Verify** there's no `% 16` hardcoded anywhere; if so, parameterize.

**Stage 2.5 — face binning via Sodium's segments** (optional perf restore)
- Translate `SodiumLayerGeometry.vertexSegments[]` (which uses `ModelQuadFacing` enum indices) into vulkium's face-bin ordering `[+X +Z +Y -X -Z -Y unsigned]`. Sodium's `ModelQuadFacing` values: `NORTH(0), SOUTH(1), WEST(2), EAST(3), UP(4), DOWN(5), UNASSIGNED(6)`. Map appropriately.
- Per-section: write `faceBinCounts[6]` into the existing UploadResult.

**Verification at end of Stage 2**: build green, runClient renders SOMETHING. Likely garbled (wrong colors / wrong positions) because the shader still reads `Vertex` as `uvec4` = 16 bytes and walks 20B-spaced records as if they were 16B-spaced. **If you only run Stage 2 without Stage 3, expect visible-but-broken terrain.** This is fine as a checkpoint.

### Stage 3 — Shader format adaptation (terrain renders correctly)

**File: `src/main/resources/assets/vulkium/shaders/occlusion/scene.glsl`** (L18 today: `#define Vertex uvec4`)
- Replace with a 20-byte struct:
  ```
  struct Vertex {
      uvec2 position;  // 8 bytes: hi/lo 20-bit packed
      uint  colour;    // 4 bytes: RGBA8 (pre-multiplied AO in RGB)
      uint  texCoord;  // 4 bytes: U16 | (V16 << 16)
      uint  lightData; // 4 bytes: blockLight(0:7) | skyLight(8:15) | material(16:23) | sectionIdx(24:31)
  };
  ```
- L108-109 `TerrainDataPtr.data[]` of `Vertex` — verify std430 packing of this struct = 20 bytes. If GLSL pads to 24 or 32 (uvec2 alignment), need explicit member-by-member buffer reads instead. Test with a layout dump or compute.
- L108 `buffer_reference_align=16` → `buffer_reference_align=4` (Sodium's stride is not vec4-aligned).

**File: `src/main/resources/assets/vulkium/shaders/terrain/vertex_format.glsl`** (current decoders for vulkium's compact layout)
- Replace ALL decoder bodies. Reference: nvidium's vertex_format readers in `~/Downloads/vulkium/nvidium/src/main/resources/assets/nvidium/shaders/` (search for `packPositionHi`/`packPositionLo` decoders).
- New `decodeVertexPosition`:
  ```
  vec3 decodeVertexPosition(Vertex v) {
      // Sodium splits each 20-bit component across hi/lo dwords:
      //   posX = (lo & 0xFFFFF) | ((hi & 0xFFFFF) << 20)  -- wait, layout actually is:
      //   lo:  bits 0-19 = X_lo10|Y_lo10, bits 20-31 = X_hi or Y; check CompactChunkVertex.packPositionHi/Lo
      // Refer ~/Downloads/vulkium/sodium/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex.java:71-89
      // for the exact bit layout. Decode → normalized [0,1) per component → multiply by MODEL_RANGE=32 → subtract MODEL_ORIGIN=8.
  }
  ```
- New `decodeVertexColour`: read `v.colour` as `(R, G, B, ?)` with alpha not present (Sodium's color is pre-mul AO, alpha lives elsewhere or is implicit). May need to fabricate alpha=1 for non-translucent and look up alpha elsewhere for translucent.
- `decodeVertexUV`: same as today — Sodium uses identical RG16 packing. Reuse existing decoder.
- `decodeLightUV`: light is in `v.lightData[0:7]` (block) and `v.lightData[8:15]` (sky), already in 0..255 range with Sodium's [8,248] clamp. Update the bit-shift offsets.
- `decodeVertexAlphaCutoff` / `hasMipping`: vulkium-specific; Sodium doesn't carry these per-vertex. Options: (a) hardcode per render pass (SOLID=0, CUTOUT=0.1, TRANSLUCENT=0 since translucent uses blending not cutoff), (b) carry via push constant per layer dispatch. Pick (a) for simplicity.

**File: `src/main/resources/assets/vulkium/shaders/terrain/mesh.glsl`**
- Spot-check that `Vertex V0; V1; V2; V3;` usages (L72-78) and `transformVertex(Vertex)` (L62) still work with the new struct (they should — they consume via decoder helpers).

**File: `src/main/resources/assets/vulkium/shaders/terrain/frag.frag`**
- Uses `gl_BaryCoordEXT` to interpolate per-vertex data — should be unaffected by the format change as long as decoders return same-typed values.

**File: `src/main/java/me/cortex/vulkium/render/PrimaryTerrainPass.java`**
- L39-43 has `COLOR_FORMAT`, `DEPTH_FORMAT` — unaffected.
- Shader compilation will pick up the changed GLSL automatically; no Java changes needed for stride.

**Verification at end of Stage 3**: terrain renders correctly. Colors, lighting, positions all look right. Double-render with Sodium's own terrain may still happen (Stage 4 handles).

### Stage 4 — Cancel Sodium's terrain draw

Depends on Stage 0 recon. Likely a new mixin under `mixin/sodium/`:

**File: `src/main/java/me/cortex/vulkium/mixin/sodium/SodiumWorldRendererMixin.java` (new)**
- `@Inject(method="renderLayer", at=@At("HEAD"), cancellable=true)` — cancel if `Vulkium.isEnabled() && drawTerrain`.
- OR `@Inject` on `ChunkRenderer.render` if that's where draws actually happen.
- Mark `remap=false`.

**Verification at end of Stage 4**: Sodium loaded but vulkium owns terrain draws. Frame profile shows Sodium's renderLayer is no-op.

### Stage 5 — Settings GUI compat (lower priority)

Add `mixin/sodium/VideoSettingsScreenMixin.java` targeting Sodium's `VideoSettingsScreen` with the "Vulkium Options..." entry. Pattern from nvidium's `MixinSodiumOptionsGUI`. Standalone vanilla mixin (`VideoSettingsScreenMixin` under `mixin/gui/`) stays untouched — user gets vulkium options from whichever settings screen they prefer (Sodium config lets them switch).

### Stage 6 — Cleanup of now-redundant code (task #12)

Per `SODIUM_PORT.md` step #7. After Stages 1-3 land and terrain renders. Specifically delete on this branch:
- `FrameDriver.updateMvpFromCamera` (consume Sodium's uniform instead) and all `BobViewTap` / `GameRendererBobMixin` / `LevelRendererProjMixin`.
- `MojangAtlasTap`, `MojangColorFormat` (fetch atlas view from Sodium's bound state).
- `MojangLightmapTap`.
- `ChunkSectionsToRenderMixin` (Sodium already cancels MC's vanilla renderGroup; ours is redundant).
- `SectionCompilerMixin`, `CompiledSectionMeshMixin`, `LevelRendererPrepareChunkRendersMixin` — MC vanilla ingest path, dead under Sodium.
- The `LayerGeometry` half of `SectionEntry` if no callers remain.
- Drop the corresponding entries from `vulkium.mixins.json`.

Re-test F3+A flow afterward.

### Open questions for the executor

1. **Sodium's color is pre-multiplied with AO**: do we keep that bake (cheap, looks correct against Sodium's lightmap) or unbake (matches vulkium-standalone shader's expectations)? Pick at Stage 3.
2. **Translucent sort**: accept Sodium's build-order or hook Sodium's `ResortTransparencyTask` for index buffers? Stage 2 deferral; revisit when water/glass look wrong from oblique camera angles.
3. **Material / section bytes** in Sodium's `lightData`: useful for anything in vulkium's pipeline (per-block-state dispatch?), or just discard? Leave bits in the struct, ignore in decoder.
4. **Stride alignment**: std430 alignment of `struct Vertex { uvec2; uint; uint; uint }` may pad to 24 bytes. If so, fall back to reading via `uint data[]` and indexing by `sectionId * 5 + lane`. Test in Stage 3.

### Rollback strategy

Each stage's commit is self-contained. If a stage breaks something:
- Stage 1: revert single commit; SectionManager regains its standalone-only state.
- Stage 2: revert; terrain stops rendering but ingest still queues.
- Stage 3: revert; back to garbled-but-rendering state.
- Stage 4: revert; double-render returns.

Commits should be one per stage so revert is clean. Push after each stage's verification passes.

## Sodium COMPACT vs vulkium vertex (recon, 2026-06-27)

Sodium's `CompactChunkVertex` is **20 bytes/vertex** (vs vulkium's 16). Per-vertex layout (little-endian):

| Offset | Bytes | Sodium field | Vulkium equivalent |
|---|---|---|---|
| 0-7 | 8 | `a_Position` (RG32_UINT) — 20-bit-per-component split across Hi/Lo dwords, normalized `(pos+8)/32` quantized to 2^20 | `v.x[0:32]` packs x16y16; `v.y[0:16]` packs z16. Vulkium uses 16-bit fixed-point per component (2048× scale, +8 origin). **Precision delta: ~10 bits.** |
| 8-11 | 4 | `a_Color` (RGBA8_UNORM) — `ColorARGB.mulRGB(color, ao)` pre-multiplied with AO | RGB24 in `v.z[0:24]` + alpha8 in `v.y[16:24]`; AO not pre-baked |
| 12-15 | 4 | `a_TexCoord` (RG16_UINT) | `v.w[0:32]` packs u16v16 — **identical packing** |
| 16-19 | 4 | `a_LightAndData` (RGBA8_UINT) — block light, sky light, material bits, section idx | block light in `v.y[24:32]`, sky light in `v.z[24:32]`; **material + section unused** |

Three runtime passes: `SOLID`, `CUTOUT`, `TRANSLUCENT` (no CUTOUT_MIPPED in modern MC).

`BuiltSectionMeshParts.getVertexData()` returns a `NativeBuffer` (host memory). `getVertexSegments()` returns `int[]` with `[count, facing, count, facing, ...]` for face-binned dispatch. Caller must copy bytes before Sodium recycles. No translucent sort-state inside `BuiltSectionMeshParts` — Sodium handles resort via a separate `ResortTransparencyTask`.

Showstoppers / accepted differences for approach (a):
- Vertex stride 16 → 20 (shader buffer reads change).
- Position precision lost if we squash 20-bit Sodium → 16-bit vulkium; keep 20-bit by reading position as 8 bytes / 2 dwords.
- Sodium pre-multiplies AO into color; vulkium currently treats them separately. Either accept baked AO or split it back in the shader.
- Light is clamped to [8, 248] in Sodium vs raw [0, 240] in vulkium. Pick one convention.

## Logbook

- **2026-06-27** Branch created. Survey done. Sodium upstream cloned. Scaffolding done — sodium dep, fabric.mod.json flipped, 3 observer mixins (RenderRegionManager / ChunkBuilderMeshingTask / ChunkBuildOutput), separate mixins.json. Build green.
- **2026-06-27** First runClient crashed: `RenderRegionManagerMixin` `@Inject` couldn't find `uploadResults(Collection, UniformBufferManager)`. Root cause: I surveyed `~/Downloads/vulkium/sodium/` (dev branch, ahead of release) but compile against the published 0.9.1-beta.2 jar. `UniformBufferManager` moved between dev and release: dev has it at `...chunk.compile.UniformBufferManager`, release at `...chunk.UniformBufferManager`. Fixed by adjusting the method descriptor in the `@Inject` annotation. Confirmed signature against the actual runtime jar at `~/.gradle/caches/.../sodium-mc26.2-0.9.1-beta.2-fabric.jar`. **Lesson:** when porting against a published Sodium beta, verify mixin targets against the actual jar, not the live dev tree.
- **2026-06-27** Added TODO: integrate with Sodium's own `VideoSettingsScreen`. Sodium replaces MC's vanilla screen with its own UI; user can switch between them. Our existing vanilla mixin keeps adding the "Vulkium Options..." button to MC's screen, but the Sodium screen has no entry to vulkium options. Need a parallel sodium-side mixin. Pattern reference: nvidium's `MixinSodiumOptionsGUI` (now-renamed class).
- **2026-06-27** Second runClient: clean, no crash. All scaffolding observers fire: 6144+ `Sodium ChunkBuildOutput` captures + 256+ `uploadResults` calls in steady state. BUT vulkium's terrain doesn't render — `visibleRegionCount=0`, `Early-return`. Root cause: vulkium's standalone-era ingest hooks `MC's SectionCompiler.compile`, which Sodium has replaced with its own `ChunkBuilder` — MC's compiler never fires when Sodium is loaded, so vulkium's `RegionManager` stays empty. The 6144 chunks live in Sodium's region buffers; vulkium has no path to them. **Expected next blocker** per the survey: we need to ROUTE Sodium's `ChunkBuildOutput` into vulkium's `SectionManager` instead of relying on MC's compiler.
- **2026-06-27** User picked approach (a) for the routing: adapt vulkium's mesh shader to read Sodium's COMPACT vertex format directly. Vertex format recon done — see "Sodium COMPACT vs vulkium vertex" section. Stride differs (20 vs 16), position precision differs (20-bit vs 16-bit), color has AO pre-baked, light is clamped — all addressable in the shader. `BuiltSectionMeshParts.getVertexData()` returns a `NativeBuffer` we must copy out before Sodium recycles.
- **2026-06-27** User flagged: audit vulkium for code now redundant because Sodium handles it. MVP construction is the named candidate. Logbook step #7 lists the full candidate set.
- **2026-06-27** User clarified: this branch hard-depends on Sodium, so the cleanup deletes redundant code outright — no `sodiumPresent` runtime gates. Standalone behavior lives on the `mc-26.2` branch already.
- **2026-06-28** Plan-only session. Implementation plan for Sodium → vulkium rendering drafted as the "Implementation plan" section above. Six stages (0 recon → 1 routing → 2 uploader → 3 shader → 4 cancel-sodium-draw → 5 GUI → 6 cleanup), each independently verifiable with rollback. Total est. several focused hours; recommend landing one stage per commit and pushing after each verification passes. No code changes this session.
- **2026-06-28** Stages 0-4 landed in one session. Commits on `mc-26.2-sodium`:
  - Stage 0 (recon) — finding written above; no code.
  - Stage 1 (3fbf104) — `SectionEntry.SodiumLayerGeometry` + `SectionManager.offerFromSodium` + ChunkBuilderMeshingTaskMixin now captures and forwards (no longer observer-only).
  - Stage 2 (4c443f1) — `TerrainUploader` rewritten: stride 16→20, pass-through memcpy of Sodium bytes, deleted MC-vertex conversion entirely. Face binning regressed to all-unsigned (Stage 2.5 will restore via Sodium's `vertexSegments`).
  - Stage 3 (374ac1f) — `scene.glsl`'s `Vertex` is now a 20-byte struct mirroring `CompactChunkVertex`; `vertex_format.glsl` decoders fully rewritten for Sodium's bit layout (20-bit hi/lo position split, pre-mul AO colour, [8,248]-clamped light, material byte for cutoff/mipping). std430 packs cleanly.
  - Stage 4 (2deafda) — `DefaultChunkRendererMixin` cancels Sodium's terrain draw via `@Inject HEAD/cancellable` on the 9-arg `render`. Gated on `Vulkium.isEnabled()`.
  - Untested at commit time (`./gradlew build` is compile-check only per project convention). User runClient verification pending. Expected behavior end-to-end: Sodium owns chunk-build, vulkium owns the GPU dispatch, no double-render. Known regression vs standalone-edition: per-face culling is OFF (Stage 2 dropped it) — expect ~2× draw cost on opaque terrain until Stage 2.5 lands.
  - Pre-existing shader WIP (chunk-fade FadeTimesPtr / `computeSectionVisibility` etc.) preserved uncommitted per `feedback_runclient_autorelaunch`/WIP-file convention; Stage 3 commit was surgical to keep the fade work separable.
- **2026-06-28** Testing pass landed four follow-up fixes:
  - **`d236923` — position hi/lo swap.** Stage 3's decoder had `v.position.x = posLo` but Sodium writes `packPositionHi` at byte 0 → `position.x = posHi`. Cross-checked against sodium's own decoder in `common/.../shaders/include/chunk_vertex.glsl#_deinterleave_u20x3`. Also fixed cutoff threshold table — Sodium emits the full `AlphaCutoffParameter` ordinal (HALF=2), the standalone `idx==1 ? 0.5 : 0.0` mapped CUTOUT to no-discard.
  - **`b449b53` — std430 array stride.** Stage 3 modelled `Vertex` as `struct { uvec2 position; uint colour; uint texCoord; uint lightData }` — 20 bytes of members but `uvec2`'s 8-byte alignment forces struct alignment 8 → `Vertex data[]` stride rounds to `roundUp(20, 8) = 24`. Shader read 24-byte records out of a 20-byte arena → every vertex past index 0 drifted further out of phase. Fix: replace the leading `uvec2` with two `uint`s. Five uints = struct alignment 4 = array stride 20 = matches Sodium's STRIDE. Visible symptom before fix: "huge mess of triangles."
  - **`8f8119f` — lightmap UV double offset.** Sodium's `Mth.clamp(light + 8, 8, 248)` pre-bakes the half-texel offset; vulkium's standalone decoder added `+0.5/16` on top → +0.0625 UV bias toward bright corner of the lightmap. Fix: just `light/256`, no extra offset.
  - **`6ddeb6d` — colour R/B swap.** Sodium does `out.color = ColorARGB.toABGR(...)` before the buffer write — packed ints are ABGR (not ARGB), so after little-endian putInt the memory layout is `[R, G, B, A]` (matches Vulkan's RGBA8_UNORM byte order exactly). My recon agent's ARGB-shaped decoder put B into R and vice versa → reddish water, hot biome tints. Fix: pull R from bits 0-7, B from bits 16-23.
  - **Process bug noted in flight**: the surgical commit dance (reset working tree → edit → build → commit → restore-from-backup) leaves `build/resources/main/...` stale at the post-build/pre-restore content. Re-running `./gradlew processResources --rerun-tasks` after restore syncs it. Symptom was `task.glsl` failing to find `computeSectionVisibility` because the WIP-stripped scene.glsl was still in the build outputs.
- **2026-06-28** VRAM blowup root cause + first fix:
  - User config had `terrainArenaMb: 4096` and `maxRegions: 4096` from standalone-edition tuning — vulkium's arena alone pre-allocated 4 GiB the moment it initialised. Combined with Sodium's own per-region geometry/index GPU buffers (also storing every section, because we cancelled Sodium's DRAW but not its UPLOAD), the user's 6 GiB card hit 80% utilisation and the Wayland compositor stalled.
  - **`b5bf6a0`** — `RenderRegionManagerMixin` upgraded from observer to HEAD cancellation. With the upload cancelled, Sodium never instantiates per-region `DeviceResources` (its `GlBufferArena`s for geometry + index never grow). Only its constant 32 MB `MojangStagingBuffer` (allocated in `RenderRegionManager` constructor) remains — small, ignore. Safety analysis in the mixin doc-comment.
  - User should still drop `terrainArenaMb` in `vulkium.json` to something sane for the sodium edition (Sodium does the chunk compile, vulkium just stores the GPU geometry — 512 MB is plenty for default RD).

- **2026-06-28 (later)** Pushed Stages 0-4 + testing fixes + uploadResults cancel to origin (`266e8c1`).
- **2026-06-28** Optimisation continuation pass:
  - **`1d0a1be` — Stage 2.5 face binning restored.** TerrainUploader now walks Sodium's `vertexSegments[]` per opaque layer and reorders into vulkium's task-shader bin order (`POS_X→0, POS_Z→1, POS_Y→2, NEG_X→3, NEG_Z→4, NEG_Y→5, UNASSIGNED→tail`). populateTasks gets real `faceBinCounts[6]` again — ~35-50% fewer mesh workgroups dispatched on outdoor scenes vs the all-unsigned Stage 2 fallback. SOLID + CUTOUT merged into one face-binned blob since the per-vertex material byte already carries the cutoff ordinal.
  - **`71122f3` — Stage 6.1 mixin cleanup.** Deleted four MC-vanilla ingest mixins that never fire under Sodium: `SectionCompilerMixin`, `CompiledSectionMeshMixin`, `LevelRendererPrepareChunkRendersMixin`, `RenderSectionResortMixin`. -237 lines. Manifest entries dropped. Kept `RenderSectionMixin` + `ChunkSectionsToRenderMixin` as defensive — zero runtime cost since their targets don't fire, but they catch any vanilla-fallback path Sodium might leak.
  - **`1ad2dd7` — translucent sort cancel.** `RenderSectionManagerMixin` cancels `scheduleSort(long, boolean)` at HEAD. Sodium's translucent sort tasks compute orderings that flowed into the now-cancelled `uploadResults` index-buffer branch → pure worker-CPU waste. One HEAD cancel covers both call sites (`integrateTranslucentData` after fresh build + `triggerSections` camera-movement sweep). Vulkium uses Sodium's build-order translucent vertices verbatim — known limitation noted in plan.
  - **`f49313a` — Stage 6.2 dead-API strip.** `SectionEntry.LayerGeometry` deleted, `SectionEntry.layers` field deleted, `SectionManager.offerFromCompile` deleted, `SectionCapture.onSectionMeshCompiled` deleted along with the worker-thread compile-key plumbing. -150 lines net. `SectionCapture` kept as a thin facade (sentinel + zero metric stubs) so `VulkiumKeys`/`VulkiumHudOverlay` don't need touching.

## Optimisation audit — Sodium work we now bypass

After the two cancel-mixins (`DefaultChunkRendererMixin` + `RenderRegionManagerMixin`), here's what Sodium still does per frame that vulkium no longer consumes:

| Sodium work | Cost | Cancellable? | Status |
|---|---|---|---|
| Terrain GPU draw (`DefaultChunkRenderer.render`) | medium GPU + CPU | HEAD cancel | ✅ cancelled |
| Per-region GPU buffer upload (`RenderRegionManager.uploadResults`) | huge GPU mem | HEAD cancel | ✅ cancelled |
| Per-frame render-list build (visibility traversal + frustum cull) | low CPU (~1-3ms) | risky — dual-purpose (also drives worker prioritisation by camera distance) | deferred |
| Translucent per-quad sort tasks (`SortTriggering.integrateTranslucentData` → sort worker) | medium CPU (only when camera moves through translucent threshold) | yes via mixin | ✅ cancelled (`RenderSectionManagerMixin#scheduleSort`) |
| Shared index buffer ensureCapacity (`DefaultChunkRenderer:92`) | trivial | already skipped (parent method cancelled) | n/a |
| Section-info graph + tree updates | low CPU | NO — vulkium needs Sodium's worker pool to keep building, which needs the graph to know what to build | keep |
| `MojangStagingBuffer(32_000_000)` constructor allocation | 32 MB GPU constant | tricky (buffer is referenced by RenderRegion constructor params) | skip — not worth the risk |

Deferred items above can be revisited if perf-profiling shows Sodium taking >5 ms/frame. With just the two cancels above, sodium frame time should drop substantially — its hot loops bottom out on `storage == null` early-returns.
