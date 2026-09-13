package com.workouttracker.sync

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.IOException

/**
 * What went wrong, in words. [hint] says what to check, and is null when there
 * is nothing useful to add.
 */
data class SyncError(val message: String, val hint: String? = null)

/** A Drive call that came back with an HTTP error, carrying the mapped [error]. */
class DriveHttpException(val code: Int, val error: SyncError) : IOException(error.message)

/**
 * Translates Google's numeric status codes and Drive's HTTP errors into
 * something a person can act on.
 *
 * The distinction that matters is permanent-setup-problem versus
 * try-again-later: a wrong signing certificate and a dropped connection both
 * arrive as an opaque number, but only one of them is worth waiting out. Each
 * message below says which it is.
 */
object SyncErrors {

    // From GoogleSignInStatusCodes. Inlined so this file doesn't pull in the
    // deprecated GoogleSignIn entry point just for three constants.
    private const val SIGN_IN_FAILED = 12500
    private const val SIGN_IN_CANCELLED = 12501
    private const val SIGN_IN_IN_PROGRESS = 12502

    fun fromException(e: Throwable): SyncError = when (e) {
        is DriveHttpException -> e.error
        is ApiException -> fromStatusCode(e.statusCode)
        is IOException -> SyncError(
            "Couldn't reach Google Drive",
            "Check your connection. The next sync will try again.",
        )
        else -> SyncError(e.message ?: e.javaClass.simpleName)
    }

    /** Maps a Google Play services status code. */
    fun fromStatusCode(code: Int): SyncError = when (code) {
        CommonStatusCodes.DEVELOPER_ERROR -> SyncError(
            "This build isn't registered with Google",
            "Its package name and signing certificate must match an Android OAuth client in " +
                "your Google Cloud project. Compare the SHA-1 from './gradlew :app:signingReport' " +
                "with the one you registered. Waiting will not fix this.",
        )
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.INVALID_ACCOUNT -> SyncError(
            "Google needs you to sign in again",
            "Tap Connect Google Drive.",
        )
        CommonStatusCodes.NETWORK_ERROR -> SyncError(
            "Couldn't reach Google",
            "Check your connection. The next sync will try again.",
        )
        CommonStatusCodes.TIMEOUT -> SyncError(
            "Google took too long to respond",
            "Temporary. The next sync will try again.",
        )
        CommonStatusCodes.CANCELED, SIGN_IN_CANCELLED -> SyncError(
            "Sign-in was dismissed",
            "Tap Connect Google Drive to try again.",
        )
        SIGN_IN_IN_PROGRESS -> SyncError("Sign-in is already in progress")
        CommonStatusCodes.API_NOT_CONNECTED -> SyncError(
            "Google Play services can't reach the Drive API",
            "Check that the Drive API is enabled in your Google Cloud project and that Play " +
                "services is up to date.",
        )
        SIGN_IN_FAILED -> SyncError(
            "Google refused the sign-in",
            "If your OAuth consent screen is still in Testing, add this Google account under " +
                "Test users.",
        )
        CommonStatusCodes.INTERNAL_ERROR -> SyncError(
            "Google Play services hit an internal error",
            "Usually temporary. The next sync will try again.",
        )
        else -> SyncError(
            "Google returned an unexpected error (code $code)",
            "If it repeats, check the OAuth client and Drive API setup in Google Cloud.",
        )
    }

    /** Maps a Drive REST error. [reason] and [detail] come from the error body. */
    fun fromHttp(operation: String, code: Int, detail: String?, reason: String?): SyncError {
        val text = detail.orEmpty()
        return when {
            code == 401 -> SyncError(
                "Google rejected the access token",
                "Access may have been revoked. Tap Connect Google Drive to grant it again.",
            )
            // Google names the offending project in this one, which is the whole
            // point of showing the detail: the API is often enabled in a
            // different project than the OAuth client lives in.
            code == 403 && (reason == "accessNotConfigured" || text.contains("has not been used in project")) ->
                SyncError(
                    "The Drive API isn't enabled for your Google Cloud project",
                    "Enable it in the same project that holds your OAuth client. Google says: $text",
                )
            code == 403 && (reason == "insufficientPermissions" || text.contains("insufficient")) ->
                SyncError(
                    "This app wasn't granted Drive access",
                    "Tap Connect Google Drive and accept the permission request.",
                )
            code == 403 && reason == "rateLimitExceeded" -> SyncError(
                "Google is rate limiting us",
                "Temporary. The next sync will try again.",
            )
            code == 403 -> SyncError(
                "Google refused the request",
                text.ifEmpty { null },
            )
            code == 404 -> SyncError("The backup file no longer exists on Drive")
            code == 429 || code in 500..599 -> SyncError(
                "Google Drive is unavailable right now",
                "Temporary. The next sync will try again.",
            )
            else -> SyncError(
                "Drive $operation failed (HTTP $code)",
                text.ifEmpty { null },
            )
        }
    }

    /** Maps a [com.google.android.gms.common.GoogleApiAvailability] status. */
    fun playServicesUnavailable(status: Int): SyncError = SyncError(
        "Google Play services isn't available on this device",
        "Drive sync needs it. Update or enable Google Play services, then try again " +
            "(availability code $status).",
    )
}
