package de.skilltoremember.app

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import de.skilltoremember.app.update.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bietet ein gefundenes Update an, lädt die APK in den Cache und übergibt sie
 * dem System-Installer — genutzt vom stillen Start-Check (MainActivity) und
 * vom manuellen "Nach Updates suchen"-Knopf (SettingsActivity).
 */
object UpdateInstaller {

    fun offer(activity: AppCompatActivity, root: View, update: UpdateChecker.UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, update.versionName))
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall(activity, root, update) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(activity: AppCompatActivity, root: View, update: UpdateChecker.UpdateInfo) {
        Snackbar.make(root, R.string.update_downloading, Snackbar.LENGTH_LONG).show()
        activity.lifecycleScope.launch {
            val target = File(File(activity.cacheDir, "updates"), "skilltoremember.apk")
            val ok = UpdateChecker.download(update.apkUrl, target)
            if (!ok) {
                Snackbar.make(root, R.string.update_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(intent) }
                .onFailure { Snackbar.make(root, R.string.update_failed, Snackbar.LENGTH_LONG).show() }
        }
    }
}
