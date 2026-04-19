package me.cortex.vulkium.vk;

import me.cortex.vulkium.blaze3d.MojangVulkanBridge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * End-to-end compute-dispatch validation. Compiles
 * {@code assets/vulkium/shaders/diag/compute_smoke.comp}, builds a pipeline with a push
 * constant carrying a buffer-reference + count, dispatches 64 threads, and reads back the
 * results to confirm {@code out[i] == i*i}.
 *
 * <p>Exercises: shaderc compile → {@code vkCreateShaderModule} → {@code vkCreatePipelineLayout}
 * with push constants → {@code vkCreateComputePipelines} → {@code CommandRecorder.recordAndSubmit}
 * → {@code vkCmdPushConstants} → {@code vkCmdDispatch} → {@code vkCmdPipelineBarrier2} →
 * host readback via VMA-mapped memory. If this passes, the V7 compute path is live.
 */
public final class ComputeSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("vulkium/smoke");

    private static final int THREAD_COUNT = 64;

    private ComputeSmokeTest() {}

    public static boolean run() {
        long vma = MojangVulkanBridge.vma();

        // Allocate a HOST-visible readback buffer so we can verify the dispatch output.
        long bufferHandle;
        long allocation;
        long bufferAddr;
        long mappedPtr;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size((long) THREAD_COUNT * 4)
                .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCreate = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                    | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAllocation = stack.callocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            int r = Vma.vmaCreateBuffer(vma, bufferInfo, allocCreate, pBuffer, pAllocation, allocInfo);
            if (r != VK10.VK_SUCCESS) {
                if (r == -3 /* VK_ERROR_INITIALIZATION_FAILED */) {
                    // Classic symptom: Mojang's VkDevice wasn't created with
                    // bufferDeviceAddressFeature.bufferDeviceAddress=true, so VMA rejects
                    // buffers flagged VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT. Not a bug in
                    // vulkium — just the GPU/driver/launcher combination. Skip silently.
                    LOGGER.info("Compute smoke-test SKIPPED — Mojang's VkDevice does not have bufferDeviceAddress enabled.");
                } else {
                    LOGGER.warn("smoke: vmaCreateBuffer failed: VkResult={}", r);
                }
                return false;
            }
            bufferHandle = pBuffer.get(0);
            allocation = pAllocation.get(0);
            mappedPtr = allocInfo.pMappedData();

            org.lwjgl.vulkan.VkBufferDeviceAddressInfo addrInfo =
                org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(bufferHandle);
            bufferAddr = VK12.vkGetBufferDeviceAddress(MojangVulkanBridge.vkDevice(), addrInfo);
        }

        // Zero the buffer so we can detect writes.
        MemoryUtil.memSet(mappedPtr, 0, (long) THREAD_COUNT * 4);
        Vma.vmaFlushAllocation(vma, allocation, 0, (long) THREAD_COUNT * 4);

        try (ShaderModule compute = ShaderModule.compileFromResource(
                 "diag/compute_smoke.comp", ShaderStage.COMPUTE, java.util.Map.of());
             PipelineLayout layout = PipelineLayout.builder()
                 .pushConstants(VK10.VK_SHADER_STAGE_COMPUTE_BIT, 16 /* 8B ptr + 4B count + 4 padding */)
                 .build();
             ComputePipeline pipeline = ComputePipeline.create(compute, layout)) {

            final long bh = bufferHandle;
            final long ba = bufferAddr;
            final long la = layout.handle();
            final long ph = pipeline.handle();

            CommandRecorder.recordAndFlushNow(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, ph);

                    ByteBuffer pc = stack.calloc(16);
                    pc.putLong(0, ba);
                    pc.putInt(8, THREAD_COUNT);
                    VK10.vkCmdPushConstants(cmd, la, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

                    int groups = (THREAD_COUNT + 63) / 64;
                    VK10.vkCmdDispatch(cmd, groups, 1, 1);

                    // Ensure the write is visible to host reads before we read the mapped memory.
                    VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack)
                        .sType$Default()
                        .srcStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                        .srcAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT)
                        .dstStageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                        .dstAccessMask(org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(bh).offset(0).size(VK10.VK_WHOLE_SIZE);

                    VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pBufferMemoryBarriers(barrier);
                    // Use KHR entrypoint — Mojang enables VK_KHR_synchronization2 (VK 1.2 path),
                    // not VK 1.3 core. The non-KHR function pointer would be null.
                    org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
                }
            });

            // Submit happens inside the encoder's frame; caller must flush the submit to actually
            // run this. For a startup smoke test we need an explicit wait-for-idle.
            VK10.vkDeviceWaitIdle(MojangVulkanBridge.vkDevice());
            Vma.vmaInvalidateAllocation(vma, allocation, 0, (long) THREAD_COUNT * 4);

            ByteBuffer readback = MemoryUtil.memByteBuffer(mappedPtr, THREAD_COUNT * 4);
            StringBuilder wrong = new StringBuilder();
            int wrongCount = 0;
            for (int i = 0; i < THREAD_COUNT; i++) {
                int got = readback.getInt(i * 4);
                int expect = i * i;
                if (got != expect) {
                    wrongCount++;
                    if (wrong.length() < 200) {
                        wrong.append(" [").append(i).append("] expected=").append(expect)
                            .append(" got=").append(got);
                    }
                }
            }
            boolean ok = wrongCount == 0;
            if (ok) {
                LOGGER.info("Compute smoke-test PASSED — {} threads, out[i]==i*i verified.", THREAD_COUNT);
            } else {
                LOGGER.warn("Compute smoke-test FAILED — {} wrong slots:{}", wrongCount, wrong);
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.error("Compute smoke-test crashed", t);
            return false;
        } finally {
            Vma.vmaDestroyBuffer(vma, bufferHandle, allocation);
        }
    }
}
