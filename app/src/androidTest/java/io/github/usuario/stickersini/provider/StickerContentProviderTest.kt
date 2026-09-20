package io.github.usuario.stickersini.provider

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica que [StickerContentProvider] responde el contrato WAStickerApps
 * tal como lo consultaría WhatsApp: primero la lista de packs, luego los
 * stickers de un pack, y por último que el asset de cada sticker se puede
 * abrir de verdad.
 */
@RunWith(AndroidJUnit4::class)
class StickerContentProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = WaStickerContract.authority(context)

    @Test
    fun metadata_devuelveElPackSemillaConSusTresStickers() {
        val metadataUri = Uri.parse("content://$authority/${WaStickerContract.Path.METADATA}")
        context.contentResolver.query(metadataUri, null, null, null, null).use { cursor ->
            assertNotNull("La consulta a /metadata no debe devolver null", cursor)
            checkNotNull(cursor)
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())

            val identifierIndex = cursor.getColumnIndexOrThrow(WaStickerContract.PackColumns.IDENTIFIER)
            val identifier = cursor.getString(identifierIndex)
            assertEquals("sticker_pack_semilla", identifier)

            val animatedIndex = cursor.getColumnIndexOrThrow(WaStickerContract.PackColumns.ANIMATED_PACK)
            assertEquals(0, cursor.getInt(animatedIndex))
        }

        val stickersUri = Uri.parse("content://$authority/${WaStickerContract.Path.STICKERS}/sticker_pack_semilla")
        context.contentResolver.query(stickersUri, null, null, null, null).use { cursor ->
            assertNotNull("La consulta a /stickers/<id> no debe devolver null", cursor)
            checkNotNull(cursor)
            assertEquals(3, cursor.count)

            val fileNameIndex = cursor.getColumnIndexOrThrow(WaStickerContract.StickerColumns.FILE_NAME)
            val fileNames = generateSequence { if (cursor.moveToNext()) cursor.getString(fileNameIndex) else null }.toList()
            assertEquals(listOf("sticker_1.webp", "sticker_2.webp", "sticker_3.webp"), fileNames)
        }
    }

    @Test
    fun stickersAsset_abreLosBytesDeCadaStickerYDelIconoDeBandeja() {
        val fileNames = listOf("sticker_1.webp", "sticker_2.webp", "sticker_3.webp", "tray.png")
        for (fileName in fileNames) {
            val assetUri = Uri.parse("content://$authority/${WaStickerContract.Path.STICKERS_ASSET}/sticker_pack_semilla/$fileName")
            context.contentResolver.openAssetFileDescriptor(assetUri, "r").use { descriptor ->
                assertNotNull("No se pudo abrir el asset $fileName", descriptor)
                checkNotNull(descriptor)
                assertTrue("$fileName debería pesar más de 0 bytes", descriptor.length > 0)
            }
        }
    }
}
