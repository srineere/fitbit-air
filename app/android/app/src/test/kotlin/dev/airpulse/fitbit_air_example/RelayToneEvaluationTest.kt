package dev.airpulse.fitbit_air_example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Accuracy on a hand-labelled synthetic set; the floors guard against regressions. */
class RelayToneEvaluationTest {
    private data class Example(val label: RelayTone, val text: String)

    private val examples = File("src/test/resources/tone_eval.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t', limit = 2).let { Example(RelayTone.valueOf(it[0].uppercase()), it[1]) } }

    @Test fun `classifier accuracy on the labelled set`() {
        val results = examples.map { it to RelayToneClassifier.classify(it.text, lexicons = RelayVaderTest.lexicons) }
        val wrong = results.filter { (example, actual) -> example.label != actual }
        wrong.forEach { (example, actual) -> println("expected ${example.label.id}, got ${actual.id}: ${example.text}") }
        val accuracy = 1.0 - wrong.size.toDouble() / results.size
        println("accuracy %.1f%% (%d/%d)".format(accuracy * 100, results.size - wrong.size, results.size))
        RelayTone.entries.forEach { tone ->
            val of = results.filter { it.first.label == tone }
            val right = of.count { it.first.label == it.second }
            println("  ${tone.id}: $right/${of.size}")
        }
        assertTrue("accuracy fell to $accuracy", accuracy >= MIN_ACCURACY)
    }

    companion object {
        private const val MIN_ACCURACY = 0.95
    }
}
