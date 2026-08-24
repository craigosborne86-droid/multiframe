package dev.multiframe.camera

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits a [ListenableFuture] without pulling in kotlinx-coroutines-guava.
 * CameraX 1.6.1 exposes ProcessCameraProvider.getInstance() only as a future.
 */
suspend fun <T> ListenableFuture<T>.await(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            executor,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
