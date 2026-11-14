package dev.fulltalk.core

/**
 * Collapses whitespace and drops output that carries no words (the model
 * sometimes emits a lone "." for silence). Returns an empty string in that case.
 */
fun normalize(raw: String): String {
    val joined = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    return if (joined.any { it.isLetterOrDigit() }) joined else ""
}
