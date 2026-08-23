package com.sahidcode404.camex.core.camera.raw

import com.sahidcode404.camex.core.model.Size2D
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object RawCompatibilityReportJson {
    private val json = Json { prettyPrint = true }

    fun append(baseJson: String, state: RawCaptureState): String {
        val base = Json.parseToJsonElement(baseJson).jsonObject
        val diagnostics = state.diagnostics
        val context = diagnostics.context
        val raw = buildJsonObject {
            put("phase", state.phase.name)
            put("rawSupported", diagnostics.rawSupported.name)
            put("availableRawSizes", buildJsonArray {
                diagnostics.availableRawSizes.forEach { add(sizeObject(it)) }
            })
            putNullableSize("selectedRawSize", diagnostics.selectedRawSize)
            putNullable("canonicalFingerprint", context?.canonicalFingerprint)
            putNullable("profileFingerprint", context?.profileFingerprint)
            putNullable("routingKey", context?.routingKey)
            putNullable("openCameraId", context?.openCameraId)
            putNullable("physicalTarget", context?.streamPhysicalCameraId)
            putNullableNumber("selectionGeneration", context?.selectionGeneration)
            putNullableNumber("captureToken", context?.captureToken)
            putNullableNumber("rawTimestamp", diagnostics.rawTimestamp)
            putNullableNumber("resultTimestamp", diagnostics.resultTimestamp)
            putNullableNumber("exposureTimeNs", diagnostics.exposureTimeNs)
            putNullableNumber("iso", diagnostics.iso?.toLong())
            putNullableNumber("dngWidth", diagnostics.dngWidth?.toLong())
            putNullableNumber("dngHeight", diagnostics.dngHeight?.toLong())
            putNullableNumber("dngBytes", diagnostics.dngBytes)
            putNullable("mediaStoreUri", diagnostics.mediaStoreUri)
            putNullableNumber("captureDurationMs", diagnostics.captureDurationMs)
            putNullableNumber("writeDurationMs", diagnostics.writeDurationMs)
            putNullable("lastRawError", diagnostics.lastRawError)
        }
        val merged = JsonObject(base + ("rawCapture" to raw))
        return json.encodeToString(JsonElement.serializer(), merged)
    }

    private fun sizeObject(size: Size2D): JsonObject = buildJsonObject {
        put("width", size.width)
        put("height", size.height)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        key: String,
        value: String?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableNumber(
        key: String,
        value: Long?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableSize(
        key: String,
        value: Size2D?,
    ) {
        put(key, value?.let(::sizeObject) ?: JsonNull)
    }
}
