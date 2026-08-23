package com.sahidcode404.camex.core.camera.topology

import com.sahidcode404.camex.core.model.FingerprintStrategy
import com.sahidcode404.camex.core.model.LensFingerprint
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Last-resort invariant guard after optical grouping. Distinct canonical objects must never publish
 * the same optical fingerprint. A collision is not permission to merge: retain both lenses and give
 * the later object a deterministic device-scoped identity derived from its exact profile identities.
 */
fun CameraTopology.withUniqueCanonicalFingerprints(): CameraTopology {
    if (routes.size < 2) return this
    val used = linkedSetOf<String>()
    var changed = false
    val repaired = routes.map { route ->
        val fingerprint = route.lensFingerprint
        if (fingerprint == null || used.add(fingerprint.value)) {
            route
        } else {
            changed = true
            var salt = 0
            var fallback: LensFingerprint
            do {
                val profileIdentity = route.profiles
                    .map(CameraProfile::profileFingerprint)
                    .sorted()
                    .joinToString("|")
                val canonical = buildString {
                    append("canonical-collision-v1|")
                    append(environmentFingerprint.buildFingerprint.trim()).append('|')
                    append(profileIdentity).append('|')
                    append(salt)
                }
                fallback = LensFingerprint(
                    value = "oc1_${sha256Collision(canonical)}",
                    strategy = FingerprintStrategy.DEVICE_SCOPED_FALLBACK,
                )
                salt += 1
            } while (!used.add(fallback.value))
            route.copy(lensFingerprint = fallback)
        }
    }
    return if (changed) copy(routes = repaired) else this
}

private fun sha256Collision(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
