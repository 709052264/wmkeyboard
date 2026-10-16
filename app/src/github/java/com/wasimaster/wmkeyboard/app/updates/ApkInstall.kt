package com.wasimaster.wmkeyboard.app.updates

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.io.File
import java.security.MessageDigest

/** What the checks before an install concluded. */
internal sealed interface InstallPrecheck {
    data object Ok : InstallPrecheck
    data class Rejected(val reason: UpdateFailure) : InstallPrecheck
}

/**
 * Handing a downloaded APK to Android.
 *
 * Through `PackageInstaller` rather than an `ACTION_VIEW` on a content URI,
 * which is deprecated and, more to the point, reports nothing back: a failure
 * would reach the user as the system's own "App not installed" and reach this
 * app as silence. A session says why, which is what turns a wrong signing key
 * into a sentence the user can act on.
 */
internal object ApkInstall {

    private const val TAG = "AppUpdates"
    const val ACTION_STATUS = "com.wasimaster.wmkeyboard.app.updates.INSTALL_STATUS"

    /**
     * Whether this file is a newer build of this app, signed by the same key.
     *
     * None of this is the security boundary: Android refuses a mismatched
     * signature by itself, and would refuse a downgrade too. It is here so the
     * refusal arrives as a specific sentence before the user is sent through a
     * permission grant and a confirmation screen for a file that was never
     * going to install. It is also what makes a debug-signed build fail
     * gracefully instead of confusingly.
     */
    fun precheck(context: Context, apk: File): InstallPrecheck {
        val archive = archiveInfo(context, apk)
            ?: return InstallPrecheck.Rejected(UpdateFailure.CORRUPT)
        if (archive.packageName != context.packageName) {
            return InstallPrecheck.Rejected(UpdateFailure.SIGNATURE_MISMATCH)
        }
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull() ?: return InstallPrecheck.Rejected(UpdateFailure.INSTALL_FAILED)
        if (PackageInfoCompat.getLongVersionCode(archive) <=
            PackageInfoCompat.getLongVersionCode(installed)
        ) {
            return InstallPrecheck.Rejected(UpdateFailure.SIGNATURE_MISMATCH)
        }
        val ours = signerHashes(installed)
        val theirs = signerHashes(archive)
        if (ours.isEmpty() || theirs.isEmpty() || ours != theirs) {
            DebugLog.w(TAG, "release is signed with a different key than this install")
            return InstallPrecheck.Rejected(UpdateFailure.SIGNATURE_MISMATCH)
        }
        return InstallPrecheck.Ok
    }

    /**
     * Stages [apk] and asks Android to install it. Returns the session id, or
     * null when the session could not even be opened.
     *
     * `USER_ACTION_NOT_REQUIRED` is a request and not an instruction. Android
     * only honours it once this app is the installer of record for itself, and
     * the install being replaced was made by a browser or by adb, so the first
     * update always shows Android's own screen and some devices show it every
     * time. Asking for update ownership on the way through is what makes the
     * second update quieter than the first. Every path here still begins with
     * the user pressing Install, because on the quiet path that press is the
     * only warning they get before the keyboard restarts.
     */
    @Suppress("ReturnCount")
    fun commit(context: Context, apk: File, dexMetadata: File? = null): Int? {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            runCatching { setSize(apk.length()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = runCatching { installer.createSession(params) }.getOrElse { error ->
            DebugLog.w(TAG, "could not open an install session: ${error.message.orEmpty()}")
            return null
        }
        val written = runCatching {
            installer.openSession(sessionId).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    // Without this the staged bytes may never reach the disk
                    // and the commit fails with nothing useful to say.
                    session.fsync(output)
                }
                // The baseline profile, under the APK's own name with `.dm`:
                // Android pairs them by name and compiles the keyboard with
                // it during the install, rather than leaving it interpreted
                // until its overnight maintenance. Optional, so a staged one
                // that is missing changes nothing.
                if (dexMetadata != null && dexMetadata.isFile) {
                    session.openWrite(apk.nameWithoutExtension + ".dm", 0, dexMetadata.length()).use { output ->
                        dexMetadata.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                session.commit(statusSender(context, sessionId))
            }
        }.isSuccess
        if (!written) {
            // A session left open holds staged bytes until it times out, and
            // there is a limit on how many one app may have.
            runCatching { installer.abandonSession(sessionId) }
            DebugLog.w(TAG, "could not stage the update, session abandoned")
            return null
        }
        return sessionId
    }

    /**
     * Abandons sessions left behind by a process that died mid-install.
     *
     * Sessions outlive the process that made them, so without this an
     * interrupted install would leak one, and its staged copy of the APK, on
     * every attempt.
     */
    fun abandonStale(context: Context, keepSessionId: Int) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            installer.mySessions
                .filter { it.sessionId != keepSessionId }
                .forEach { runCatching { installer.abandonSession(it.sessionId) } }
        }
    }

    private fun statusSender(context: Context, sessionId: Int) = PendingIntent.getBroadcast(
        context,
        // The request code has to differ per session: PendingIntent equality
        // ignores extras, so two installs sharing a code would collide and one
        // session's result would be delivered to the other's sender.
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setAction(ACTION_STATUS),
        // Mutable because Android fills in the status and, when it wants a
        // screen shown, the intent that shows it. An immutable sender loses
        // both and the install simply never reports.
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    ).intentSender

    private fun archiveInfo(context: Context, apk: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
    }.getOrNull()

    // getPackageArchiveInfo takes plain int flags on every level this app
    // supports, and unlike getPackageInfo it was never deprecated in favour of
    // the PackageInfoFlags overload.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    /**
     * The SHA-256 of every certificate a package is signed with.
     *
     * A set rather than a first entry: an APK can carry several signers, and
     * "one of ours is in there" is not the question Android asks. It compares
     * the whole set, so this does too.
     */
    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // apkContentsSigners, not signingCertificateHistory: the
                // question is who signed this file, not who ever signed a
                // version of it.
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }
}

/**
 * Where Android reports what happened to an install.
 *
 * Headless on purpose. On `STATUS_SUCCESS` it is running inside the *new*
 * version, in a cold process nobody asked for, where starting an activity is
 * both restricted and rude; its whole job there is to tidy up. And when
 * Android wants a screen shown it hands the intent to the settings activity
 * rather than starting it here, because a foreground activity launching it is
 * never subject to the background-launch rules a receiver is.
 */
internal class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_INTENT,
                Intent::class.java,
            )
            GithubUpdateManager.onUserActionRequired(context, confirm)
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        GithubUpdateManager.onInstallStatus(context, status, message)
    }
}
