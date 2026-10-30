package com.bajaya.app

import com.bajaya.app.extract.MediaKind
import com.bajaya.app.extract.MediaTypes
import com.bajaya.app.util.extractFirstUrl
import com.bajaya.app.util.formatBytes
import com.bajaya.app.util.sanitizeFileName
import com.bajaya.app.util.sanitizeFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Pruebas unitarias (JVM) de las funciones puras: no necesitan emulador. Se ejecutan con ./gradlew test */
class UtilsTest {

    @Before
    fun fixLocale() {
        // formatBytes usa el idioma del dispositivo; fijamos uno para que el resultado sea estable.
        Locale.setDefault(Locale.US)
    }

    @Test
    fun formatBytes_convierteUnidades() {
        assertEquals("—", formatBytes(-1))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }

    @Test
    fun extractFirstUrl_encuentraEnlaceYQuitaPuntuacion() {
        assertEquals("https://a.com/v.mp4", extractFirstUrl("mira https://a.com/v.mp4."))
        assertEquals("https://a.com/x", extractFirstUrl("(https://a.com/x)"))
        assertEquals("https://a.com/v.mp4?x=1&y=2", extractFirstUrl("https://a.com/v.mp4?x=1&y=2 fin"))
        assertNull(extractFirstUrl("sin enlace"))
        assertNull(extractFirstUrl(null))
    }

    @Test
    fun sanitizeFolderName_noPermiteSalirDeLaCarpeta() {
        assertEquals("BajaYa", sanitizeFolderName("BajaYa"))
        assertEquals("BajaYa", sanitizeFolderName("   "))
        assertEquals("BajaYa", sanitizeFolderName("..."))
        assertEquals("etc", sanitizeFolderName("../../etc"))
        assertEquals("Cursos/Mate", sanitizeFolderName("Cursos/Mate"))
        assertEquals("Mi/Carpeta", sanitizeFolderName("Mi\\Carpeta"))
        assertEquals("abc", sanitizeFolderName("a:b*c"))
    }

    @Test
    fun sanitizeFileName_quitaCaracteresProhibidosYAcortaConservandoExtension() {
        assertEquals("a_b_c.mp4", sanitizeFileName("a/b:c.mp4"))
        assertEquals("archivo", sanitizeFileName(""))
        assertEquals("archivo", sanitizeFileName("..."))

        val long = sanitizeFileName("a".repeat(200) + ".mp4")
        assertEquals(114, long.length)
        assertTrue(long.endsWith(".mp4"))
    }

    @Test
    fun mediaTypes_reconoceExtensionesYTipos() {
        assertEquals("mp4", MediaTypes.extensionOf("video.MP4?x=1#t=3"))
        assertTrue(MediaTypes.isMediaFileName("/ruta/archivo.webm"))
        assertFalse(MediaTypes.isMediaFileName("/ruta/pagina.html"))

        assertEquals(MediaKind.VIDEO, MediaTypes.kindOf("video/mp4", "a.bin"))
        assertEquals(MediaKind.AUDIO, MediaTypes.kindOf(null, "cancion.mp3"))
        assertEquals(MediaKind.OTHER, MediaTypes.kindOf(null, "datos.zip"))

        assertEquals("video/webm", MediaTypes.mimeForExtension("webm"))
        assertEquals("mp3", MediaTypes.extensionForMime("audio/mpeg"))
    }
}
