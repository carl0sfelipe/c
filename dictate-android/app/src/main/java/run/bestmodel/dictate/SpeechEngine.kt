package run.bestmodel.dictate

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Parakeet TDT 0.6B v3 (PT/EN/ES auto, punctuation) + Silero VAD.
 *
 * Each pause the VAD detects becomes a segment that is decoded right away, so text shows up
 * while you are still talking. Segments are cut from the full recording with a margin
 * around them: without it the VAD clips soft words at the edges (seen in testing: "com").
 */
class SpeechEngine(modelDir: File, numThreads: Int = 4) {

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").path,
                    decoder = File(modelDir, "decoder.int8.onnx").path,
                    joiner = File(modelDir, "joiner.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = numThreads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    private val vadConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = File(modelDir, "silero_vad.onnx").path,
            threshold = 0.5f,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            windowSize = VAD_WINDOW,
            maxSpeechDuration = 20f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
    )

    fun newSession(onSegment: (String) -> Unit): Session = Session(onSegment)

    fun release() = recognizer.release()

    fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** One recording. Not thread-safe: feed and finish from the same worker thread. */
    inner class Session(private val onSegment: (String) -> Unit) {
        private val vad = Vad(config = vadConfig)
        private var audio = FloatArray(SAMPLE_RATE * 30)
        private var size = 0
        private var fedToVad = 0
        private var lastPaddedEnd = 0
        val segments = mutableListOf<String>()

        fun accept(pcm: FloatArray) {
            if (size + pcm.size > audio.size) {
                audio = audio.copyOf(max(audio.size * 2, size + pcm.size))
            }
            pcm.copyInto(audio, size)
            size += pcm.size
            while (size - fedToVad >= VAD_WINDOW) {
                vad.acceptWaveform(audio.copyOfRange(fedToVad, fedToVad + VAD_WINDOW))
                fedToVad += VAD_WINDOW
            }
            drain()
        }

        fun finish(): List<String> {
            vad.flush()
            drain()
            vad.release()
            return segments
        }

        val durationSeconds: Float get() = size.toFloat() / SAMPLE_RATE

        private fun drain() {
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                val start = max(segment.start - PAD, lastPaddedEnd)
                val end = min(segment.start + segment.samples.size + PAD, size)
                lastPaddedEnd = end
                if (end - start < SAMPLE_RATE / 5) continue
                val text = decode(audio.copyOfRange(start, end))
                if (text.isNotEmpty()) {
                    segments += text
                    onSegment(text)
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW = 512
        private const val PAD = (0.35 * SAMPLE_RATE).toInt()
    }
}
