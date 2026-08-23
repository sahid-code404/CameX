package com.sahidcode404.camex.core.camera.discovery.nativebackend

/**
 * Builds a deterministic, bounded deep-scan list without assigning meaning to any numeric ID.
 * Previously successful IDs lead, followed by advertised numeric IDs, nearby gaps, then a small
 * low-number namespace. The output is safe to pass directly across JNI.
 */
object DeepAuxCandidatePlanner {
    fun plan(request: DeepAuxDiscoveryRequest): List<String> {
        val maximumCandidateCount = request.limits.maximumCandidateCount.coerceIn(
            0,
            HARD_MAXIMUM_CANDIDATE_COUNT,
        )
        if (maximumCandidateCount == 0) return emptyList()

        val maximumNumericId = request.limits.maximumNumericId.coerceIn(
            0,
            HARD_MAXIMUM_NUMERIC_ID,
        )
        val lowNamespaceMax = request.limits.lowNumericNamespaceMax.coerceIn(
            0,
            minOf(maximumNumericId, HARD_LOW_NAMESPACE_MAX),
        )
        val neighborRadius = request.limits.neighborRadius.coerceIn(0, HARD_NEIGHBOR_RADIUS)
        val selected = LinkedHashSet<String>(maximumCandidateCount)

        fun add(values: Collection<String>, numericOnly: Boolean = false) {
            values
                .asSequence()
                .take(HARD_MAXIMUM_INPUT_IDS)
                .filter(::isSafeExactId)
                .filter { !numericOnly || it.asBoundedNumericId(maximumNumericId) != null }
                .distinct()
                .sortedWith(CAMERA_ID_COMPARATOR)
                .forEach { value ->
                    if (selected.size < maximumCandidateCount) selected += value
                }
        }

        // Learned hidden endpoints are the highest-value candidates and remain generic evidence.
        add(request.previouslySuccessfulDeepCameraIds)
        add(request.cachedSuccessfulCameraIds)
        add(request.advertisedCameraIds, numericOnly = true)

        val knownNumericIds = sequenceOf(
            request.previouslySuccessfulDeepCameraIds,
            request.cachedSuccessfulCameraIds,
            request.advertisedCameraIds,
        )
            .flatMap(Collection<String>::asSequence)
            .take(HARD_MAXIMUM_INPUT_IDS)
            .mapNotNull { it.asBoundedNumericId(maximumNumericId) }
            .distinct()
            .sorted()
            .toList()

        knownNumericIds.forEach { known ->
            for (distance in 1..neighborRadius) {
                val below = known - distance
                val above = known + distance
                if (below >= 0 && selected.size < maximumCandidateCount) {
                    selected += below.toString()
                }
                if (above <= maximumNumericId && selected.size < maximumCandidateCount) {
                    selected += above.toString()
                }
            }
        }

        for (cameraId in 0..lowNamespaceMax) {
            if (selected.size >= maximumCandidateCount) break
            selected += cameraId.toString()
        }
        return selected.toList()
    }

    internal fun isSafeExactId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_CAMERA_ID_LENGTH &&
            value.none(Char::isISOControl)

    private fun String.asBoundedNumericId(maximum: Int): Int? {
        if (isEmpty() || length > MAX_NUMERIC_ID_LENGTH || any { it !in '0'..'9' }) return null
        return toIntOrNull()?.takeIf { it in 0..maximum }
    }

    private val CAMERA_ID_COMPARATOR = Comparator<String> { left, right ->
        val leftNumber = left.asBoundedNumericId(HARD_MAXIMUM_NUMERIC_ID)
        val rightNumber = right.asBoundedNumericId(HARD_MAXIMUM_NUMERIC_ID)
        when {
            leftNumber != null && rightNumber != null ->
                leftNumber.compareTo(rightNumber).takeIf { it != 0 } ?: left.compareTo(right)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    internal const val MAX_CAMERA_ID_LENGTH = 128
    internal const val HARD_MAXIMUM_CANDIDATE_COUNT = 128
    private const val HARD_MAXIMUM_INPUT_IDS = 512
    private const val HARD_MAXIMUM_NUMERIC_ID = 1024
    private const val HARD_LOW_NAMESPACE_MAX = 63
    private const val HARD_NEIGHBOR_RADIUS = 8
    private const val MAX_NUMERIC_ID_LENGTH = 10
}
