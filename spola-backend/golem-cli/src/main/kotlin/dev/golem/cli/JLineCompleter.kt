package dev.spola.cli

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

class JLineCompleter : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val buffer = line.line()
        when {
            buffer.startsWith("/model ") -> {
                val partial = buffer.removePrefix("/model ")
                MODEL_CANDIDATES
                    .filter { it.startsWith(partial) }
                    .forEach { candidates.add(Candidate(it)) }
            }

            buffer.startsWith("/provider ") -> {
                val partial = buffer.removePrefix("/provider ")
                PROVIDER_CANDIDATES
                    .filter { it.startsWith(partial) }
                    .forEach { candidates.add(Candidate(it)) }
            }

            buffer.startsWith("/") -> {
                val partial = buffer.removePrefix("/")
                SLASH_COMMANDS.keys
                    .filter { it.startsWith(partial) }
                    .map { "/$it" }
                    .forEach { candidates.add(Candidate(it)) }
            }
        }
    }

    private companion object {
        val MODEL_CANDIDATES = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-3.5-turbo",
            "claude-sonnet-4-20250514",
            "claude-3-opus-latest",
            "claude-3-haiku-20240307",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "llama3",
            "llama2",
            "mistral",
            "codellama",
            "mixtral",
        )

        val PROVIDER_CANDIDATES = listOf(
            "openai",
            "anthropic",
            "openai-compat",
            "ollama",
            "google",
        )
    }
}
