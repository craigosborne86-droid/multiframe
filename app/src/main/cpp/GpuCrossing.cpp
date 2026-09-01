// What it costs to reach the GPU at all.
//
// The plan of record once said Vulkan, and the log records why it was dropped:
// the merge was assumed to be dominated by accumulation, the timer was split,
// and accumulation turned out to be 9% of it. The conclusion written down then
// was that the GPU idea was not wrong but premature -- AHardwareBuffer still
// imports into a Vulkan pipeline without a copy, and that still matters "when
// there is something on the GPU worth the crossing."
//
// The develop is now that something: two passes, per-pixel, embarrassingly
// parallel, and the largest thing left in a capture. So the question is live
// again, and this answers the half of it that can be answered without writing a
// GPU develop: **what does the round trip cost?** 25 MB of merged CFA in,
// 50 MB of RGBA out, and a shader in the middle chosen to be too cheap to
// matter.
//
// If that round trip costs more than the CPU develop, no kernel wins it back
// and the question is closed. If it is cheap, the next question -- whether a
// GPU develop beats a NEON one -- becomes worth the work of asking. This
// deliberately does not answer that second question, and a figure from here
// must never be quoted as though it did.

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>

// AHardwareBuffer import lives behind this guard, and it is the whole point of
// the third route -- without it vulkan.h omits every ANDROID-suffixed symbol.
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstring>
#include <functional>
#include <ctime>
#include <string>
#include <vector>

#define LOG_TAG "MultiframeGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

const uint32_t kShader[] =
#include "crossing_comp.inc"
    ;

inline int64_t nowMicros() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
}

/**
 * How the frame gets to the GPU and back, which is the whole question.
 *
 * These are not implementations of the same thing to pick between on style.
 * They are three different answers to "where does the memory live", and on a
 * phone -- where the CPU and GPU share one pool -- which of them is available
 * decides whether a copy happens at all.
 */
enum Route {
    // Host-visible staging buffers, copied to and from device-local ones by the
    // GPU. What a discrete card would need, and the conservative shape: it
    // costs one extra pass over both buffers in each direction.
    kStaging = 0,
    // One allocation that is both DEVICE_LOCAL and HOST_VISIBLE, which is what
    // unified memory offers and what a phone should be able to give. The
    // CPU writes it directly and the shader reads it in place; the only copy
    // left is the app's own data into that allocation.
    kShared = 1,
    // The same, from an AHardwareBuffer imported into Vulkan. This is the claim
    // in RingBuffer.h -- that a ring of hardware buffers would reach the GPU
    // with no copy at all -- and the only route here that could remove the last
    // memcpy, since the ring would own the memory the shader reads.
    kImported = 2,
    // Shared, but in HOST_CACHED memory with the flush and the invalidate paid
    // explicitly.
    //
    // This route exists because without it the comparison is confounded. The
    // shared route above takes the first memory that is coherent, which on this
    // driver is uncached -- and reading 50 MB back through an uncached mapping
    // is slow for reasons that have nothing to do with Vulkan. An imported
    // AHardwareBuffer asks for CPU_READ_OFTEN and gets cached memory, so
    // comparing the two measures the cache policy at least as much as it
    // measures the import. This one holds the policy fixed.
    kCached = 3,
};

const char* routeName(int route) {
    switch (route) {
        case kStaging: return "staging";
        case kShared: return "shared";
        case kImported: return "imported";
        case kCached: return "cached";
        default: return "?";
    }
}

/** Vulkan, or the reason there isn't any. Built once and kept. */
struct Gpu {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkPhysicalDeviceMemoryProperties memory{};
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    bool hardwareBuffers = false;
    std::string name;
    std::string failure;
    int64_t setupMicros = 0;

    bool ok() const { return device != VK_NULL_HANDLE; }
};

PFN_vkGetAndroidHardwareBufferPropertiesANDROID gAhbProperties = nullptr;

/**
 * A memory type satisfying [required], preferring one that also has [wanted].
 *
 * Returns -1 rather than falling back silently. A route that cannot get the
 * memory it is named after has to say so: reporting the staging figure under
 * the shared route's name would be the most misleading thing this file could
 * do.
 */
int memoryTypeFor(const Gpu& gpu, uint32_t bits, VkMemoryPropertyFlags required,
                  VkMemoryPropertyFlags wanted) {
    int fallback = -1;
    for (uint32_t i = 0; i < gpu.memory.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) == 0) continue;
        const VkMemoryPropertyFlags flags = gpu.memory.memoryTypes[i].propertyFlags;
        if ((flags & required) != required) continue;
        if ((flags & wanted) == wanted) return static_cast<int>(i);
        if (fallback < 0) fallback = static_cast<int>(i);
    }
    return fallback;
}

void destroyGpu(Gpu& gpu) {
    if (gpu.device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(gpu.device);
        if (gpu.pipeline) vkDestroyPipeline(gpu.device, gpu.pipeline, nullptr);
        if (gpu.pipelineLayout) vkDestroyPipelineLayout(gpu.device, gpu.pipelineLayout, nullptr);
        if (gpu.setLayout) vkDestroyDescriptorSetLayout(gpu.device, gpu.setLayout, nullptr);
        if (gpu.descriptorPool) vkDestroyDescriptorPool(gpu.device, gpu.descriptorPool, nullptr);
        if (gpu.commandPool) vkDestroyCommandPool(gpu.device, gpu.commandPool, nullptr);
        vkDestroyDevice(gpu.device, nullptr);
    }
    if (gpu.instance != VK_NULL_HANDLE) vkDestroyInstance(gpu.instance, nullptr);
    gpu = Gpu{};
}

/**
 * Everything that has to exist before a single byte can cross, timed.
 *
 * Reported separately and on purpose. If reaching the GPU costs a tenth of a
 * second before any work happens, that is not a reason to abandon the idea --
 * it is a reason to build the context once and keep it, which is a different
 * design from building it per capture. The number decides which.
 */
bool createGpu(Gpu& gpu) {
    const int64_t start = nowMicros();

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "multiframe";
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &app;
    if (vkCreateInstance(&instanceInfo, nullptr, &gpu.instance) != VK_SUCCESS) {
        gpu.failure = "no Vulkan instance";
        return false;
    }

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(gpu.instance, &count, nullptr);
    if (count == 0) {
        gpu.failure = "no Vulkan physical device";
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(gpu.instance, &count, devices.data());
    gpu.physical = devices[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(gpu.physical, &props);
    gpu.name = props.deviceName;
    vkGetPhysicalDeviceMemoryProperties(gpu.physical, &gpu.memory);

    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu.physical, &families, nullptr);
    std::vector<VkQueueFamilyProperties> queues(families);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu.physical, &families, queues.data());
    bool found = false;
    for (uint32_t i = 0; i < families; ++i) {
        if (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            gpu.queueFamily = i;
            found = true;
            break;
        }
    }
    if (!found) {
        gpu.failure = "no compute queue";
        return false;
    }

    uint32_t extensionCount = 0;
    vkEnumerateDeviceExtensionProperties(gpu.physical, nullptr, &extensionCount, nullptr);
    std::vector<VkExtensionProperties> extensions(extensionCount);
    vkEnumerateDeviceExtensionProperties(gpu.physical, nullptr, &extensionCount,
                                         extensions.data());
    std::vector<const char*> enabled;
    for (const VkExtensionProperties& e : extensions) {
        if (std::strcmp(e.extensionName,
                        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) == 0) {
            gpu.hardwareBuffers = true;
        }
    }
    if (gpu.hardwareBuffers) {
        enabled.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        enabled.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = gpu.queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    deviceInfo.ppEnabledExtensionNames = enabled.empty() ? nullptr : enabled.data();
    if (vkCreateDevice(gpu.physical, &deviceInfo, nullptr, &gpu.device) != VK_SUCCESS) {
        gpu.failure = "vkCreateDevice failed";
        return false;
    }
    vkGetDeviceQueue(gpu.device, gpu.queueFamily, 0, &gpu.queue);

    if (gpu.hardwareBuffers) {
        gAhbProperties =
            reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                vkGetDeviceProcAddr(gpu.device,
                                    "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (gAhbProperties == nullptr) gpu.hardwareBuffers = false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;
    vkCreateDescriptorSetLayout(gpu.device, &layoutInfo, nullptr, &gpu.setLayout);

    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(uint32_t);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &gpu.setLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &push;
    vkCreatePipelineLayout(gpu.device, &pipelineLayoutInfo, nullptr, &gpu.pipelineLayout);

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = sizeof(kShader);
    shaderInfo.pCode = kShader;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(gpu.device, &shaderInfo, nullptr, &module) != VK_SUCCESS) {
        gpu.failure = "shader module rejected";
        return false;
    }

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = module;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = gpu.pipelineLayout;
    const VkResult built = vkCreateComputePipelines(gpu.device, VK_NULL_HANDLE, 1,
                                                    &pipelineInfo, nullptr, &gpu.pipeline);
    vkDestroyShaderModule(gpu.device, module, nullptr);
    if (built != VK_SUCCESS) {
        gpu.failure = "compute pipeline rejected";
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 8;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 4;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    vkCreateDescriptorPool(gpu.device, &poolInfo, nullptr, &gpu.descriptorPool);

    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = gpu.queueFamily;
    vkCreateCommandPool(gpu.device, &commandPoolInfo, nullptr, &gpu.commandPool);

    gpu.setupMicros = nowMicros() - start;
    return true;
}

/** A buffer and the memory under it, however that memory was come by. */
struct Block {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    AHardwareBuffer* hardware = nullptr;
    VkDeviceSize size = 0;
    // Non-coherent memory is the fast kind to read and the kind that has to be
    // flushed and invalidated by hand. Both costs belong to the route.
    bool coherent = true;
};

void destroyBlock(const Gpu& gpu, Block& block) {
    if (block.mapped && block.hardware == nullptr) vkUnmapMemory(gpu.device, block.memory);
    if (block.buffer) vkDestroyBuffer(gpu.device, block.buffer, nullptr);
    if (block.memory) vkFreeMemory(gpu.device, block.memory, nullptr);
    if (block.hardware) {
        if (block.mapped) AHardwareBuffer_unlock(block.hardware, nullptr);
        AHardwareBuffer_release(block.hardware);
    }
    block = Block{};
}

bool allocateBlock(const Gpu& gpu, Block& block, VkDeviceSize size,
                   VkMemoryPropertyFlags required, VkMemoryPropertyFlags wanted,
                   bool mapIt) {
    block.size = size;
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                 VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(gpu.device, &info, nullptr, &block.buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements needs{};
    vkGetBufferMemoryRequirements(gpu.device, block.buffer, &needs);
    const int type = memoryTypeFor(gpu, needs.memoryTypeBits, required, wanted);
    if (type < 0) return false;
    // A route must get the memory it is named after, or say nothing at all.
    const VkMemoryPropertyFlags got = gpu.memory.memoryTypes[type].propertyFlags;
    if ((got & wanted) != wanted && wanted != 0) return false;

    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.allocationSize = needs.size;
    allocation.memoryTypeIndex = static_cast<uint32_t>(type);
    if (vkAllocateMemory(gpu.device, &allocation, nullptr, &block.memory) != VK_SUCCESS) {
        return false;
    }
    if (vkBindBufferMemory(gpu.device, block.buffer, block.memory, 0) != VK_SUCCESS) {
        return false;
    }
    block.coherent = (got & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    if (mapIt && vkMapMemory(gpu.device, block.memory, 0, size, 0, &block.mapped) != VK_SUCCESS) {
        return false;
    }
    return true;
}

/** Makes a CPU write visible to the GPU, on memory that is not coherent. */
void flushBlock(const Gpu& gpu, const Block& block) {
    if (block.coherent || block.memory == VK_NULL_HANDLE) return;
    VkMappedMemoryRange range{};
    range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
    range.memory = block.memory;
    range.size = VK_WHOLE_SIZE;
    vkFlushMappedMemoryRanges(gpu.device, 1, &range);
}

/** And the other direction, before the CPU reads what the GPU wrote. */
void invalidateBlock(const Gpu& gpu, const Block& block) {
    if (block.coherent || block.memory == VK_NULL_HANDLE) return;
    VkMappedMemoryRange range{};
    range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
    range.memory = block.memory;
    range.size = VK_WHOLE_SIZE;
    vkInvalidateMappedMemoryRanges(gpu.device, 1, &range);
}

/**
 * A buffer whose memory is an AHardwareBuffer the app allocated.
 *
 * This is the route RingBuffer.h describes and the only one where the shader
 * reads memory the app could have owned all along -- a ring of these would put
 * camera frames in front of the GPU with no copy anywhere.
 */
bool importBlock(const Gpu& gpu, Block& block, VkDeviceSize size) {
    if (!gpu.hardwareBuffers) return false;
    block.size = size;

    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(size);
    desc.height = 1;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_BLOB;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
    if (AHardwareBuffer_allocate(&desc, &block.hardware) != 0) return false;

    VkAndroidHardwareBufferPropertiesANDROID properties{};
    properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (gAhbProperties(gpu.device, block.hardware, &properties) != VK_SUCCESS) return false;

    VkExternalMemoryBufferCreateInfo external{};
    external.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.pNext = &external;
    info.size = size;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                 VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                 VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(gpu.device, &info, nullptr, &block.buffer) != VK_SUCCESS) return false;

    const int type = memoryTypeFor(gpu, properties.memoryTypeBits, 0, 0);
    if (type < 0) return false;

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = block.hardware;

    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.buffer = block.buffer;
    dedicated.pNext = &importInfo;

    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &dedicated;
    allocation.allocationSize = properties.allocationSize;
    allocation.memoryTypeIndex = static_cast<uint32_t>(type);
    if (vkAllocateMemory(gpu.device, &allocation, nullptr, &block.memory) != VK_SUCCESS) {
        return false;
    }
    if (vkBindBufferMemory(gpu.device, block.buffer, block.memory, 0) != VK_SUCCESS) {
        return false;
    }
    // Locked for the life of the benchmark: the cost being measured is the
    // crossing, not a lock/unlock round trip that a real ring would take once.
    if (AHardwareBuffer_lock(block.hardware,
                             AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                             -1, nullptr, &block.mapped) != 0) {
        return false;
    }
    return true;
}

}  // namespace

namespace {

/**
 * One route, set up and ready to be timed.
 *
 * `shaderSrc` and `shaderDst` are what the dispatch binds; `hostSrc` and
 * `hostDst` are what the CPU writes and reads. On the staging route they are
 * different buffers and the difference is the point. On the other two they are
 * the same buffer, which is what makes those routes worth measuring.
 */
struct Run {
    Block hostSrc, hostDst, deviceSrc, deviceDst;
    Block* shaderSrc = nullptr;
    Block* shaderDst = nullptr;
    bool staged = false;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
};

void destroyRun(const Gpu& gpu, Run& run) {
    if (run.fence) vkDestroyFence(gpu.device, run.fence, nullptr);
    if (run.cmd) vkFreeCommandBuffers(gpu.device, gpu.commandPool, 1, &run.cmd);
    destroyBlock(gpu, run.hostSrc);
    destroyBlock(gpu, run.hostDst);
    destroyBlock(gpu, run.deviceSrc);
    destroyBlock(gpu, run.deviceDst);
    run = Run{};
}

constexpr VkMemoryPropertyFlags kHostFlags =
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
constexpr VkMemoryPropertyFlags kSharedFlags =
    kHostFlags | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
constexpr VkMemoryPropertyFlags kCachedFlags =
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT |
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

bool prepareRun(const Gpu& gpu, int route, VkDeviceSize srcBytes, VkDeviceSize dstBytes,
                Run& run) {
    switch (route) {
        case kStaging:
            run.staged = true;
            if (!allocateBlock(gpu, run.hostSrc, srcBytes, kHostFlags, kHostFlags, true)) return false;
            if (!allocateBlock(gpu, run.hostDst, dstBytes, kHostFlags, kHostFlags, true)) return false;
            if (!allocateBlock(gpu, run.deviceSrc, srcBytes,
                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false)) return false;
            if (!allocateBlock(gpu, run.deviceDst, dstBytes,
                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false)) return false;
            run.shaderSrc = &run.deviceSrc;
            run.shaderDst = &run.deviceDst;
            break;
        case kShared:
            if (!allocateBlock(gpu, run.hostSrc, srcBytes, kHostFlags, kSharedFlags, true)) return false;
            if (!allocateBlock(gpu, run.hostDst, dstBytes, kHostFlags, kSharedFlags, true)) return false;
            run.shaderSrc = &run.hostSrc;
            run.shaderDst = &run.hostDst;
            break;
        case kImported:
            if (!importBlock(gpu, run.hostSrc, srcBytes)) return false;
            if (!importBlock(gpu, run.hostDst, dstBytes)) return false;
            run.shaderSrc = &run.hostSrc;
            run.shaderDst = &run.hostDst;
            break;
        case kCached:
            if (!allocateBlock(gpu, run.hostSrc, srcBytes,
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, kCachedFlags, true)) {
                return false;
            }
            if (!allocateBlock(gpu, run.hostDst, dstBytes,
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, kCachedFlags, true)) {
                return false;
            }
            run.shaderSrc = &run.hostSrc;
            run.shaderDst = &run.hostDst;
            break;
        default:
            return false;
    }

    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = gpu.descriptorPool;
    setInfo.descriptorSetCount = 1;
    setInfo.pSetLayouts = &gpu.setLayout;
    if (vkAllocateDescriptorSets(gpu.device, &setInfo, &run.set) != VK_SUCCESS) return false;

    VkDescriptorBufferInfo buffers[2]{};
    buffers[0].buffer = run.shaderSrc->buffer;
    buffers[0].range = VK_WHOLE_SIZE;
    buffers[1].buffer = run.shaderDst->buffer;
    buffers[1].range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = run.set;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &buffers[i];
    }
    vkUpdateDescriptorSets(gpu.device, 2, writes, 0, nullptr);

    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = gpu.commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(gpu.device, &cmdInfo, &run.cmd) != VK_SUCCESS) return false;

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(gpu.device, &fenceInfo, nullptr, &run.fence) != VK_SUCCESS) return false;
    return true;
}

/** Records, submits and waits. Every phase below is timed around one of these. */
bool submit(const Gpu& gpu, Run& run, const std::function<void(VkCommandBuffer)>& record) {
    vkResetFences(gpu.device, 1, &run.fence);
    vkResetCommandBuffer(run.cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(run.cmd, &begin);
    record(run.cmd);
    vkEndCommandBuffer(run.cmd);

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &run.cmd;
    if (vkQueueSubmit(gpu.queue, 1, &submitInfo, run.fence) != VK_SUCCESS) return false;
    return vkWaitForFences(gpu.device, 1, &run.fence, VK_TRUE, UINT64_MAX) == VK_SUCCESS;
}

void barrier(VkCommandBuffer cmd, VkAccessFlags from, VkAccessFlags to,
             VkPipelineStageFlags stageFrom, VkPipelineStageFlags stageTo) {
    VkMemoryBarrier memory{};
    memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memory.srcAccessMask = from;
    memory.dstAccessMask = to;
    vkCmdPipelineBarrier(cmd, stageFrom, stageTo, 0, 1, &memory, 0, nullptr, 0, nullptr);
}

struct Phases {
    int64_t upload = 0;
    int64_t dispatch = 0;
    int64_t download = 0;
};

/**
 * One crossing: the frame in, the shader, the result out.
 *
 * The three phases are timed separately because they answer different
 * questions. Upload and download are the crossing and are what this file exists
 * to price. The dispatch is the floor -- what an empty pass over twelve and a
 * half million pixels costs once the data is already there -- and any real
 * kernel is added to it, never subtracted.
 */
bool crossing(const Gpu& gpu, Run& run, const uint16_t* source, uint8_t* sink,
              uint32_t pairs, size_t srcBytes, size_t dstBytes, Phases& out) {
    const int64_t t0 = nowMicros();
    std::memcpy(run.hostSrc.mapped, source, srcBytes);
    flushBlock(gpu, run.hostSrc);
    if (run.staged) {
        VkBufferCopy copy{};
        copy.size = srcBytes;
        if (!submit(gpu, run, [&](VkCommandBuffer cmd) {
                vkCmdCopyBuffer(cmd, run.hostSrc.buffer, run.deviceSrc.buffer, 1, &copy);
            })) {
            return false;
        }
    }
    const int64_t t1 = nowMicros();

    if (!submit(gpu, run, [&](VkCommandBuffer cmd) {
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipelineLayout,
                                    0, 1, &run.set, 0, nullptr);
            vkCmdPushConstants(cmd, gpu.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                               sizeof(uint32_t), &pairs);
            vkCmdDispatch(cmd, (pairs + 63u) / 64u, 1, 1);
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_HOST_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT);
        })) {
        return false;
    }
    const int64_t t2 = nowMicros();

    if (run.staged) {
        VkBufferCopy copy{};
        copy.size = dstBytes;
        if (!submit(gpu, run, [&](VkCommandBuffer cmd) {
                vkCmdCopyBuffer(cmd, run.deviceDst.buffer, run.hostDst.buffer, 1, &copy);
            })) {
            return false;
        }
    }
    invalidateBlock(gpu, run.hostDst);
    std::memcpy(sink, run.hostDst.mapped, dstBytes);
    const int64_t t3 = nowMicros();

    out.upload = t1 - t0;
    out.dispatch = t2 - t1;
    out.download = t3 - t2;
    return true;
}

}  // namespace

extern "C" {

/**
 * What this phone's GPU is, and which routes to it exist.
 *
 * Read this first on an unfamiliar device. Every route below is optional: a
 * driver that offers no memory which is both host-visible and device-local
 * cannot do the shared route at all, and one without
 * `VK_ANDROID_external_memory_android_hardware_buffer` cannot do the imported
 * one. Reporting that plainly is more useful than a benchmark that quietly
 * measured the staging route three times.
 */
JNIEXPORT jstring JNICALL
Java_dev_multiframe_camera_pipeline_GpuCrossing_nProbe(JNIEnv* env, jobject) {
    Gpu gpu;
    if (!createGpu(gpu)) {
        const std::string reason = "no GPU route: " + gpu.failure;
        destroyGpu(gpu);
        return env->NewStringUTF(reason.c_str());
    }

    std::string out = gpu.name + ", Vulkan 1.1, setup " +
                      std::to_string(gpu.setupMicros / 1000) + "ms";
    out += "; memory types:";
    for (uint32_t i = 0; i < gpu.memory.memoryTypeCount; ++i) {
        const VkMemoryPropertyFlags f = gpu.memory.memoryTypes[i].propertyFlags;
        out += " [";
        if (f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) out += "device";
        if (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) out += "+visible";
        if (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) out += "+coherent";
        if (f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) out += "+cached";
        out += "]";
    }

    // Tried rather than inferred from the flags: a route counts as available
    // when it has actually been built.
    const VkDeviceSize probeBytes = 1 << 20;
    for (int route = kStaging; route <= kCached; ++route) {
        Run run;
        const bool ok = prepareRun(gpu, route, probeBytes, probeBytes * 2, run);
        destroyRun(gpu, run);
        out += std::string("; ") + routeName(route) + (ok ? " yes" : " no");
    }
    destroyGpu(gpu);
    return env->NewStringUTF(out.c_str());
}

/**
 * Two routes to the GPU, timed against each other in one process.
 *
 * The same arrangement as every other comparison in this project: both
 * candidates live in the binary, the order alternates within a round, and the
 * comparison is paired rather than pooled. Passing the same route twice is the
 * A/A, which is not a formality -- one slot allocates before the other and a
 * harness that can separate a route from itself cannot be believed about two.
 *
 * Result: `[setup, mismatchesA, mismatchesB]`, then per round `upA, disA, dnA,
 * upB, disB, dnB` in microseconds. Null if either route could not be built;
 * `nProbe` says which and why.
 *
 * The mismatch counts matter more than they look. A driver that silently
 * dropped the dispatch would produce the fastest figures in this file, and the
 * only thing standing between that and a wrong conclusion is checking that the
 * bytes came back right.
 */
JNIEXPORT jlongArray JNICALL
Java_dev_multiframe_camera_pipeline_GpuCrossing_nBench(
        JNIEnv* env, jobject, jint width, jint height, jint routeA, jint routeB,
        jint roundCount) {
    if (width < 2 || height < 1 || roundCount <= 0) return nullptr;
    const size_t pixels = static_cast<size_t>(width) * height;
    if ((pixels & 1u) != 0) return nullptr;
    const uint32_t pairs = static_cast<uint32_t>(pixels / 2);
    const size_t srcBytes = pixels * 2;
    const size_t dstBytes = pixels * 4;

    Gpu gpu;
    if (!createGpu(gpu)) {
        LOGW("no GPU route: %s", gpu.failure.c_str());
        destroyGpu(gpu);
        return nullptr;
    }

    Run a, b;
    if (!prepareRun(gpu, routeA, srcBytes, dstBytes, a) ||
        !prepareRun(gpu, routeB, srcBytes, dstBytes, b)) {
        LOGW("could not build %s against %s", routeName(routeA), routeName(routeB));
        destroyRun(gpu, a);
        destroyRun(gpu, b);
        destroyGpu(gpu);
        return nullptr;
    }

    // A merged frame's shape rather than a constant: a ramp with detail on it,
    // so a driver cannot satisfy the check by writing one value everywhere.
    std::vector<uint16_t> source(pixels);
    for (size_t i = 0; i < pixels; ++i) {
        source[i] = static_cast<uint16_t>((i * 2654435761u) & 0x3ffu);
    }
    std::vector<uint8_t> sinkA(dstBytes), sinkB(dstBytes);

    auto mismatches = [&](const std::vector<uint8_t>& sink) {
        long wrong = 0;
        for (size_t i = 0; i < pixels; ++i) {
            const uint8_t grey = static_cast<uint8_t>(source[i] >> 2);
            const uint8_t* p = sink.data() + i * 4;
            if (p[0] != grey || p[1] != grey || p[2] != grey || p[3] != 0xff) ++wrong;
        }
        return wrong;
    };

    Phases warm{};
    if (!crossing(gpu, a, source.data(), sinkA.data(), pairs, srcBytes, dstBytes, warm) ||
        !crossing(gpu, b, source.data(), sinkB.data(), pairs, srcBytes, dstBytes, warm)) {
        LOGW("the first crossing failed");
        destroyRun(gpu, a);
        destroyRun(gpu, b);
        destroyGpu(gpu);
        return nullptr;
    }

    std::vector<jlong> out(3 + static_cast<size_t>(roundCount) * 6, 0);
    out[0] = gpu.setupMicros;

    bool ok = true;
    for (int r = 0; r < roundCount && ok; ++r) {
        Phases pa{}, pb{};
        auto slotA = [&]() {
            ok = ok && crossing(gpu, a, source.data(), sinkA.data(), pairs, srcBytes, dstBytes, pa);
        };
        auto slotB = [&]() {
            ok = ok && crossing(gpu, b, source.data(), sinkB.data(), pairs, srcBytes, dstBytes, pb);
        };
        if ((r & 1) == 0) { slotA(); slotB(); } else { slotB(); slotA(); }
        const size_t base = 3 + static_cast<size_t>(r) * 6;
        out[base + 0] = pa.upload;
        out[base + 1] = pa.dispatch;
        out[base + 2] = pa.download;
        out[base + 3] = pb.upload;
        out[base + 4] = pb.dispatch;
        out[base + 5] = pb.download;
    }

    out[1] = mismatches(sinkA);
    out[2] = mismatches(sinkB);

    destroyRun(gpu, a);
    destroyRun(gpu, b);
    destroyGpu(gpu);
    if (!ok) return nullptr;

    jlongArray result = env->NewLongArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

}  // extern "C"
