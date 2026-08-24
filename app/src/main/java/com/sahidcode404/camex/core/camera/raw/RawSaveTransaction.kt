package com.sahidcode404.camex.core.camera.raw

/**
 * Tiny transactional helper used by MediaStore persistence. If writing or commit fails, the newly
 * created destination is deleted. This is framework-free so cleanup behavior is unit-testable.
 */
class RawSaveTransaction<H>(
    private val create: () -> H?,
    private val write: (H) -> Unit,
    private val commit: (H) -> Unit,
    private val delete: (H) -> Unit,
) {
    fun execute(): Result<H> {
        val handle = try {
            create() ?: return Result.failure(IllegalStateException("Could not create DNG destination"))
        } catch (error: Throwable) {
            return Result.failure(error)
        }

        return try {
            write(handle)
            commit(handle)
            Result.success(handle)
        } catch (error: Throwable) {
            runCatching { delete(handle) }
            Result.failure(error)
        }
    }
}
