# Vulkium verification checklist (V10)

End-to-end validation matrix for shipping a vulkium build. Run through this before tagging a
release; failures gate the tag.

## 0 — Environment baseline

- [ ] Linux, macOS, or Windows with a Turing+ / RDNA2+ / Arc+ GPU (any GPU exposing
      `VK_EXT_mesh_shader`).
- [ ] Current Vulkan drivers (Mesa 24+ / NVIDIA 555+ / AMD 24.10+).
- [ ] JDK 25 on PATH (`java -version` → `25.x.y`).
- [ ] MC 26.2-snapshot-3 client profile with Fabric Loader + Fabric API installed.
- [ ] Vulkium jar built via `JAVA_HOME=/home/hawaf/javas/jdk-25.0.1 ./gradlew build` —
      artifact at `build/mods/vulkium-*-fabric.jar`.
- [ ] No Sodium, no Iris, no competing mod (the `breaks` block in `fabric.mod.json` already
      prevents this — confirm loader log shows no conflicts).

## 1 — Boot sequence (cold start)

Launch MC 26.2-snapshot-3 with vulkium, attach log viewer, do NOT enter a world yet.

- [ ] `[vulkium]` log line: `loaded (MC initialized)`.
- [ ] `[vulkium]` log line: `Vulkium ENABLED.` followed by probe result (device name, enabled
      feature set).
- [ ] `[vulkium]` log line: `Mojang backend = Vulkan.` (if log says OpenGL, switch MC to
      "Prefer Vulkan" in Video Settings and relaunch).
- [ ] `[vulkium/shader-check]` log line: `Shader sanity-check: N/N OK in <M> ms` with
      **zero FAILs**. Every shader in the matrix compiles via shaderc.
- [ ] `[vulkium/smoke]` log line: `Compute smoke-test PASSED — 64 threads, out[i]==i*i
      verified.` Confirms shaderc + pipeline + dispatch + readback all wire correctly.
- [ ] `[vulkium]` log line: `RegionManager bound (1024 regions × 256 sections/region).`
- [ ] `[vulkium/render]` log line: `Renderer initialized (SceneUniform …; VisibilityTracker;
      RegionSorter pipeline compiled).` — confirms region_section_sorter.comp compiles on
      this GPU.
- [ ] No `VK_ERROR_*` log lines.
- [ ] No Java stack traces involving `me.cortex.vulkium.*`.

If any of the above fails, stop — fix before proceeding.

## 2 — World entry

Create or load a superflat / creative flat test world. Stand still at spawn for ~10 seconds.

- [ ] MC renders the world normally (vanilla terrain path is still active — vulkium hasn't
      taken over draws yet).
- [ ] F3 opens. Bottom of left column shows:
      `[vulkium] frames=<N> captured=<M> liveSections=<K> regions=<R> visible=<V> cull=<T>us vb=<X>MB ib=<Y>MB`
- [ ] `captured` grows as chunks load.
- [ ] `liveSections` tracks `captured` within a few hundred (the rest are MC's own
      unloads / recompiles).
- [ ] `regions` > 0 once chunks load.
- [ ] `visible` > 0 once camera has a view frustum.
- [ ] `cull` is under 500 µs at 16-chunk render distance.

## 3 — Movement / chunk churn

Fly in creative for 60 seconds through the world at 1–2 chunks/sec.

- [ ] `captured` keeps growing (new sections compile as the player moves).
- [ ] `liveSections` stays bounded — it should not grow indefinitely. Eviction is working.
- [ ] `regions` stays ≤ 1024 (the `MAX_REGIONS` cap). If it approaches, that's a bug — check
      eviction.
- [ ] No memory leak in the client's JVM heap (track with VisualVM or
      `-XX:+HeapDumpOnOutOfMemoryError`). vb+ib totals grow with captured section count but
      plateau once chunks start recycling.

## 4 — Diagnostic keybind

- [ ] Press F6 in a world. Chat displays two `[vulkium]` lines matching the F3 overlay.
- [ ] Log captures the same lines.
- [ ] Holding F6 does not spam (edge detection works).

## 5 — Shutdown

Return to title screen, then quit MC cleanly.

- [ ] No shutdown-order crashes (VMA teardown vs. vulkium-owned buffers).
- [ ] Log shows `RegionSorter close failed` only on controlled shutdown paths — or not at
      all on a clean shutdown.
- [ ] No trailing `[vulkium]` WARN or ERROR lines after world leave.

## 6 — Regression comparison (optional)

Against a clean vanilla MC 26.2 instance (same profile, no vulkium):

- [ ] At 16 chunks render distance, standing still, frame time is within 5 % of vanilla
      (vulkium is observe-only today; regressions here mean our mixins cost more than
      expected).
- [ ] `jstack` on both processes: vulkium's thread list shows only the expected additions
      (no rogue worker thread loops).

## 7 — OpenGL backend fallback

Toggle MC to "Prefer OpenGL" in Video Settings, relaunch.

- [ ] `[vulkium]` log line: `Vulkium DISABLED. Reason: Mojang backend not Vulkan`.
- [ ] No ERROR-level log from vulkium.
- [ ] MC runs normally — vulkium is dormant.
- [ ] F3 overlay shows `[vulkium] disabled (Mojang backend not Vulkan)`.

## 8 — Release sign-off

All sections 0–6 pass, section 7 passes, and:

- [ ] `git status` clean (no uncommitted changes).
- [ ] `git log --oneline -1` matches the release tag.
- [ ] Artifact filename matches `fabric.mod.json` version.

Sign off with the tested GPU model + driver version in the release notes.
