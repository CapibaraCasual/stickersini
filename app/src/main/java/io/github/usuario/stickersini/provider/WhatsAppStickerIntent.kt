package io.github.usuario.stickersini.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Construye el intent de confirmación (RF-20) y detecta si WhatsApp está
 * instalado (RF-21), tal como exige WAStickerApps.
 */
object WhatsAppStickerIntent {

    fun isWhatsAppInstalled(context: Context): Boolean =
        isPackageInstalled(context, WaStickerContract.WHATSAPP_CONSUMER_PACKAGE) ||
            isPackageInstalled(context, WaStickerContract.WHATSAPP_BUSINESS_PACKAGE)

    fun buildAddPackIntent(context: Context, packIdentifier: String, packName: String): Intent =
        Intent(WaStickerContract.AddPackIntent.ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(WaStickerContract.AddPackIntent.EXTRA_STICKER_PACK_ID, packIdentifier)
            putExtra(WaStickerContract.AddPackIntent.EXTRA_STICKER_PACK_AUTHORITY, WaStickerContract.authority(context))
            putExtra(WaStickerContract.AddPackIntent.EXTRA_STICKER_PACK_NAME, packName)
        }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (error: PackageManager.NameNotFoundException) {
        false
    }
}
