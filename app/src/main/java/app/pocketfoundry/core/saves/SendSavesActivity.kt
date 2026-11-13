package app.pocketfoundry.core.saves

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * 0.8.18, the last app.pocketfoundry: Pocket Foundry 0.9 (dev.pocketfoundry, another app to Android) asks here for
 * Factorio's folder and gets read access to it, as a file picker would give it, to bring the saves, mods and config
 * over. Only that app, signed with this one's key: anything else gets nothing. No screen of its own.
 */
class SendSavesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        val pocketFoundry = caller == NewApp.ID &&
            packageManager.checkSignatures(packageName, caller) == PackageManager.SIGNATURE_MATCH
        if (pocketFoundry) {
            setResult(RESULT_OK, Intent()
                .setData(DocumentsContract.buildTreeDocumentUri("$packageName.files", "/"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
