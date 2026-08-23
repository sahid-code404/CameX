package com.sahidcode404.camex.core.camera.discovery.nativebackend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object NativeDiscoveryPayloadParser {
    private const val PAYLOAD_SCHEMA_VERSION = 1
    private const val MAX_RESULT_IDS = 512
    private const val MAX_FOCAL_LENGTHS = 32
    private const val MAX_SIZES_PER_STREAM_CLASS = 8

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(
        payload: String,
        expectedSource: NativeDiscoverySource,
        fallbackRequestedIds: List<String> = emptyList(),
    ): NativeDiscoveryResult = runCatching {
        val decoded = json.decodeFromString<PayloadDto>(payload)
        require(decoded.schemaVersion == PAYLOAD_SCHEMA_VERSION)
        require(decoded.source == expectedSource.name)
        decoded.toResult(expectedSource, fallbackRequestedIds)
    }.getOrElse {
        failureResult(
            source = expectedSource,
            requestedIds = fallbackRequestedIds,
            stage = NativeDiscoveryFailureStage.DECODE_PAYLOAD,
            reason = NativeDiscoveryFailureReason.MALFORMED_NATIVE_RESPONSE,
        )
    }

    fun unavailableResult(
        source: NativeDiscoverySource,
        requestedIds: List<String> = emptyList(),
    ): NativeDiscoveryResult = failureResult(
        source = source,
        requestedIds = requestedIds,
        stage = NativeDiscoveryFailureStage.LOAD_API,
        reason = NativeDiscoveryFailureReason.NATIVE_API_UNAVAILABLE,
    )

    fun invocationFailureResult(
        source: NativeDiscoverySource,
        requestedIds: List<String> = emptyList(),
    ): NativeDiscoveryResult = failureResult(
        source = source,
        requestedIds = requestedIds,
        stage = NativeDiscoveryFailureStage.JNI_CALL,
        reason = NativeDiscoveryFailureReason.JNI_INVOCATION_FAILED,
    )

    private fun PayloadDto.toResult(
        source: NativeDiscoverySource,
        fallbackRequestedIds: List<String>,
    ): NativeDiscoveryResult {
        val supplementalFailures = mutableListOf<NativeDiscoveryFailure>()
        val advertised = advertisedIds.safeIds()
        val requested = requestedIds.safeIds().ifEmpty { fallbackRequestedIds.safeIds() }
        val attempted = attemptedIds.safeIds()
        val mappedCameras = cameras
            .asSequence()
            .take(MAX_RESULT_IDS)
            .mapNotNull { camera ->
                camera.toModel(source) ?: run {
                    supplementalFailures += NativeDiscoveryFailure(
                        stage = NativeDiscoveryFailureStage.INPUT_VALIDATION,
                        reason = NativeDiscoveryFailureReason.INVALID_CAMERA_ID,
                    )
                    null
                }
            }
            .distinctBy(NativeMinimalCameraMetadata::cameraId)
            .toList()
        val mappedFailures = failures
            .asSequence()
            .take(MAX_RESULT_IDS)
            .map { failure -> failure.toModel() }
            .toList() + supplementalFailures

        return NativeDiscoveryResult(
            source = source,
            advertisedCameraIds = advertised,
            requestedCameraIds = requested,
            attemptedCameraIds = attempted,
            cameras = mappedCameras,
            failures = mappedFailures,
            counts = NativeDiscoveryCounts(
                advertisedIdCount = advertisedCount.nonNegativeAtMost(MAX_RESULT_IDS),
                requestedCandidateCount = requestedCount.nonNegativeAtMost(MAX_RESULT_IDS),
                attemptedMetadataCount = attemptedCount.nonNegativeAtMost(MAX_RESULT_IDS),
                metadataValidCount = mappedCameras.size,
                failureCount = mappedFailures.size,
                skippedCandidateCount = skippedCount.nonNegativeAtMost(MAX_RESULT_IDS),
            ),
            durationMs = durationMs.coerceAtLeast(0L),
        )
    }

    private fun CameraDto.toModel(source: NativeDiscoverySource): NativeMinimalCameraMetadata? {
        if (!DeepAuxCandidatePlanner.isSafeExactId(id)) return null
        val focalLengths = focalLengthsMm
            .asSequence()
            .take(MAX_FOCAL_LENGTHS)
            .filter { it.isFinite() && it > 0.0 }
            .distinct()
            .sorted()
            .toList()
        val physicalSize = if (
            sensorWidthMm != null && sensorHeightMm != null &&
            sensorWidthMm.isFinite() && sensorHeightMm.isFinite() &&
            sensorWidthMm > 0.0 && sensorHeightMm > 0.0
        ) {
            NativeSizeF(sensorWidthMm, sensorHeightMm)
        } else {
            null
        }
        val active = activeArray?.toRect()
        val pixelSize = if (pixelWidth != null && pixelHeight != null && pixelWidth > 0 && pixelHeight > 0) {
            NativeSize(pixelWidth, pixelHeight)
        } else {
            null
        }
        val formats = rawFormats.mapNotNullTo(linkedSetOf()) { value ->
            enumValueOrNull<NativeRawStreamFormat>(value)
        }
        val capabilities = reportedCapabilities?.mapNotNullTo(linkedSetOf()) { value ->
            enumValueOrNull<NativeCameraCapability>(value)
        }
        return NativeMinimalCameraMetadata(
            cameraId = id,
            sources = setOf(source),
            facing = when (facing) {
                0 -> NativeLensFacing.FRONT
                1 -> NativeLensFacing.BACK
                2 -> NativeLensFacing.EXTERNAL
                else -> NativeLensFacing.UNKNOWN
            },
            focalLengthsMm = focalLengths,
            sensorPhysicalSizeMm = physicalSize,
            activeArray = active,
            pixelArraySize = pixelSize,
            sensorOrientationDegrees = sensorOrientationDegrees?.takeIf { it in 0..359 },
            hardwareLevel = when (hardwareLevel) {
                0 -> NativeHardwareLevel.LIMITED
                1 -> NativeHardwareLevel.FULL
                2 -> NativeHardwareLevel.LEGACY
                3 -> NativeHardwareLevel.LEVEL_3
                4 -> NativeHardwareLevel.EXTERNAL
                else -> NativeHardwareLevel.UNKNOWN
            },
            rawCapabilityAdvertised = rawCapabilityAdvertised,
            rawStreamActuallyDeclared = formats.any { it != NativeRawStreamFormat.RAW_PRIVATE },
            rawFormats = formats,
            rawSizes = rawSizes.toSizes(),
            privatePreviewStreamDeclared = privatePreviewStreamDeclared,
            privatePreviewSizes = privatePreviewSizes.toSizes(),
            yuvPreviewStreamDeclared = yuvPreviewStreamDeclared,
            yuvPreviewSizes = yuvPreviewSizes.toSizes(),
            reportedCapabilities = capabilities,
            colorFilterArrangement = colorFilterArrangement?.let { value ->
                when (value) {
                    0 -> NativeColorFilterArrangement.RGGB
                    1 -> NativeColorFilterArrangement.GRBG
                    2 -> NativeColorFilterArrangement.GBRG
                    3 -> NativeColorFilterArrangement.BGGR
                    4 -> NativeColorFilterArrangement.RGB
                    5 -> NativeColorFilterArrangement.MONO
                    6 -> NativeColorFilterArrangement.NIR
                    else -> NativeColorFilterArrangement.UNKNOWN
                }
            },
            physicalCameraIds = physicalCameraIds.safeIds().filterNot { it == id },
        )
    }

    private fun FailureDto.toModel(): NativeDiscoveryFailure = NativeDiscoveryFailure(
        cameraId = cameraId?.takeIf(DeepAuxCandidatePlanner::isSafeExactId),
        stage = enumValueOrNull<NativeDiscoveryFailureStage>(stage)
            ?: NativeDiscoveryFailureStage.READ_CHARACTERISTICS,
        reason = enumValueOrNull<NativeDiscoveryFailureReason>(reason)
            ?: NativeDiscoveryFailureReason.UNKNOWN_NATIVE_STATUS,
        statusCode = statusCode,
    )

    private fun ActiveArrayDto.toRect(): NativeSensorRect? = NativeSensorRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    ).takeIf(NativeSensorRect::isValid)

    private fun List<String>.safeIds(): List<String> = asSequence()
        .take(MAX_RESULT_IDS)
        .filter(DeepAuxCandidatePlanner::isSafeExactId)
        .distinct()
        .toList()

    private fun List<SizeDto>.toSizes(): List<NativeSize> = asSequence()
        .take(MAX_SIZES_PER_STREAM_CLASS)
        .map { size -> NativeSize(size.width, size.height) }
        .filter(NativeSize::isValid)
        .distinct()
        .sortedWith(
            compareByDescending<NativeSize> { it.width.toLong() * it.height.toLong() }
                .thenByDescending(NativeSize::width)
                .thenByDescending(NativeSize::height),
        )
        .toList()

    private fun Int.nonNegativeAtMost(maximum: Int): Int = coerceIn(0, maximum)

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun failureResult(
        source: NativeDiscoverySource,
        requestedIds: List<String>,
        stage: NativeDiscoveryFailureStage,
        reason: NativeDiscoveryFailureReason,
    ): NativeDiscoveryResult {
        val safeRequested = requestedIds.safeIds()
        val failure = NativeDiscoveryFailure(stage = stage, reason = reason)
        return NativeDiscoveryResult(
            source = source,
            requestedCameraIds = safeRequested,
            failures = listOf(failure),
            counts = NativeDiscoveryCounts(
                requestedCandidateCount = safeRequested.size,
                failureCount = 1,
            ),
        )
    }

    @Serializable
    private data class PayloadDto(
        val schemaVersion: Int = 0,
        val source: String = "",
        val durationMs: Long = 0L,
        val advertisedCount: Int = 0,
        val requestedCount: Int = 0,
        val attemptedCount: Int = 0,
        val validCount: Int = 0,
        val failureCount: Int = 0,
        val skippedCount: Int = 0,
        val advertisedIds: List<String> = emptyList(),
        val requestedIds: List<String> = emptyList(),
        val attemptedIds: List<String> = emptyList(),
        val cameras: List<CameraDto> = emptyList(),
        val failures: List<FailureDto> = emptyList(),
    )

    @Serializable
    private data class CameraDto(
        val id: String = "",
        val facing: Int? = null,
        val focalLengthsMm: List<Double> = emptyList(),
        val sensorWidthMm: Double? = null,
        val sensorHeightMm: Double? = null,
        val activeArray: ActiveArrayDto? = null,
        val pixelWidth: Int? = null,
        val pixelHeight: Int? = null,
        val sensorOrientationDegrees: Int? = null,
        val hardwareLevel: Int? = null,
        val rawCapabilityAdvertised: Boolean? = null,
        val rawFormats: List<String> = emptyList(),
        val rawSizes: List<SizeDto> = emptyList(),
        val privatePreviewStreamDeclared: Boolean = false,
        val privatePreviewSizes: List<SizeDto> = emptyList(),
        val yuvPreviewStreamDeclared: Boolean = false,
        val yuvPreviewSizes: List<SizeDto> = emptyList(),
        val reportedCapabilities: List<String>? = null,
        val colorFilterArrangement: Int? = null,
        val physicalCameraIds: List<String> = emptyList(),
    )

    @Serializable
    private data class ActiveArrayDto(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    @Serializable
    private data class SizeDto(
        val width: Int,
        val height: Int,
    )

    @Serializable
    private data class FailureDto(
        val cameraId: String? = null,
        val stage: String = "",
        val reason: String = "",
        val statusCode: Int? = null,
    )
}
