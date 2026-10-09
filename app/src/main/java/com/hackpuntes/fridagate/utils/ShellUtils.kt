package com.hackpuntes.fridagate.utils

/**
 * ShellUtils - Helpers to build shell commands safely.
 *
 * Every value that ends up inside a root command (paths, IPs, package names,
 * user-typed flags...) goes through [quote]. A single-quoted string is taken
 * literally by sh, so characters like ; | & $ ` or spaces can't change the command.
 */
object ShellUtils {

    /**
     * Wraps [value] in single quotes for POSIX sh.
     * A single quote inside the value becomes '\'' (close quote, escaped quote, reopen).
     *
     *   quote("it's")  ->  'it'\''s'
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Splits a user-typed argument string into separate arguments using the basic
     * sh rules people expect: whitespace separates arguments, '...' and "..." group
     * text (spaces included), and a backslash escapes the next character outside
     * single quotes.
     *
     *   splitArgs("-l 0.0.0.0:27042 --token='my secret'")
     *     -> ["-l", "0.0.0.0:27042", "--token=my secret"]
     *
     * Nothing is expanded or executed: $VAR, `cmd`, ; and | stay as literal text
     * and are later passed through [quote].
     *
     * @throws IllegalArgumentException if a quote is left open
     */
    fun splitArgs(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inArg = false
        var openQuote: Char? = null
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                openQuote == '\'' -> {
                    if (c == '\'') openQuote = null else current.append(c)
                }
                openQuote == '"' -> {
                    if (c == '"') {
                        openQuote = null
                    } else if (c == '\\' && i + 1 < input.length && input[i + 1] in "\"\\$`") {
                        current.append(input[i + 1])
                        i++
                    } else {
                        current.append(c)
                    }
                }
                c == '\'' || c == '"' -> {
                    openQuote = c
                    inArg = true
                }
                c == '\\' && i + 1 < input.length -> {
                    current.append(input[i + 1])
                    i++
                    inArg = true
                }
                c.isWhitespace() -> {
                    if (inArg) {
                        args += current.toString()
                        current.clear()
                        inArg = false
                    }
                }
                else -> {
                    current.append(c)
                    inArg = true
                }
            }
            i++
        }
        require(openQuote == null) { "Unclosed $openQuote quote" }
        if (inArg) args += current.toString()
        return args
    }
}
