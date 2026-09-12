package expo.modules.aikit.vision

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Play services optional-module install, shared by the features whose
 * models are Play services modules (subject segmentation, OCR). Compiled only
 * when one of them is; it is the only vision code that needs play-services-base.
 */
class PlayModules(private val support: VisionSupport) {

  companion object {
    private const val INSTALL_TIMEOUT_MS = 120_000L
    private const val INSTALL_POLL_MS = 2_000L
  }

  private val context: Context get() = support.context
  private val installClient: ModuleInstallClient by lazy { ModuleInstall.getClient(context) }

  private fun fail(code: String, reason: String): Nothing = support.fail(code, reason)
  private fun isContract(e: Throwable): Boolean = support.isContract(e)

  // ==================================================================
  // Play services & module install
  // ==================================================================

  fun playServicesAvailable(): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  fun requirePlayServices(feature: String) {
    if (!playServicesAvailable()) {
      fail("DEVICE_NOT_SUPPORTED", "$feature requires Google Play services, which this device does not have")
    }
  }

  suspend fun modulesInstalled(apis: List<OptionalModuleApi>): Boolean =
    try {
      installClient.areModulesAvailable(*apis.toTypedArray()).await().areModulesAvailable()
    } catch (_: Throwable) {
      false
    }

  suspend fun requireModules(feature: String, apis: List<OptionalModuleApi>) {
    if (!modulesInstalled(apis)) {
      fail(
        "MODEL_NOT_DOWNLOADED",
        "The on-device $feature model is not installed yet. Call prepareVision() first, " +
          "vision calls never download models themselves"
      )
    }
  }

  suspend fun installModules(apis: List<OptionalModuleApi>, onProgress: (Double) -> Unit) {
    if (apis.isEmpty() || modulesInstalled(apis)) {
      onProgress(1.0)
      return
    }
    onProgress(0.0)
    var failure: Throwable? = null
    val completed = try {
      withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
        coroutineScope {
          val install = async { awaitInstall(apis, onProgress) }
          // Play services does not always deliver a terminal listener update;
          // polling availability is the reliable signal, the listener just
          // makes success and failure land sooner.
          val poll = async {
            while (!modulesInstalled(apis)) delay(INSTALL_POLL_MS)
          }
          try {
            select<Unit> {
              install.onAwait {}
              poll.onAwait {}
            }
          } finally {
            install.cancel()
            poll.cancel()
          }
        }
        true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      failure = e
      null
    }
    if (completed == true || modulesInstalled(apis)) {
      onProgress(1.0)
      return
    }
    val reason = failure?.message?.let { if (isContract(failure)) throw failure else it }
    fail(
      "DOWNLOAD_FAILED",
      reason ?: "Timed out downloading the on-device vision model. Check the network and Google Play services, then try again"
    )
  }

  private suspend fun awaitInstall(apis: List<OptionalModuleApi>, onProgress: (Double) -> Unit) =
    suspendCancellableCoroutine<Unit> { continuation ->
      val client = installClient
      val listener = object : InstallStatusListener {
        override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
          update.progressInfo?.let { info ->
            if (info.totalBytesToDownload > 0) {
              val fraction = info.bytesDownloaded.toDouble() / info.totalBytesToDownload.toDouble()
              // Hold 1.0 for the terminal update so callers see a real "done".
              onProgress(fraction.coerceIn(0.0, 0.99))
            }
          }
          when (update.installState) {
            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
              client.unregisterListener(this)
              if (continuation.isActive) continuation.resume(Unit)
            }
            ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
              client.unregisterListener(this)
              if (continuation.isActive) {
                continuation.resumeWithException(
                  RuntimeException(
                    "DOWNLOAD_FAILED:${VisionSupport.MODEL_ID}:Google Play services could not install the on-device vision model"
                  )
                )
              }
            }
            else -> Unit
          }
        }
      }
      val request = ModuleInstallRequest.newBuilder()
        .apply { apis.forEach { addApi(it) } }
        .setListener(listener)
        .build()
      continuation.invokeOnCancellation {
        try {
          client.unregisterListener(listener)
        } catch (_: Throwable) {}
      }
      client.installModules(request)
        .addOnSuccessListener { response ->
          if (response.areModulesAlreadyInstalled()) {
            client.unregisterListener(listener)
            if (continuation.isActive) continuation.resume(Unit)
          }
        }
        .addOnFailureListener { error ->
          client.unregisterListener(listener)
          if (continuation.isActive) {
            continuation.resumeWithException(
              RuntimeException("DOWNLOAD_FAILED:${VisionSupport.MODEL_ID}:${error.message ?: error}", error)
            )
          }
        }
    }

}
