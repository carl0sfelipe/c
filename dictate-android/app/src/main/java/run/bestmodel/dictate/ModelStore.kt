package run.bestmodel.dictate

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * The model lives in app-private storage. It is downloaded once (or imported from a folder
 * for a phone that should never touch the network) and every file is checked by SHA-256.
 */
class ModelStore(context: Context) {

    data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

    val dir = File(context.filesDir, "models/parakeet-tdt-0.6b-v3-int8")

    val files = listOf(
        hf("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        hf("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        hf("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        hf("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        ModelFile(
            "silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
    )

    val totalBytes = files.sumOf { it.bytes }

    /** Cheap check (size only) for UI; the hash was verified when the file was written. */
    fun isReady() = files.all { File(dir, it.name).length() == it.bytes }

    /** Downloads what is missing, resuming partial files. [progress] gets total bytes on disk. */
    fun download(progress: (Long) -> Unit) {
        dir.mkdirs()
        var done = 0L
        for (file in files) {
            val target = File(dir, file.name)
            if (target.length() == file.bytes) {
                done += file.bytes
                progress(done)
                continue
            }
            val part = File(dir, file.name + ".part")
            fetch(file.url, part) { progress(done + it) }
            verifyAndPlace(part, file, target)
            done += file.bytes
            progress(done)
        }
    }

    /** Copies the model files from a folder the user picked (Storage Access Framework). */
    fun importFrom(context: Context, tree: Uri) {
        val folder = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Pasta inválida")
        dir.mkdirs()
        for (file in files) {
            val source = folder.findFile(file.name) ?: throw IOException("Falta ${file.name} na pasta")
            val part = File(dir, file.name + ".part")
            context.contentResolver.openInputStream(source.uri).use { input ->
                requireNotNull(input) { "Não consegui ler ${file.name}" }
                part.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            verifyAndPlace(part, file, File(dir, file.name))
        }
    }

    private fun fetch(url: String, part: File, progress: (Long) -> Unit) {
        var location = url
        repeat(MAX_REDIRECTS) {
            val connection = URI(location).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            val resumeFrom = part.length()
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            try {
                when (val code = connection.responseCode) {
                    in 300..399 -> {
                        location = URI(location).resolve(connection.getHeaderField("Location")).toString()
                        return@repeat
                    }
                    200, 206 -> {
                        val append = code == 206
                        var written = if (append) resumeFrom else 0L
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(part, append).use { output ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    written += n
                                    progress(written)
                                }
                            }
                        }
                        return
                    }
                    416 -> return // already complete; hash check decides
                    else -> throw IOException("HTTP $code em $location")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Redirecionamentos demais: $url")
    }

    private fun verifyAndPlace(part: File, file: ModelFile, target: File) {
        val actual = sha256(part)
        if (actual != file.sha256) {
            part.delete()
            throw IOException("${file.name}: SHA-256 não confere (arquivo corrompido ou adulterado)")
        }
        if (!part.renameTo(target)) throw IOException("Não consegui salvar ${file.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

        fun hf(name: String, bytes: Long, sha256: String) = ModelFile(name, "$HF/$name", bytes, sha256)
    }
}
