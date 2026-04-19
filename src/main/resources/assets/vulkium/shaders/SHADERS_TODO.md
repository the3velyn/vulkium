# Shader translation tracker (V6)

Target: `VK_EXT_mesh_shader` + `GL_EXT_buffer_reference` + MC 26.2 Vulkan backend.
All shaders compile at runtime via `com.mojang.blaze3d.vulkan.glsl.GlslCompiler`.

## Status matrix

| File | NV→EXT done | Buffer-ref done | Notes |
|------|:---:|:---:|-------|
| `occlusion/scene.glsl` | n/a | ✅ | Pointer types translated to `buffer_reference` blocks |
| `terrain/task_common.glsl` | ✅ | ✅ | Payload restructured to `taskPayloadSharedEXT`; `populateTasks` now returns the task count |
| `terrain/task.glsl` | ✅ | ✅ | Uses `EmitMeshTasksEXT(N, 1, 1)` |
| `terrain/mesh.glsl` | ⚠️ |  ✅ | Pointer-indexing rewritten; `gl_MeshVerticesNV/gl_PrimitiveIndicesNV/gl_PrimitiveCountNV` still NV |
| `terrain/frag.frag` | ⚠️ | ✅ | `gl_BaryCoordNV`; needs `GL_EXT_fragment_shader_barycentric` + `gl_BaryCoordEXT` |
| `terrain/temporal_task.glsl` | ⚠️ | ✅ | Same shape as `task.glsl`; port identically |
| `terrain/translucent/task.glsl` | ⚠️ | ✅ | Same shape as `task.glsl`; port identically |
| `terrain/translucent/mesh.glsl` | ⚠️ | ✅ | Uses shared-memory sort; subgroup ops stay |
| `terrain/fog.glsl` | ✅ | ✅ | No mesh-shader code; was clean |
| `terrain/vertex_format.glsl` | ✅ | n/a | Scalar helpers; no changes needed |
| `occlusion/region_raster/mesh.glsl` | ⚠️ | ✅ | Small — emits region AABBs |
| `occlusion/region_raster/fragment.frag` | ✅ | ✅ | No mesh-shader code |
| `occlusion/section_raster/task.glsl` | ⚠️ | ✅ | Port identical to `terrain/task.glsl` pattern |
| `occlusion/section_raster/mesh.glsl` | ⚠️ | ✅ | Section AABB emission |
| `occlusion/section_raster/fragment.glsl` | ✅ | ✅ | No mesh-shader code |
| `occlusion/queries/region/mesh.glsl` | ⚠️ | ✅ | Visibility-query mesh shader |
| `occlusion/queries/region/fragment.frag` | ✅ | ✅ | No mesh-shader code |
| `sorting/region_section_sorter.comp` | n/a | ✅ | Compute shader — no mesh-shader code |
| `sorting/sorting_network.glsl` | n/a | n/a | Pure algorithm, reused as-is |

Legend: ✅ done · ⚠️ needs NV→EXT mesh builtin rewrite · `n/a` no applicable work

## Recipe for each `⚠️ NV→EXT mesh` file

1. In the `#extension` header: drop `GL_NV_mesh_shader`, `GL_NV_gpu_shader5`, `GL_NV_bindless_texture`;
   add `GL_EXT_mesh_shader`.
2. In task shaders: replace `gl_TaskCountNV = N;` with a trailing `EmitMeshTasksEXT(N, 1, 1);`
   (returns immediately). Move bare top-level writes (`quadCount = ...;`) onto `payload.*`.
3. In mesh shaders:
   - `layout(triangles, max_vertices=V, max_primitives=P)` stays.
   - Add a single `SetMeshOutputsEXT(vertCount, primCount);` call before any output writes.
   - `gl_MeshVerticesNV[i]` → `gl_MeshVerticesEXT[i]` (field names identical).
   - `gl_PrimitiveIndicesNV[i*3+k]` → `gl_PrimitiveTriangleIndicesEXT[i] = uvec3(a, b, c);` (one
     write per triangle instead of three per-index writes).
   - `gl_PrimitiveCountNV = N;` → drop (replaced by `SetMeshOutputsEXT`).
   - `taskNV in Task { ... }` → redeclare the same `TaskPayload` struct + `taskPayloadSharedEXT`.
4. In fragment shaders using barycentrics: add `#extension GL_EXT_fragment_shader_barycentric : require`
   and replace `gl_BaryCoordNV` with `gl_BaryCoordEXT`.

Already applied globally (V6 partial):
- `<nvidium:...>` → `<vulkium:...>` namespace rename across all 14 shader files.
- `name[idx]` → `name.data[idx]` for every buffer_reference pointer type in scene.glsl (for files
  other than scene.glsl itself).
