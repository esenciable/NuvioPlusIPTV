package com.nuvio.tv.ext.livetv.magis

import java.io.File
import org.junit.Assert.assertTrue

import org.junit.Test

/**
 * La frontera de portabilidad del cliente nativo de Magis.
 *
 * Estos archivos se copian al cliente KMP de teléfono, donde `android.*`, `javax.*`, `java.*` y
 * `org.json` no existen en `commonMain`. Un solo import de plataforma los vuelve incompilables allá
 * —y el síntoma aparece recién al portar, no acá—. Este test es la alarma temprana: falla con el
 * `archivo:línea` exacto de cada violación.
 *
 * El glue que sí depende de la app de TV va declarado en [APP_GLUE], uno por uno, para que agregar
 * otro sea un diff visible y no un olvido.
 */
class MagisPortabilityTest {

    /** Archivos que NO tienen que ser portables, y por qué. */
    private val appGlue = mapOf(
        "MagisLiveSource.kt" to "produce LiveChannel, el modelo de la app de TV (en el teléfono lo mapea su propio adaptador)",
        "MagisVodSource.kt" to "produce NativeVodSource/NativeVodStream, modelos del pipeline de streams de la app de TV",
    )

    @Test
    fun `el paquete magis solo importa kotlin, kotlinx y a si mismo`() {
        val dir = packageDir()
        val files = dir.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no se encontraron archivos .kt en $dir", files.isNotEmpty())

        val offenders = files
            .filterNot { it.name in appGlue }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.isImport() && !line.isAllowedImport() }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }

        assertTrue(
            buildString {
                appendLine("El paquete magis tiene que compilar en el cliente KMP (composeApp/commonMain).")
                appendLine("Permitido: kotlin.*, kotlinx.* y com.nuvio.tv.ext.livetv.magis.*")
                appendLine("Si un archivo es glue de la app de TV, declaralo en [appGlue] con su motivo.")
                append("Violaciones (${offenders.size}):")
                offenders.forEach { appendLine().append("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `el glue declarado existe en el paquete`() {
        val dir = packageDir()
        appGlue.forEach { (name, reason) ->
            assertTrue(
                "[appGlue] declara $name ($reason) pero ese archivo no está en el paquete: " +
                    "actualizá la lista cuando el archivo se renombre o se mueva.",
                File(dir, name).isFile,
            )
        }
    }

    private fun packageDir(): File {
        val relative = "src/main/java/com/nuvio/tv/ext/livetv/magis"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "no se encontró el paquete magis. Directorio de trabajo: " +
                    "'${File(".").absolutePath}'. Probados: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private fun String.isImport(): Boolean = trimStart().startsWith("import ")

    private fun String.isAllowedImport(): Boolean {
        val line = trimStart()
        return ALLOWED_IMPORT_PREFIXES.any { line.startsWith(it) }
    }

    private companion object {
        val ALLOWED_IMPORT_PREFIXES = listOf(
            "import kotlin.",
            "import kotlinx.",
            "import com.nuvio.tv.ext.livetv.magis.",
        )
    }
}
