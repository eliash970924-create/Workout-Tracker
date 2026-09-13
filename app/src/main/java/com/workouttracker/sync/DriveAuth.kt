package com.workouttracker.sync

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Obtains an OAuth access token for the Drive *app data folder* — a private
 * per-app area the user cannot browse and other apps cannot read. We only ever
 * ask for this one scope, so the app can never see the rest of their Drive.
 */
object DriveAuth {

    private const val APP_DATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    sealed interface Result {
        /** Ready to call Drive. */
        data class Authorized(val accessToken: String) : Result

        /** The user has to grant access once; launch [pendingIntent] from an Activity. */
        data class ConsentRequired(val pendingIntent: PendingIntent) : Result

        data class Failed(val error: SyncError) : Result
    }

    suspend fun authorize(context: Context): Result {
        // Checked first so a device without Play services says so, rather than
        // surfacing as an opaque status code from the call below.
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            return Result.Failed(SyncErrors.playServicesUnavailable(availability))
        }

        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(APP_DATA_SCOPE)))
                .build()
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            val pendingIntent = result.pendingIntent
            when {
                // First run (or access revoked): Google needs the user to confirm.
                result.hasResolution() && pendingIntent != null -> Result.ConsentRequired(pendingIntent)
                result.accessToken != null -> Result.Authorized(result.accessToken!!)
                else -> Result.Failed(
                    SyncError(
                        "Google didn't return an access token",
                        "Tap Connect Google Drive to grant access again.",
                    )
                )
            }
        } catch (e: Exception) {
            Result.Failed(SyncErrors.fromException(e))
        }
    }

    /** Reads the token out of the Activity result of a [Result.ConsentRequired] intent. */
    fun tokenFromConsentResult(context: Context, data: android.content.Intent?): String? = try {
        Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
            .accessToken
    } catch (e: Exception) {
        null
    }

    /** Explains a failed consent flow, for when [tokenFromConsentResult] comes back null. */
    fun describeConsentFailure(context: Context, data: android.content.Intent?): SyncError = try {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        SyncError(
            "Google Drive access wasn't granted",
            "Tap Connect Google Drive and accept the permission request.",
        )
    } catch (e: Exception) {
        SyncErrors.fromException(e)
    }
}
