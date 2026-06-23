package com.dev.docscannerpdf.ui

fun cleanOcrText(rawText: String): String {
    if (rawText.isBlank()) return ""

    val normalized = rawText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("(?<=\\p{L})-\\n(?=\\p{Ll})"), "")

    val lines = normalized
        .split('\n')
        .map { line ->
            line.replace(Regex("[\\t ]+"), " ")
                .trim()
        }

    val output = mutableListOf<String>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) {
            output += text
        }
        paragraph.clear()
    }

    lines.forEach { line ->
        when {
            line.isBlank() -> flushParagraph()
            line.isListItem() -> {
                flushParagraph()
                output += line
            }
            line.isLikelyHeading() -> {
                flushParagraph()
                output += line
            }
            paragraph.isEmpty() -> paragraph.append(line)
            else -> paragraph.append(' ').append(line)
        }
    }
    flushParagraph()

    return output
        .joinToString(separator = "\n\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun String.isListItem(): Boolean {
    return matches(Regex("""^(\d{1,3}[.)]|[A-Za-z][.)]|[-*\u2022])\s+.+"""))
}

private fun String.isLikelyHeading(): Boolean {
    if (length !in 3..70) return false
    if (endsWith(".") || endsWith(",") || endsWith(";") || endsWith(":")) return false
    if (isListItem()) return false
    val letters = count { it.isLetter() }
    if (letters < 2) return false
    val uppercase = count { it.isLetter() && it.isUpperCase() }
    val words = split(Regex("\\s+")).filter { it.isNotBlank() }
    val titleCaseWords = words.count { word -> word.firstOrNull()?.isUpperCase() == true }
    return uppercase >= letters * 0.65 || titleCaseWords >= (words.size * 0.7).toInt().coerceAtLeast(1)
}
