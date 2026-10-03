package run.bestmodel.dictate

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Reads 16 kHz mono PCM in 100 ms blocks on its own thread and hands them over as floats.
 * Decoding happens elsewhere, so a slow decode never makes the mic drop audio.
 */
class Recorder(private val onBlock: (FloatArray, Float) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller before starting the service
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, RATE * 2 * 2), // 2 s of slack
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microfone indisponível")
        }
        running = true
        record.startRecording()
        thread = Thread({
            val block = ShortArray(RATE / 10)
            try {
                while (running) {
                    val n = record.read(block, 0, block.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = block[i] / 32768f
                        samples[i] = v
                        sum += v * v
                    }
                    onBlock(samples, sqrt(sum / n).toFloat())
                }
            } finally {
                record.stop()
                record.release()
            }
        }, "dictate-mic").apply { start() }
    }

    /** Stops and waits for the last block to be delivered. */
    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    private companion object {
        const val RATE = SpeechEngine.SAMPLE_RATE
    }
}
