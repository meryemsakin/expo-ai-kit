package expo.modules.aikit.vision

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// ==================================================================
// Play services Task → coroutine
// ==================================================================

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
  addOnSuccessListener { value ->
    @Suppress("UNCHECKED_CAST")
    if (continuation.isActive) continuation.resume(value as T)
  }
  addOnFailureListener { error ->
    if (continuation.isActive) continuation.resumeWithException(error)
  }
  addOnCanceledListener {
    if (continuation.isActive) continuation.cancel()
  }
}
