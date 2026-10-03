package run.bestmodel.dictate

/**
 * Turns the per-pause segments the recognizer returns into one clean piece of text.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object TextPolisher {

    data class Options(
        val removeFillers: Boolean = true,
        val voiceCommands: Boolean = true,
    )

    // Hesitations only. "um", "eh" and "ah" are real words in PT/ES, so they stay.
    private val FILLERS = Regex(
        """(?i),?\s*(?<![\p{L}\p{N}])(uh+m*|uhm+|hum+|hmm+|m{3,}|h?ã+|é{2,}|e{2,}h*|eh{2,})(?![\p{L}\p{N}])[,…]*""",
    )

    private val NEW_PARAGRAPH = Regex(
        """(?i),?\s*\b(novo parágrafo|new paragraph|nuevo párrafo)\b[,.!]?\s*""",
    )
    private val NEW_LINE = Regex(
        """(?i),?\s*\b(nova linha|new line|nueva línea|nueva linea)\b[,.!]?\s*""",
    )

    fun polish(segments: List<String>, options: Options = Options()): String {
        val joined = join(segments.map { it.trim() }.filter { it.isNotEmpty() })
        var text = joined
        if (options.removeFillers) text = FILLERS.replace(text, "")
        if (options.voiceCommands) {
            text = NEW_PARAGRAPH.replace(text, "\n\n")
            text = NEW_LINE.replace(text, "\n")
        }
        return tidy(text)
    }

    /**
     * Joins segments split at a breath. When the previous segment did not end a sentence
     * (no . ! ?), the next one is a continuation: lowercase its first letter.
     */
    internal fun join(segments: List<String>): String {
        val out = StringBuilder()
        for (segment in segments) {
            if (out.isEmpty()) {
                out.append(segment)
                continue
            }
            val endsSentence = out.trimEnd().lastOrNull()?.let { it in ".!?…:" } ?: true
            val next = if (!endsSentence && startsWithCapitalizedWord(segment)) {
                segment.replaceFirstChar { it.lowercaseChar() }
            } else {
                segment
            }
            out.append(' ').append(next)
        }
        return out.toString()
    }

    // "I", acronyms ("NASA") and the like keep their case.
    private fun startsWithCapitalizedWord(segment: String): Boolean {
        val word = segment.takeWhile { it.isLetter() }
        return word.length > 1 && word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }
    }

    private fun tidy(text: String): String {
        val lines = text.split('\n').map { line ->
            line.replace(Regex("""[ \t]+"""), " ")
                .replace(Regex("""\s+([,.!?;:…])"""), "$1")
                .replace(Regex("""([,;:])\1+"""), "$1")
                .replace(Regex("""^[\s,.;:…]+"""), "")
                .trim()
                .replaceFirstChar { it.uppercaseChar() }
        }
        return lines.joinToString("\n").trim()
    }
}
