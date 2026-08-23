#include <jni.h>

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <media/NdkImage.h>

#include <dlfcn.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <limits>
#include <locale>
#include <new>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr int kPayloadSchemaVersion = 1;
constexpr int kMaximumAdvertisedIds = 512;
constexpr int kMaximumDeepCandidates = 128;
constexpr std::size_t kMaximumCameraIdBytes = 512;
constexpr std::uint32_t kMaximumCapabilityValues = 256;
constexpr std::uint32_t kMaximumFocalLengths = 32;
constexpr std::uint32_t kMaximumStreamConfigurationWords = 16384;
constexpr std::uint32_t kMaximumPhysicalIdBytes = 4096;
constexpr std::size_t kMaximumSizesPerStreamClass = 8;
constexpr std::int32_t kImageFormatRaw14 = 44;

using Clock = std::chrono::steady_clock;

class CameraNdkApi {
public:
    using CreateManager = ACameraManager* (*)();
    using DeleteManager = void (*)(ACameraManager*);
    using GetCameraIdList = camera_status_t (*)(ACameraManager*, ACameraIdList**);
    using DeleteCameraIdList = void (*)(ACameraIdList*);
    using GetCameraCharacteristics = camera_status_t (*)(
        ACameraManager*,
        const char*,
        ACameraMetadata**);
    using GetConstEntry = camera_status_t (*)(
        const ACameraMetadata*,
        std::uint32_t,
        ACameraMetadata_const_entry*);
    using FreeMetadata = void (*)(ACameraMetadata*);

    static const CameraNdkApi& instance() {
        static const CameraNdkApi api;
        return api;
    }

    CameraNdkApi(const CameraNdkApi&) = delete;
    CameraNdkApi& operator=(const CameraNdkApi&) = delete;

    bool available() const noexcept { return available_; }

    CreateManager createManager = nullptr;
    DeleteManager deleteManager = nullptr;
    GetCameraIdList getCameraIdList = nullptr;
    DeleteCameraIdList deleteCameraIdList = nullptr;
    GetCameraCharacteristics getCameraCharacteristics = nullptr;
    GetConstEntry getConstEntry = nullptr;
    FreeMetadata freeMetadata = nullptr;

private:
    CameraNdkApi() {
        handle_ = dlopen("libcamera2ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (handle_ == nullptr) return;
        available_ =
            load(createManager, "ACameraManager_create") &&
            load(deleteManager, "ACameraManager_delete") &&
            load(getCameraIdList, "ACameraManager_getCameraIdList") &&
            load(deleteCameraIdList, "ACameraManager_deleteCameraIdList") &&
            load(getCameraCharacteristics, "ACameraManager_getCameraCharacteristics") &&
            load(getConstEntry, "ACameraMetadata_getConstEntry") &&
            load(freeMetadata, "ACameraMetadata_free");
        if (!available_) {
            dlclose(handle_);
            handle_ = nullptr;
        }
    }

    ~CameraNdkApi() {
        if (handle_ != nullptr) dlclose(handle_);
    }

    template <typename Function>
    bool load(Function& destination, const char* symbolName) noexcept {
        void* symbol = dlsym(handle_, symbolName);
        static_assert(sizeof(destination) == sizeof(symbol));
        std::memcpy(&destination, &symbol, sizeof(destination));
        return destination != nullptr;
    }

    void* handle_ = nullptr;
    bool available_ = false;
};

class ManagerOwner {
public:
    ManagerOwner(const CameraNdkApi& api, ACameraManager* value) : api_(api), value_(value) {}
    ~ManagerOwner() {
        if (value_ != nullptr) api_.deleteManager(value_);
    }
    ACameraManager* get() const noexcept { return value_; }

private:
    const CameraNdkApi& api_;
    ACameraManager* value_;
};

class CameraIdListOwner {
public:
    CameraIdListOwner(const CameraNdkApi& api, ACameraIdList* value) : api_(api), value_(value) {}
    ~CameraIdListOwner() {
        if (value_ != nullptr) api_.deleteCameraIdList(value_);
    }

private:
    const CameraNdkApi& api_;
    ACameraIdList* value_;
};

class MetadataOwner {
public:
    MetadataOwner(const CameraNdkApi& api, ACameraMetadata* value) : api_(api), value_(value) {}
    ~MetadataOwner() {
        if (value_ != nullptr) api_.freeMetadata(value_);
    }
    const ACameraMetadata* get() const noexcept { return value_; }

private:
    const CameraNdkApi& api_;
    ACameraMetadata* value_;
};

struct SensorRect {
    std::int32_t left;
    std::int32_t top;
    std::int32_t right;
    std::int32_t bottom;
};

struct StreamSize {
    std::int32_t width;
    std::int32_t height;
};

struct CameraRecord {
    std::string id;
    std::optional<std::int32_t> facing;
    std::vector<double> focalLengthsMm;
    std::optional<double> sensorWidthMm;
    std::optional<double> sensorHeightMm;
    std::optional<SensorRect> activeArray;
    std::optional<std::int32_t> pixelWidth;
    std::optional<std::int32_t> pixelHeight;
    std::optional<std::int32_t> sensorOrientationDegrees;
    std::optional<std::int32_t> hardwareLevel;
    std::optional<bool> rawCapabilityAdvertised;
    std::vector<std::string> rawFormats;
    std::vector<StreamSize> rawSizes;
    bool privatePreviewStreamDeclared = false;
    std::vector<StreamSize> privatePreviewSizes;
    bool yuvPreviewStreamDeclared = false;
    std::vector<StreamSize> yuvPreviewSizes;
    std::optional<std::vector<std::string>> reportedCapabilities;
    std::optional<std::int32_t> colorFilterArrangement;
    std::vector<std::string> physicalCameraIds;
};

struct Failure {
    std::optional<std::string> cameraId;
    std::string stage;
    std::string reason;
    std::optional<std::int32_t> statusCode;
};

struct ScanResult {
    explicit ScanResult(std::string value) : source(std::move(value)) {}

    std::string source;
    std::int64_t durationMs = 0;
    int advertisedCount = 0;
    int requestedCount = 0;
    int attemptedCount = 0;
    int skippedCount = 0;
    std::vector<std::string> advertisedIds;
    std::vector<std::string> requestedIds;
    std::vector<std::string> attemptedIds;
    std::vector<CameraRecord> cameras;
    std::vector<Failure> failures;
};

bool isControlCodePoint(std::uint32_t value) noexcept {
    return value <= 0x1FU || (value >= 0x7FU && value <= 0x9FU);
}

bool isSafeUtf8Id(const std::string& value) noexcept {
    if (value.empty() || value.size() > kMaximumCameraIdBytes) return false;
    std::size_t index = 0;
    while (index < value.size()) {
        const auto first = static_cast<std::uint8_t>(value[index]);
        std::uint32_t codePoint = 0;
        std::size_t length = 0;
        if (first <= 0x7FU) {
            codePoint = first;
            length = 1;
        } else if (first >= 0xC2U && first <= 0xDFU) {
            codePoint = first & 0x1FU;
            length = 2;
        } else if (first >= 0xE0U && first <= 0xEFU) {
            codePoint = first & 0x0FU;
            length = 3;
        } else if (first >= 0xF0U && first <= 0xF4U) {
            codePoint = first & 0x07U;
            length = 4;
        } else {
            return false;
        }
        if (index + length > value.size()) return false;
        for (std::size_t offset = 1; offset < length; ++offset) {
            const auto continuation = static_cast<std::uint8_t>(value[index + offset]);
            if ((continuation & 0xC0U) != 0x80U) return false;
            codePoint = (codePoint << 6U) | (continuation & 0x3FU);
        }
        if ((length == 2 && codePoint < 0x80U) ||
            (length == 3 && codePoint < 0x800U) ||
            (length == 4 && codePoint < 0x10000U) ||
            codePoint > 0x10FFFFU ||
            (codePoint >= 0xD800U && codePoint <= 0xDFFFU) ||
            isControlCodePoint(codePoint)) {
            return false;
        }
        index += length;
    }
    return true;
}

void appendUtf8(std::string& destination, std::uint32_t codePoint) {
    if (codePoint <= 0x7FU) {
        destination.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7FFU) {
        destination.push_back(static_cast<char>(0xC0U | (codePoint >> 6U)));
        destination.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
    } else if (codePoint <= 0xFFFFU) {
        destination.push_back(static_cast<char>(0xE0U | (codePoint >> 12U)));
        destination.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
        destination.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
    } else {
        destination.push_back(static_cast<char>(0xF0U | (codePoint >> 18U)));
        destination.push_back(static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU)));
        destination.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
        destination.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
    }
}

std::optional<std::string> javaStringToUtf8(JNIEnv* environment, jstring value) {
    if (value == nullptr) return std::nullopt;
    const jsize length = environment->GetStringLength(value);
    const jchar* characters = environment->GetStringChars(value, nullptr);
    if (characters == nullptr) {
        if (environment->ExceptionCheck()) environment->ExceptionClear();
        return std::nullopt;
    }
    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    bool valid = true;
    for (jsize index = 0; index < length && valid; ++index) {
        std::uint32_t codePoint = characters[index];
        if (codePoint >= 0xD800U && codePoint <= 0xDBFFU) {
            if (index + 1 >= length) {
                valid = false;
                break;
            }
            const std::uint32_t low = characters[++index];
            if (low < 0xDC00U || low > 0xDFFFU) {
                valid = false;
                break;
            }
            codePoint = 0x10000U + ((codePoint - 0xD800U) << 10U) + (low - 0xDC00U);
        } else if (codePoint >= 0xDC00U && codePoint <= 0xDFFFU) {
            valid = false;
            break;
        }
        if (isControlCodePoint(codePoint)) {
            valid = false;
            break;
        }
        appendUtf8(result, codePoint);
        if (result.size() > kMaximumCameraIdBytes) valid = false;
    }
    environment->ReleaseStringChars(value, characters);
    return valid && isSafeUtf8Id(result) ? std::optional<std::string>(std::move(result)) : std::nullopt;
}

void appendJsonString(std::ostringstream& output, const std::string& value) {
    output << '"';
    for (const auto character : value) {
        const auto byte = static_cast<std::uint8_t>(character);
        switch (byte) {
            case '"': output << "\\\""; break;
            case '\\': output << "\\\\"; break;
            case '\b': output << "\\b"; break;
            case '\f': output << "\\f"; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default:
                if (byte < 0x20U) {
                    output << "\\u00" << std::hex << std::setw(2) << std::setfill('0')
                           << static_cast<int>(byte) << std::dec << std::setfill(' ');
                } else {
                    output << character;
                }
        }
    }
    output << '"';
}

template <typename Value>
void appendOptionalNumber(std::ostringstream& output, const std::optional<Value>& value) {
    if (value.has_value()) output << *value; else output << "null";
}

void appendOptionalBoolean(std::ostringstream& output, const std::optional<bool>& value) {
    if (!value.has_value()) {
        output << "null";
    } else {
        output << (*value ? "true" : "false");
    }
}

void appendStringArray(std::ostringstream& output, const std::vector<std::string>& values) {
    output << '[';
    for (std::size_t index = 0; index < values.size(); ++index) {
        if (index != 0U) output << ',';
        appendJsonString(output, values[index]);
    }
    output << ']';
}

enum class EntryState {
    MISSING,
    PRESENT,
    MALFORMED,
};

EntryState readEntry(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    std::uint32_t tag,
    std::uint8_t expectedType,
    ACameraMetadata_const_entry* entry) {
    if (metadata == nullptr || entry == nullptr) return EntryState::MALFORMED;
    *entry = {};
    const camera_status_t status = api.getConstEntry(metadata, tag, entry);
    if (status == ACAMERA_ERROR_METADATA_NOT_FOUND) return EntryState::MISSING;
    if (status != ACAMERA_OK || entry->tag != tag || entry->type != expectedType) {
        return EntryState::MALFORMED;
    }
    if (entry->count > 0U && entry->data.u8 == nullptr) return EntryState::MALFORMED;
    return EntryState::PRESENT;
}

template <typename Value>
void assignFinitePositive(std::optional<double>& destination, Value value) {
    const double normalized = static_cast<double>(value);
    if (std::isfinite(normalized) && normalized > 0.0) destination = normalized;
}

std::optional<std::string> capabilityName(std::uint8_t value) {
    switch (value) {
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
            return "BACKWARD_COMPATIBLE";
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW:
            return "RAW";
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
            return "LOGICAL_MULTI_CAMERA";
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
            return "DEPTH_OUTPUT";
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
            return "MONOCHROME";
        case ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA:
            return "SYSTEM_CAMERA";
        default:
            return std::nullopt;
    }
}

void readCapabilities(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
        ACAMERA_TYPE_BYTE,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED) {
        malformed = true;
        return;
    }
    record.reportedCapabilities = std::vector<std::string>{};
    record.rawCapabilityAdvertised = false;
    const std::uint32_t count = std::min(entry.count, kMaximumCapabilityValues);
    if (entry.count > kMaximumCapabilityValues) malformed = true;
    for (std::uint32_t index = 0; index < count; ++index) {
        const std::uint8_t capability = entry.data.u8[index];
        if (capability == ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW) {
            record.rawCapabilityAdvertised = true;
        }
        const auto name = capabilityName(capability);
        if (name.has_value() &&
            std::find(
                record.reportedCapabilities->begin(),
                record.reportedCapabilities->end(),
                *name) == record.reportedCapabilities->end()) {
            record.reportedCapabilities->push_back(*name);
        }
    }
}

void readFocalLengths(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        ACAMERA_TYPE_FLOAT,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED) {
        malformed = true;
        return;
    }
    const std::uint32_t count = std::min(entry.count, kMaximumFocalLengths);
    if (entry.count > kMaximumFocalLengths) malformed = true;
    for (std::uint32_t index = 0; index < count; ++index) {
        const double focal = entry.data.f[index];
        if (std::isfinite(focal) && focal > 0.0) record.focalLengthsMm.push_back(focal);
    }
    std::sort(record.focalLengthsMm.begin(), record.focalLengthsMm.end());
    record.focalLengthsMm.erase(
        std::unique(record.focalLengthsMm.begin(), record.focalLengthsMm.end()),
        record.focalLengthsMm.end());
}

void readSingleByte(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    std::uint32_t tag,
    std::optional<std::int32_t>& destination,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(api, metadata, tag, ACAMERA_TYPE_BYTE, &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED || entry.count < 1U) {
        malformed = true;
        return;
    }
    destination = static_cast<std::int32_t>(entry.data.u8[0]);
}

void readSingleInt32(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    std::uint32_t tag,
    std::optional<std::int32_t>& destination,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(api, metadata, tag, ACAMERA_TYPE_INT32, &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED || entry.count < 1U) {
        malformed = true;
        return;
    }
    destination = entry.data.i32[0];
}

void readSensorSize(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_SENSOR_INFO_PHYSICAL_SIZE,
        ACAMERA_TYPE_FLOAT,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED || entry.count < 2U) {
        malformed = true;
        return;
    }
    assignFinitePositive(record.sensorWidthMm, entry.data.f[0]);
    assignFinitePositive(record.sensorHeightMm, entry.data.f[1]);
}

void readActiveArray(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE,
        ACAMERA_TYPE_INT32,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED || entry.count < 4U) {
        malformed = true;
        return;
    }
    const SensorRect candidate{
        entry.data.i32[0],
        entry.data.i32[1],
        entry.data.i32[2],
        entry.data.i32[3],
    };
    if (candidate.right > candidate.left && candidate.bottom > candidate.top) {
        record.activeArray = candidate;
    } else {
        malformed = true;
    }
}

void readPixelArray(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_SENSOR_INFO_PIXEL_ARRAY_SIZE,
        ACAMERA_TYPE_INT32,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED || entry.count < 2U) {
        malformed = true;
        return;
    }
    if (entry.data.i32[0] > 0 && entry.data.i32[1] > 0) {
        record.pixelWidth = entry.data.i32[0];
        record.pixelHeight = entry.data.i32[1];
    } else {
        malformed = true;
    }
}

void addRawFormat(CameraRecord& record, const char* name) {
    if (std::find(record.rawFormats.begin(), record.rawFormats.end(), name) ==
        record.rawFormats.end()) {
        record.rawFormats.emplace_back(name);
    }
}

void addBoundedSize(
    std::vector<StreamSize>& sizes,
    std::int32_t width,
    std::int32_t height) {
    const auto duplicate = std::find_if(
        sizes.begin(),
        sizes.end(),
        [width, height](const StreamSize& size) {
            return size.width == width && size.height == height;
        });
    if (duplicate != sizes.end()) return;
    sizes.push_back(StreamSize{width, height});
    std::sort(sizes.begin(), sizes.end(), [](const StreamSize& left, const StreamSize& right) {
        const auto leftArea = static_cast<std::int64_t>(left.width) * left.height;
        const auto rightArea = static_cast<std::int64_t>(right.width) * right.height;
        if (leftArea != rightArea) return leftArea > rightArea;
        if (left.width != right.width) return left.width > right.width;
        return left.height > right.height;
    });
    if (sizes.size() > kMaximumSizesPerStreamClass) sizes.resize(kMaximumSizesPerStreamClass);
}

void readStreamEvidence(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
        ACAMERA_TYPE_INT32,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED) {
        malformed = true;
        return;
    }
    if (entry.count % 4U != 0U || entry.count > kMaximumStreamConfigurationWords) {
        malformed = true;
    }
    const std::uint32_t wordCount = std::min(entry.count, kMaximumStreamConfigurationWords);
    for (std::uint32_t index = 0; index + 3U < wordCount; index += 4U) {
        const std::int32_t format = entry.data.i32[index];
        const std::int32_t width = entry.data.i32[index + 1U];
        const std::int32_t height = entry.data.i32[index + 2U];
        const std::int32_t direction = entry.data.i32[index + 3U];
        if (direction != ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT ||
            width <= 0 || height <= 0) {
            continue;
        }
        switch (format) {
            case AIMAGE_FORMAT_PRIVATE:
                record.privatePreviewStreamDeclared = true;
                addBoundedSize(record.privatePreviewSizes, width, height);
                break;
            case AIMAGE_FORMAT_YUV_420_888:
                record.yuvPreviewStreamDeclared = true;
                addBoundedSize(record.yuvPreviewSizes, width, height);
                break;
            case AIMAGE_FORMAT_RAW16:
                addRawFormat(record, "RAW_SENSOR");
                addBoundedSize(record.rawSizes, width, height);
                break;
            case AIMAGE_FORMAT_RAW10:
                addRawFormat(record, "RAW10");
                addBoundedSize(record.rawSizes, width, height);
                break;
            case AIMAGE_FORMAT_RAW12:
                addRawFormat(record, "RAW12");
                addBoundedSize(record.rawSizes, width, height);
                break;
            case AIMAGE_FORMAT_RAW_PRIVATE:
                addRawFormat(record, "RAW_PRIVATE");
                addBoundedSize(record.rawSizes, width, height);
                break;
            case kImageFormatRaw14:
                addRawFormat(record, "RAW14");
                addBoundedSize(record.rawSizes, width, height);
                break;
            default:
                break;
        }
    }
}

void readPhysicalIds(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    CameraRecord& record,
    bool& malformed) {
    ACameraMetadata_const_entry entry{};
    const EntryState state = readEntry(
        api,
        metadata,
        ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS,
        ACAMERA_TYPE_BYTE,
        &entry);
    if (state == EntryState::MISSING) return;
    if (state == EntryState::MALFORMED) {
        malformed = true;
        return;
    }
    if (entry.count > kMaximumPhysicalIdBytes) malformed = true;
    const std::uint32_t count = std::min(entry.count, kMaximumPhysicalIdBytes);
    std::uint32_t start = 0;
    for (std::uint32_t index = 0; index <= count; ++index) {
        const bool atEnd = index == count;
        if (!atEnd && entry.data.u8[index] != 0U) continue;
        if (index > start) {
            const auto* begin = reinterpret_cast<const char*>(entry.data.u8 + start);
            std::string id(begin, begin + (index - start));
            if (isSafeUtf8Id(id)) {
                if (std::find(record.physicalCameraIds.begin(), record.physicalCameraIds.end(), id) ==
                    record.physicalCameraIds.end()) {
                    record.physicalCameraIds.push_back(std::move(id));
                }
            } else {
                malformed = true;
            }
        }
        start = index + 1U;
    }
}

CameraRecord readCameraRecord(
    const CameraNdkApi& api,
    const std::string& cameraId,
    const ACameraMetadata* metadata,
    bool& malformed) {
    CameraRecord record;
    record.id = cameraId;
    readSingleByte(api, metadata, ACAMERA_LENS_FACING, record.facing, malformed);
    readFocalLengths(api, metadata, record, malformed);
    readSensorSize(api, metadata, record, malformed);
    readActiveArray(api, metadata, record, malformed);
    readPixelArray(api, metadata, record, malformed);
    readSingleInt32(
        api,
        metadata,
        ACAMERA_SENSOR_ORIENTATION,
        record.sensorOrientationDegrees,
        malformed);
    readSingleByte(
        api,
        metadata,
        ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL,
        record.hardwareLevel,
        malformed);
    readCapabilities(api, metadata, record, malformed);
    readStreamEvidence(api, metadata, record, malformed);
    readSingleByte(
        api,
        metadata,
        ACAMERA_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT,
        record.colorFilterArrangement,
        malformed);
    readPhysicalIds(api, metadata, record, malformed);
    return record;
}

std::string statusReason(camera_status_t status) {
    switch (status) {
        case ACAMERA_ERROR_INVALID_PARAMETER: return "INVALID_CAMERA_ID";
        case ACAMERA_ERROR_PERMISSION_DENIED: return "ACCESS_DENIED";
        case ACAMERA_ERROR_CAMERA_DISCONNECTED: return "CAMERA_DISCONNECTED";
        case ACAMERA_ERROR_NOT_ENOUGH_MEMORY: return "NOT_ENOUGH_MEMORY";
        case ACAMERA_ERROR_METADATA_NOT_FOUND: return "METADATA_UNAVAILABLE";
        case ACAMERA_ERROR_CAMERA_DEVICE: return "CAMERA_DEVICE_ERROR";
        case ACAMERA_ERROR_CAMERA_SERVICE: return "CAMERA_SERVICE_ERROR";
        case ACAMERA_ERROR_INVALID_OPERATION: return "INVALID_OPERATION";
        case ACAMERA_ERROR_CAMERA_IN_USE: return "CAMERA_IN_USE";
        case ACAMERA_ERROR_MAX_CAMERA_IN_USE: return "MAX_CAMERAS_IN_USE";
        case ACAMERA_ERROR_CAMERA_DISABLED: return "CAMERA_DISABLED";
        case ACAMERA_ERROR_UNSUPPORTED_OPERATION: return "UNSUPPORTED_OPERATION";
        default: return "UNKNOWN_NATIVE_STATUS";
    }
}

void addFailure(
    ScanResult& result,
    std::optional<std::string> cameraId,
    std::string stage,
    std::string reason,
    std::optional<std::int32_t> statusCode = std::nullopt) {
    result.failures.push_back(Failure{
        std::move(cameraId),
        std::move(stage),
        std::move(reason),
        statusCode,
    });
}

void scanCharacteristics(
    const CameraNdkApi& api,
    ACameraManager* manager,
    ScanResult& result) {
    for (const auto& cameraId : result.requestedIds) {
        result.attemptedIds.push_back(cameraId);
        ++result.attemptedCount;
        ACameraMetadata* metadataValue = nullptr;
        const camera_status_t status = api.getCameraCharacteristics(
            manager,
            cameraId.c_str(),
            &metadataValue);
        MetadataOwner metadata(api, metadataValue);
        if (status != ACAMERA_OK || metadata.get() == nullptr) {
            addFailure(
                result,
                cameraId,
                "READ_CHARACTERISTICS",
                status == ACAMERA_OK ? "MALFORMED_VENDOR_METADATA" : statusReason(status),
                static_cast<std::int32_t>(status));
            continue;
        }
        bool malformed = false;
        result.cameras.push_back(readCameraRecord(api, cameraId, metadata.get(), malformed));
        if (malformed) {
            addFailure(
                result,
                cameraId,
                "READ_CHARACTERISTICS",
                "MALFORMED_VENDOR_METADATA");
        }
    }
}

void appendCamera(std::ostringstream& output, const CameraRecord& camera) {
    output << '{' << "\"id\":";
    appendJsonString(output, camera.id);
    output << ",\"facing\":";
    appendOptionalNumber(output, camera.facing);
    output << ",\"focalLengthsMm\":[";
    for (std::size_t index = 0; index < camera.focalLengthsMm.size(); ++index) {
        if (index != 0U) output << ',';
        output << camera.focalLengthsMm[index];
    }
    output << "],\"sensorWidthMm\":";
    appendOptionalNumber(output, camera.sensorWidthMm);
    output << ",\"sensorHeightMm\":";
    appendOptionalNumber(output, camera.sensorHeightMm);
    output << ",\"activeArray\":";
    if (camera.activeArray.has_value()) {
        output << "{\"left\":" << camera.activeArray->left
               << ",\"top\":" << camera.activeArray->top
               << ",\"right\":" << camera.activeArray->right
               << ",\"bottom\":" << camera.activeArray->bottom << '}';
    } else {
        output << "null";
    }
    output << ",\"pixelWidth\":";
    appendOptionalNumber(output, camera.pixelWidth);
    output << ",\"pixelHeight\":";
    appendOptionalNumber(output, camera.pixelHeight);
    output << ",\"sensorOrientationDegrees\":";
    appendOptionalNumber(output, camera.sensorOrientationDegrees);
    output << ",\"hardwareLevel\":";
    appendOptionalNumber(output, camera.hardwareLevel);
    output << ",\"rawCapabilityAdvertised\":";
    appendOptionalBoolean(output, camera.rawCapabilityAdvertised);
    output << ",\"rawFormats\":";
    appendStringArray(output, camera.rawFormats);
    const auto appendSizes = [&output](const std::vector<StreamSize>& sizes) {
        output << '[';
        for (std::size_t index = 0; index < sizes.size(); ++index) {
            if (index != 0U) output << ',';
            output << "{\"width\":" << sizes[index].width
                   << ",\"height\":" << sizes[index].height << '}';
        }
        output << ']';
    };
    output << ",\"rawSizes\":";
    appendSizes(camera.rawSizes);
    output << ",\"privatePreviewStreamDeclared\":"
           << (camera.privatePreviewStreamDeclared ? "true" : "false")
           << ",\"privatePreviewSizes\":";
    appendSizes(camera.privatePreviewSizes);
    output
           << ",\"yuvPreviewStreamDeclared\":"
           << (camera.yuvPreviewStreamDeclared ? "true" : "false")
           << ",\"yuvPreviewSizes\":";
    appendSizes(camera.yuvPreviewSizes);
    output
           << ",\"reportedCapabilities\":";
    if (camera.reportedCapabilities.has_value()) {
        appendStringArray(output, *camera.reportedCapabilities);
    } else {
        output << "null";
    }
    output << ",\"colorFilterArrangement\":";
    appendOptionalNumber(output, camera.colorFilterArrangement);
    output << ",\"physicalCameraIds\":";
    appendStringArray(output, camera.physicalCameraIds);
    output << '}';
}

void appendFailure(std::ostringstream& output, const Failure& failure) {
    output << "{\"cameraId\":";
    if (failure.cameraId.has_value()) {
        appendJsonString(output, *failure.cameraId);
    } else {
        output << "null";
    }
    output << ",\"stage\":";
    appendJsonString(output, failure.stage);
    output << ",\"reason\":";
    appendJsonString(output, failure.reason);
    output << ",\"statusCode\":";
    appendOptionalNumber(output, failure.statusCode);
    output << '}';
}

std::string serialize(ScanResult& result, Clock::time_point started) {
    result.durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now() - started).count();
    std::ostringstream output;
    output.imbue(std::locale::classic());
    output << std::setprecision(std::numeric_limits<double>::max_digits10)
           << "{\"schemaVersion\":" << kPayloadSchemaVersion
           << ",\"source\":";
    appendJsonString(output, result.source);
    output << ",\"durationMs\":" << result.durationMs
           << ",\"advertisedCount\":" << result.advertisedCount
           << ",\"requestedCount\":" << result.requestedCount
           << ",\"attemptedCount\":" << result.attemptedCount
           << ",\"validCount\":" << result.cameras.size()
           << ",\"failureCount\":" << result.failures.size()
           << ",\"skippedCount\":" << result.skippedCount
           << ",\"advertisedIds\":";
    appendStringArray(output, result.advertisedIds);
    output << ",\"requestedIds\":";
    appendStringArray(output, result.requestedIds);
    output << ",\"attemptedIds\":";
    appendStringArray(output, result.attemptedIds);
    output << ",\"cameras\":[";
    for (std::size_t index = 0; index < result.cameras.size(); ++index) {
        if (index != 0U) output << ',';
        appendCamera(output, result.cameras[index]);
    }
    output << "],\"failures\":[";
    for (std::size_t index = 0; index < result.failures.size(); ++index) {
        if (index != 0U) output << ',';
        appendFailure(output, result.failures[index]);
    }
    output << "]}";
    return output.str();
}

std::string discoverAdvertised() {
    const auto started = Clock::now();
    ScanResult result("NDK_ADVERTISED");
    try {
        const CameraNdkApi& api = CameraNdkApi::instance();
        if (!api.available()) {
            addFailure(
                result,
                std::nullopt,
                "LOAD_API",
                "NATIVE_API_UNAVAILABLE");
            return serialize(result, started);
        }
        ManagerOwner manager(api, api.createManager());
        if (manager.get() == nullptr) {
            addFailure(
                result,
                std::nullopt,
                "CREATE_MANAGER",
                "MANAGER_CREATION_FAILED");
            return serialize(result, started);
        }
        ACameraIdList* idListValue = nullptr;
        const camera_status_t listStatus = api.getCameraIdList(manager.get(), &idListValue);
        CameraIdListOwner idList(api, idListValue);
        if (listStatus != ACAMERA_OK || idListValue == nullptr) {
            addFailure(
                result,
                std::nullopt,
                "LIST_IDS",
                "ID_ENUMERATION_FAILED",
                static_cast<std::int32_t>(listStatus));
            return serialize(result, started);
        }
        const int rawCount = idListValue->numCameras;
        if (rawCount < 0 || (rawCount > 0 && idListValue->cameraIds == nullptr)) {
            addFailure(
                result,
                std::nullopt,
                "LIST_IDS",
                "MALFORMED_VENDOR_METADATA");
            return serialize(result, started);
        }
        const int boundedCount = std::min(rawCount, kMaximumAdvertisedIds);
        result.advertisedCount = boundedCount;
        if (rawCount > kMaximumAdvertisedIds) {
            result.skippedCount += rawCount - kMaximumAdvertisedIds;
            addFailure(
                result,
                std::nullopt,
                "LIST_IDS",
                "ADVERTISED_LIST_TRUNCATED");
        }
        std::set<std::string> seen;
        for (int index = 0; index < boundedCount; ++index) {
            const char* rawId = idListValue->cameraIds[index];
            const std::string id = rawId == nullptr ? std::string{} : std::string(rawId);
            if (!isSafeUtf8Id(id)) {
                ++result.skippedCount;
                addFailure(
                    result,
                    std::nullopt,
                    "INPUT_VALIDATION",
                    "INVALID_CAMERA_ID");
                continue;
            }
            if (!seen.insert(id).second) {
                ++result.skippedCount;
                continue;
            }
            result.advertisedIds.push_back(id);
            result.requestedIds.push_back(id);
        }
        result.requestedCount = static_cast<int>(result.requestedIds.size());
        scanCharacteristics(api, manager.get(), result);
        return serialize(result, started);
    } catch (const std::bad_alloc&) {
        addFailure(
            result,
            std::nullopt,
            "READ_CHARACTERISTICS",
            "NOT_ENOUGH_MEMORY");
        return serialize(result, started);
    } catch (...) {
        addFailure(
            result,
            std::nullopt,
            "READ_CHARACTERISTICS",
            "CAMERA_SERVICE_ERROR");
        return serialize(result, started);
    }
}

std::vector<std::string> readCandidateIds(
    JNIEnv* environment,
    jobjectArray cameraIds,
    ScanResult& result) {
    std::vector<std::string> candidates;
    if (cameraIds == nullptr) {
        addFailure(
            result,
            std::nullopt,
            "INPUT_VALIDATION",
            "INVALID_CAMERA_ID");
        return candidates;
    }
    const jsize rawCount = environment->GetArrayLength(cameraIds);
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        addFailure(
            result,
            std::nullopt,
            "INPUT_VALIDATION",
            "INVALID_CAMERA_ID");
        return candidates;
    }
    const jsize boundedCount = std::min(rawCount, static_cast<jsize>(kMaximumDeepCandidates));
    result.requestedCount = boundedCount;
    if (rawCount > boundedCount) {
        result.skippedCount += rawCount - boundedCount;
        addFailure(
            result,
            std::nullopt,
            "INPUT_VALIDATION",
            "CANDIDATE_LIMIT_REACHED");
    }
    std::set<std::string> seen;
    for (jsize index = 0; index < boundedCount; ++index) {
        auto* value = static_cast<jstring>(environment->GetObjectArrayElement(cameraIds, index));
        if (environment->ExceptionCheck()) {
            environment->ExceptionClear();
            ++result.skippedCount;
            addFailure(
                result,
                std::nullopt,
                "INPUT_VALIDATION",
                "INVALID_CAMERA_ID");
            continue;
        }
        const auto converted = javaStringToUtf8(environment, value);
        if (value != nullptr) environment->DeleteLocalRef(value);
        if (!converted.has_value()) {
            ++result.skippedCount;
            addFailure(
                result,
                std::nullopt,
                "INPUT_VALIDATION",
                "INVALID_CAMERA_ID");
            continue;
        }
        if (seen.insert(*converted).second) candidates.push_back(*converted);
    }
    result.requestedIds = candidates;
    return candidates;
}

std::string discoverCandidates(JNIEnv* environment, jobjectArray cameraIds) {
    const auto started = Clock::now();
    ScanResult result("NDK_DEEP");
    try {
        readCandidateIds(environment, cameraIds, result);
        if (result.requestedIds.empty()) return serialize(result, started);
        const CameraNdkApi& api = CameraNdkApi::instance();
        if (!api.available()) {
            addFailure(
                result,
                std::nullopt,
                "LOAD_API",
                "NATIVE_API_UNAVAILABLE");
            return serialize(result, started);
        }
        ManagerOwner manager(api, api.createManager());
        if (manager.get() == nullptr) {
            addFailure(
                result,
                std::nullopt,
                "CREATE_MANAGER",
                "MANAGER_CREATION_FAILED");
            return serialize(result, started);
        }
        scanCharacteristics(api, manager.get(), result);
        return serialize(result, started);
    } catch (const std::bad_alloc&) {
        addFailure(
            result,
            std::nullopt,
            "READ_CHARACTERISTICS",
            "NOT_ENOUGH_MEMORY");
        return serialize(result, started);
    } catch (...) {
        addFailure(
            result,
            std::nullopt,
            "READ_CHARACTERISTICS",
            "CAMERA_SERVICE_ERROR");
        return serialize(result, started);
    }
}

jbyteArray toByteArray(JNIEnv* environment, const std::string& value) {
    if (value.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    const auto length = static_cast<jsize>(value.size());
    jbyteArray result = environment->NewByteArray(length);
    if (result == nullptr || length == 0) return result;
    environment->SetByteArrayRegion(
        result,
        0,
        length,
        reinterpret_cast<const jbyte*>(value.data()));
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahidcode404_camex_nativebridge_NativeBridge_nativeDiscoverAdvertisedCamerasPayload(
    JNIEnv* environment,
    jobject /* bridge */) {
    return toByteArray(environment, discoverAdvertised());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahidcode404_camex_nativebridge_NativeBridge_nativeDiscoverCameraMetadataPayload(
    JNIEnv* environment,
    jobject /* bridge */,
    jobjectArray cameraIds) {
    return toByteArray(environment, discoverCandidates(environment, cameraIds));
}
