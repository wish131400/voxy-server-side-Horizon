package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Independent compute device. Initialized privately, then submitted and destroyed on one worker;
 * never borrows Minecraft/Voxy's graphics context or queue. */
final class VulkanDensityDevice implements AutoCloseable {
    static final int MAX_COLUMNS = 512;
    static final int PIPELINE_DEPTH = 2;
    private VkInstance instance;
    private VkDevice device;
    private VkQueue queue;
    private long descriptorLayout, pipelineLayout, pipeline, descriptorPool, commandPool;
    private final List<Buffer> buffers = new ArrayList<>();
    private final Submission[] slots = new Submission[PIPELINE_DEPTH];
    private String pipelineCacheIdentity;
    final String name;

    VulkanDensityDevice(JsonObject program) {
        String selectedName;
        try (var stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("VSS prediction compute")).apiVersion(VK_API_VERSION_1_0);
            var created = stack.mallocPointer(1);
            check(vkCreateInstance(VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app), null, created), "instance");
            instance = new VkInstance(created.get(0), VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app));
            var count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "device count");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "devices");
            VkPhysicalDevice physical = null;
            int family = -1, best = -1;
            selectedName = "";
            for (int i = 0; i < devices.capacity(); i++) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                var properties = VkPhysicalDeviceProperties.calloc(stack);
                var features = VkPhysicalDeviceFeatures.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, properties);
                vkGetPhysicalDeviceFeatures(candidate, features);
                if (!features.shaderFloat64() || properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU
                        || properties.limits().maxComputeWorkGroupInvocations() < 32) continue;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int j = 0; j < families.capacity(); j++) {
                    int flags = families.get(j).queueFlags();
                    if ((flags & VK_QUEUE_COMPUTE_BIT) == 0 || families.get(j).queueCount() == 0) continue;
                    int score = properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 100 : 10;
                    if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) score += 1;
                    if (score > best) { best = score; physical = candidate; family = j; selectedName = properties.deviceNameString(); }
                }
            }
            if (physical == null) throw new IllegalStateException("No Vulkan compute GPU with FP64 coordinates");
            var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(0.25f));
            var info = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo)
                    .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(true));
            check(vkCreateDevice(physical, info, null, created), "logical device");
            device = new VkDevice(created.get(0), physical, info);
            var selectedProperties=VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical,selectedProperties);
            byte[] uuid=new byte[VK_UUID_SIZE];selectedProperties.pipelineCacheUUID().get(uuid);
            pipelineCacheIdentity=selectedProperties.vendorID()+"-"+selectedProperties.deviceID()+"-"
                    +selectedProperties.driverVersion()+"-"+HexFormat.of().formatHex(uuid);
            vkGetDeviceQueue(device, family, 0, created);
            queue = new VkQueue(created.get(0), device);

            var data = program.getAsJsonArray("data");
            var constants = buffer(physical, Math.max(4, data.size() * 4));
            for (int i = 0; i < data.size(); i++) constants.mapped.putInt(i * 4, data.get(i).getAsInt());

            var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            var handle = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "descriptor layout");
            descriptorLayout = handle.get(0);
            var push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, handle), "pipeline layout");
            pipelineLayout = handle.get(0);
            createPipeline(program.get("source").getAsString());
            var poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3 * PIPELINE_DEPTH);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(PIPELINE_DEPTH).pPoolSizes(poolSize), null, handle), "descriptor pool");
            descriptorPool = handle.get(0);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, handle), "command pool");
            commandPool = handle.get(0);
            for (int slotIndex = 0; slotIndex < PIPELINE_DEPTH; slotIndex++) {
                var slot = new Submission(this);
                slots[slotIndex] = slot;
                slot.input = buffer(physical, MAX_COLUMNS * 16);
                slot.output = buffer(physical, MAX_COLUMNS * 4);
                check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "descriptor set");
                slot.descriptorSet = handle.get(0);
                var writes = VkWriteDescriptorSet.calloc(3, stack);
                Buffer[] all = {slot.input, slot.output, constants};
                for (int i = 0; i < 3; i++) {
                    var b = VkDescriptorBufferInfo.calloc(1, stack);
                    b.get(0).buffer(all[i].handle).offset(0).range(all[i].mapped.capacity());
                    writes.get(i).sType$Default().dstSet(slot.descriptorSet).dstBinding(i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(b);
                }
                vkUpdateDescriptorSets(device, writes, null);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), created), "command buffer");
                slot.commands = new VkCommandBuffer(created.get(0), device);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "fence");
                slot.fence = handle.get(0);
            }
        } catch (RuntimeException | LinkageError failure) {
            close(); throw failure;
        }
        name = selectedName;
    }

    private void createPipeline(String source) {
        byte[] binary = shaderBinary(source);
        ByteBuffer code = MemoryUtil.memAlloc(binary.length);
        code.put(binary).flip();
        long module = 0, cache = 0;
        try (var stack = MemoryStack.stackPush()) {
            Path cachePath=null;
            try {
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
                cachePath=Path.of("cache","vss-vulkan","pipeline-"+pipelineCacheIdentity+"-"+hash+".bin");
            } catch (Exception unavailable) { /* Persistent caching is optional. */ }
            cache=createPipelineCache(cachePath);
            var handle = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "shader module");
            module = handle.get(0);
            var pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelines.get(0).sType$Default().layout(pipelineLayout);
            pipelines.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, cache, pipelines, null, handle), "compute pipeline");
            pipeline = handle.get(0);
            savePipelineCache(cache,cachePath);
        } finally {
            if (cache != 0) vkDestroyPipelineCache(device,cache,null);
            if (module != 0) vkDestroyShaderModule(device, module, null);
            MemoryUtil.memFree(code);
        }
    }
    private long createPipelineCache(Path path) {
        ByteBuffer initial=null;
        try (var stack=MemoryStack.stackPush()) {
            try {
                if(path!=null&&Files.isRegularFile(path)) {
                    long size=Files.size(path);
                    if(size>=32&&size<=64*1024*1024) {
                        byte[] bytes=Files.readAllBytes(path);
                        initial=MemoryUtil.memAlloc(bytes.length);initial.put(bytes).flip();
                    }
                }
            } catch (Exception unavailable) { /* Use an empty driver cache. */ }
            var info=VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(initial);
            var handle=stack.mallocLong(1);
            int result=vkCreatePipelineCache(device,info,null,handle);
            if(result!=VK_SUCCESS&&initial!=null) {
                info.pInitialData((ByteBuffer)null);
                result=vkCreatePipelineCache(device,info,null,handle);
            }
            check(result,"pipeline cache");return handle.get(0);
        } finally { if(initial!=null)MemoryUtil.memFree(initial); }
    }
    private void savePipelineCache(long cache,Path path) {
        if(path==null)return;
        ByteBuffer data=null;
        try(var stack=MemoryStack.stackPush()) {
            var size=stack.mallocPointer(1);
            if(vkGetPipelineCacheData(device,cache,size,null)!=VK_SUCCESS||size.get(0)<32||size.get(0)>64*1024*1024)return;
            data=MemoryUtil.memAlloc((int)size.get(0));
            if(vkGetPipelineCacheData(device,cache,size,data)!=VK_SUCCESS)return;
            byte[] bytes=new byte[(int)size.get(0)];data.get(bytes);
            Files.createDirectories(path.getParent());
            Path temporary=Files.createTempFile(path.getParent(),"pipeline-",".tmp");
            try {Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
            finally {Files.deleteIfExists(temporary);}
        } catch(Exception unavailable) { /* Cache persistence cannot reject a working device. */ }
        finally {if(data!=null)MemoryUtil.memFree(data);}
    }
    private static byte[] shaderBinary(String source) {
        Path cache = null;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            cache = Path.of("cache", "vss-vulkan", "shaderc-3.4.3-v2-" + hash + ".spv");
            long size = Files.isRegularFile(cache) ? Files.size(cache) : 0;
            if (size >= 20 && size <= 16 * 1024 * 1024 && size % 4 == 0) {
                byte[] bytes = Files.readAllBytes(cache);
                if (ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0x07230203) return bytes;
            }
        } catch (Exception unavailable) { /* Shader caching is optional. */ }
        long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize(), compiled = 0;
        if (compiler == 0 || options == 0) {
            if (compiler != 0) shaderc_compiler_release(compiler);
            if (options != 0) shaderc_compile_options_release(options);
            throw new IllegalStateException("Shader compiler unavailable");
        }
        ByteBuffer encoded = MemoryUtil.memUTF8(source, false);
        try (var stack = MemoryStack.stackPush()) {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
            // The driver still optimizes SPIR-V. Avoid shaderc's separate
            // optimizer: large graphs with mutable corner caches trigger a
            // native crash in its aggressive optimization pass.
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            compiled = shaderc_compile_into_spv(compiler, encoded, shaderc_compute_shader, stack.UTF8("vss-density.comp"), stack.UTF8("main"), options);
            if (compiled == 0 || shaderc_result_get_compilation_status(compiled) != shaderc_compilation_status_success)
                throw new IllegalArgumentException("VSS density shader: " + (compiled == 0 ? "no result" : shaderc_result_get_error_message(compiled)));
            var bytes = shaderc_result_get_bytes(compiled);
            byte[] result = new byte[bytes.remaining()]; bytes.get(result);
            if (cache != null) {
                try {
                    Files.createDirectories(cache.getParent());
                    Path temporary = Files.createTempFile(cache.getParent(), "shader-", ".tmp");
                    try { Files.write(temporary, result); Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING); }
                    finally { Files.deleteIfExists(temporary); }
                } catch (java.io.IOException unavailable) { /* Compilation remains usable. */ }
            }
            return result;
        } finally {
            if (compiled != 0) shaderc_result_release(compiled);
            shaderc_compile_options_release(options); shaderc_compiler_release(compiler);
            MemoryUtil.memFree(encoded);
        }
    }

    private Buffer buffer(VkPhysicalDevice physical, int size) {
        var result = new Buffer(); buffers.add(result);
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "buffer");
            result.handle = handle.get(0);
            var required = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, result.handle, required);
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, properties);
            int type = -1, flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < properties.memoryTypeCount(); i++)
                if ((required.memoryTypeBits() & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
            if (type == -1) throw new IllegalStateException("Vulkan host-coherent memory unavailable");
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(required.size()).memoryTypeIndex(type), null, handle), "buffer memory");
            result.memory = handle.get(0);
            check(vkBindBufferMemory(device, result.handle, result.memory, 0), "bind buffer");
            var address = stack.mallocPointer(1);
            check(vkMapMemory(device, result.memory, 0, size, 0, address), "map buffer");
            result.mapped = MemoryUtil.memByteBuffer(address.get(0), size).order(ByteOrder.LITTLE_ENDIAN);
            return result;
        }
    }

    /** Copy a bounded batch into reusable buffers. Returns a detached result;
     * no mapped GPU allocation escapes the compute worker. */
    int[] dispatch(int[] xyz, int count, boolean heights, int bottom, int top) {
        return dispatch(xyz, count, heights ? 1 : 0, bottom, top);
    }

    /** 0: scalar density, 1: preview height, 2: chunk-volume display boundary. */
    int[] dispatch(int[] xyz, int count, int kind, int bottom, int top) {
        if (count == 0 && xyz.length == 0) return new int[0];
        return await(submit(xyz, count, kind, bottom, top));
    }

    boolean hasFreeSlot() {
        for (var slot : slots) if (slot != null && !slot.inFlight) return true;
        return false;
    }

    /** Queue access and command pools remain confined to one compute worker.
     * Each pending submission owns separate mapped buffers and a fence. */
    Submission submit(int[] xyz, int count, int kind, int bottom, int top) {
        if (count <= 0 || count > MAX_COLUMNS || xyz.length != count * 3 || kind < 0 || kind > 2)
            throw new IllegalArgumentException("Vulkan batch");
        if (device == null) throw new IllegalStateException("Vulkan device closed");
        Submission selected = null;
        for (var slot : slots) if (!slot.inFlight) { selected = slot; break; }
        if (selected == null) throw new IllegalStateException("Vulkan pipeline full");
        var slot = selected;
        for (int i = 0; i < count; i++) for (int axis = 0; axis < 3; axis++)
            slot.input.mapped.putInt(i * 16 + axis * 4, xyz[i * 3 + axis]);
        var commands = slot.commands;
        try (var stack = MemoryStack.stackPush()) {
            check(vkResetFences(device, slot.fence), "reset fence");
            check(vkResetCommandBuffer(commands, 0), "reset commands");
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin commands");
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(slot.descriptorSet), null);
            var params = stack.malloc(16).order(ByteOrder.nativeOrder());
            params.putInt(count).putInt(kind).putInt(bottom).putInt(top).flip();
            vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, params);
            vkCmdDispatch(commands, kind != 0 ? count : (count + 31) / 32, 1, 1);
            var barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            check(vkEndCommandBuffer(commands), "end commands");
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands)), slot.fence), "submit");
            slot.count = count;
            slot.inFlight = true;
        }
        return slot;
    }

    int[] await(Submission slot) {
        if (device == null || slot.owner != this || !slot.inFlight)
            throw new IllegalStateException("Vulkan submission not active");
        check(vkWaitForFences(device, slot.fence, true, 5_000_000_000L), "compute completion");
        int[] result = new int[slot.count];
        for (int i = 0; i < result.length; i++) result[i] = slot.output.mapped.getInt(i * 4);
        slot.inFlight = false;
        return result;
    }

    static final class Submission {
        private final VulkanDensityDevice owner;
        private VkCommandBuffer commands;
        private long descriptorSet, fence;
        private Buffer input, output;
        private int count;
        private boolean inFlight;
        private Submission(VulkanDensityDevice owner) { this.owner = owner; }
    }

    @Override public void close() {
        if (device != null) {
            // A failed/timed-out submission still owns its buffers until idle.
            for (var slot : slots) if (slot != null && slot.inFlight) { vkDeviceWaitIdle(device); break; }
            for (var slot : slots) if (slot != null && slot.fence != 0) vkDestroyFence(device, slot.fence, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
            for (var b : buffers) {
                if (b.mapped != null) vkUnmapMemory(device, b.memory);
                if (b.handle != 0) vkDestroyBuffer(device, b.handle, null);
                if (b.memory != 0) vkFreeMemory(device, b.memory, null);
            }
            vkDestroyDevice(device, null); device = null;
        }
        if (instance != null) { vkDestroyInstance(instance, null); instance = null; }
    }
    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan " + operation + " failed: " + result);
    }
    private static final class Buffer { long handle, memory; ByteBuffer mapped; }
}
