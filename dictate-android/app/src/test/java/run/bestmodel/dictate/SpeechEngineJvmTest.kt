package run.bestmodel.dictate

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real [SpeechEngine] (Parakeet v3 + Silero VAD) against recorded speech, fed in
 * 100 ms blocks exactly like [Recorder] does. Skipped unless the model, audio and the Linux
 * JNI build of sherpa-onnx are passed in (see app/build.gradle.kts).
 */
class SpeechEngineJvmTest {

    @Test
    fun `pt en es speech comes out as clean text`() {
        val modelDir = System.getProperty("dictate.modelDir")?.let(::File)
        val audioFile = System.getProperty("dictate.audio")?.let(::File)
        assumeTrue("model/audio not provided", modelDir?.isDirectory == true && audioFile?.isFile == true)

        val bytes = audioFile!!.readBytes()
        val audio = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(audio)

        val engine = SpeechEngine(modelDir!!)
        val live = mutableListOf<String>()
        val session = engine.newSession { live += it }
        val started = System.nanoTime()
        audio.toList().chunked(SpeechEngine.SAMPLE_RATE / 10).forEach { session.accept(it.toFloatArray()) }
        val text = TextPolisher.polish(session.finish())
        val seconds = (System.nanoTime() - started) / 1e9
        engine.release()

        println("live segments: ${live.size}")
        live.forEach { println("  | $it") }
        println("final: $text")
        println("audio %.1fs processed in %.1fs (RTF %.3f)".format(audio.size / 16000f, seconds, seconds / (audio.size / 16000f)))

        assertTrue("segments arrive while speaking", live.size >= 3)
        val lower = text.lowercase()
        listOf("viviam unidos", "com teto de telhas", "ask not what your country", "no preguntes")
            .forEach { assertTrue("missing '$it' in: $text", it in lower) }
        assertTrue("breath split is joined, not capitalized", "tosca, com teto" in text)
    }
}
