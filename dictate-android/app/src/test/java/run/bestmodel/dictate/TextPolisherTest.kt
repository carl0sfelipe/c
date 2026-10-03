package run.bestmodel.dictate

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPolisherTest {

    @Test
    fun `continuation after a comma is lowercased`() {
        // Real output of Parakeet v3 on MLS pt, split by the VAD at a breath.
        val segments = listOf(
            "Viviam unidos em uma só comunhão de desânimo e de espanto, na casinha feita de madeira tosca,",
            "Com teto de telhas de pau, incendiada pelo sol nos dias quentes.",
        )
        assertEquals(
            "Viviam unidos em uma só comunhão de desânimo e de espanto, na casinha feita de madeira tosca, " +
                "com teto de telhas de pau, incendiada pelo sol nos dias quentes.",
            TextPolisher.polish(segments),
        )
    }

    @Test
    fun `new sentence keeps its capital and mixed languages pass through`() {
        val segments = listOf(
            "Ask not what your country can do for you.",
            "No preguntes que puede hacer tu país por ti.",
        )
        assertEquals(
            "Ask not what your country can do for you. No preguntes que puede hacer tu país por ti.",
            TextPolisher.polish(segments),
        )
    }

    @Test
    fun `pronoun I and acronyms are not lowercased`() {
        assertEquals("Then, I said so", TextPolisher.join(listOf("Then,", "I said so")))
        assertEquals("Fale com a NASA hoje", TextPolisher.join(listOf("Fale com a", "NASA hoje")))
    }

    @Test
    fun `fillers are removed but real words stay`() {
        assertEquals(
            "Eu quero um café e um pão.",
            TextPolisher.polish(listOf("Hum, eu quero um café, ã, e um pão.")),
        )
        assertEquals("Ah, sí, eh, claro.", TextPolisher.polish(listOf("Ah, sí, eh, claro.")))
        assertEquals("So we go.", TextPolisher.polish(listOf("Uhm, so, uh, we go.")))
        assertEquals("Eu acho.", TextPolisher.polish(listOf("Eu acho, hum.")))
        assertEquals("Eu acho.", TextPolisher.polish(listOf("Hum. Eu acho.")))
        assertEquals("Um parafuso de 10 mm.", TextPolisher.polish(listOf("Um parafuso de 10 mm.")))
    }

    @Test
    fun `voice commands become line breaks in three languages`() {
        assertEquals(
            "Lista de compras:\nArroz.\n\nOutro assunto.",
            TextPolisher.polish(listOf("Lista de compras: nova linha. Arroz. Novo parágrafo. Outro assunto.")),
        )
        assertEquals("Hello\nWorld.", TextPolisher.polish(listOf("Hello, new line, world.")))
        assertEquals("Hola.\nMundo.", TextPolisher.polish(listOf("Hola. Nueva línea. Mundo.")))
    }

    @Test
    fun `commands can be turned off`() {
        assertEquals(
            "Escreva nova linha aqui.",
            TextPolisher.polish(listOf("Escreva nova linha aqui."), TextPolisher.Options(voiceCommands = false)),
        )
    }

    @Test
    fun `empty input gives empty text`() {
        assertEquals("", TextPolisher.polish(listOf("", "  ")))
    }
}
