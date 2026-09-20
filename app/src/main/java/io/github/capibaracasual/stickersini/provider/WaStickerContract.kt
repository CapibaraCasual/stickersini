package io.github.capibaracasual.stickersini.provider

import android.content.Context

/**
 * Nombres de columna, rutas y acciÃ³n de intent del contrato WAStickerApps
 * que WhatsApp espera de cualquier aplicaciÃ³n de terceros que publique
 * stickers. No es un contrato propio: lo define WhatsApp y no se puede
 * modificar (ver ADR-0004).
 */
object WaStickerContract {

    private const val AUTHORITY_SUFFIX = "stickercontentprovider"

    /** Debe coincidir con `android:authorities` del `<provider>` en el manifiesto. */
    fun authority(context: Context): String = "${context.packageName}.$AUTHORITY_SUFFIX"

    object Path {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
    }

    object PackColumns {
        const val IDENTIFIER = "sticker_pack_identifier"
        const val NAME = "sticker_pack_name"
        const val PUBLISHER = "sticker_pack_publisher"
        const val TRAY_IMAGE = "sticker_pack_icon"
        const val ANDROID_PLAY_STORE_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_PACK = "animated_sticker_pack"

        val ALL = arrayOf(
            IDENTIFIER, NAME, PUBLISHER, TRAY_IMAGE, ANDROID_PLAY_STORE_LINK, IOS_APP_DOWNLOAD_LINK,
            PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_PACK,
        )
    }

    object StickerColumns {
        const val FILE_NAME = "sticker_file_name"
        const val EMOJI = "sticker_emoji"
        const val ACCESSIBILITY_TEXT = "sticker_accessibility_text"

        val ALL = arrayOf(FILE_NAME, EMOJI, ACCESSIBILITY_TEXT)
    }

    object AddPackIntent {
        const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
        const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
        const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
        const val EXTRA_VALIDATION_ERROR = "validation_error"
    }

    const val WHATSAPP_CONSUMER_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
}
