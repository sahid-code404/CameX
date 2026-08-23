package com.sahidcode404.camex.core.update

import java.io.File

class UpdateDownloader(
    private val directory: File,
    private val network: UpdateNetworkClient,
) {
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not create update cache directory")
        }
        val part = File(directory, "${manifest.versionCode}-${manifest.apk.name}.part")
        cleanupExcept(part.name)
        try {
            network.download(manifest.apk.downloadUrl, part, onProgress)
            if (!part.isFile || part.length() <= 0L) {
                throw UpdateException(UpdateFailureCode.STORAGE, "Downloaded update is empty")
            }
            if (manifest.apk.size > 0L && part.length() != manifest.apk.size) {
                throw UpdateException(UpdateFailureCode.HASH_MISMATCH, "Downloaded APK size does not match update metadata")
            }
            return part
        } catch (error: UpdateException) {
            part.delete()
            throw error
        } catch (error: Throwable) {
            part.delete()
            throw UpdateException(UpdateFailureCode.NETWORK, "Development APK download failed", error)
        }
    }

    fun promoteVerified(part: File, manifest: UpdateManifest): File {
        if (!part.isFile) throw UpdateException(UpdateFailureCode.STORAGE, "Verified update file is missing")
        val final = File(directory, manifest.apk.name)
        if (final.exists() && !final.delete()) {
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not replace previous update candidate")
        }
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            if (!part.delete()) final.delete()
        }
        if (!final.isFile || final.length() != manifest.apk.size) {
            final.delete()
            throw UpdateException(UpdateFailureCode.STORAGE, "Could not promote verified update candidate")
        }
        cleanupExcept(final.name)
        return final
    }

    fun discard(file: File?) {
        file?.takeIf { it.exists() }?.delete()
    }

    private fun cleanupExcept(keepName: String) {
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name != keepName &&
                (file.name.endsWith(".part") || file.name == "Camera-dev-ota.apk")
            ) {
                file.delete()
            }
        }
    }
}
