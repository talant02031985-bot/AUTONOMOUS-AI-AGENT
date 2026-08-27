package kg.autonomous.agent

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/**
 * AYANA Local Expression Calculator v1.0.
 * Pure Kotlin, no Android/network dependency.
 * Supports + - * /, parentheses, unary signs and Russian operator phrases.
 */
object AyanaLocalExpressionCalculator {

    data class Result(
        val success: Boolean,
        val valueText: String = "",
        val error: String = ""
    )

    fun evaluate(raw: String): Result? {
        val prepared = prepare(raw) ?: return null
        return try {
            val parser = Parser(prepared)
            val value = parser.parseExpression()
            parser.skipSpaces()
            if (!parser.atEnd()) {
                return null
            }
            Result(
                success = true,
                valueText = format(value)
            )
        } catch (zero: ArithmeticException) {
            Result(false, error = "На ноль делить нельзя.")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun prepare(raw: String): String? {
        var s = raw
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .trim()

        s = s.replace(
            Regex("^(?:(?:(?:сколько|что|то)\\s+)?будет|сколько|посчитай|вычисли)\\s+"),
            ""
        )

        s = s
            .replace("умножить на", "*")
            .replace("умножь на", "*")
            .replace("разделить на", "/")
            .replace("поделить на", "/")
            .replace("плюс", "+")
            .replace("минус", "-")
            .replace('×', '*')
            .replace('x', '*')
            .replace('х', '*')
            .replace(',', '.')
            .replace(Regex("\\s+"), "")

        if (s.isBlank()) return null
        if (!s.matches(Regex("[0-9.+\\-*/()]+"))) return null
        if (!s.any { it.isDigit() }) return null
        return s
    }

    private fun format(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        val text = normalized.toPlainString()
        return text.replace('.', ',')
    }

    private class Parser(
        private val source: String
    ) {
        private var index = 0
        private val mc = MathContext.DECIMAL128

        fun atEnd(): Boolean = index >= source.length

        fun skipSpaces() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                value = when {
                    consume('+') -> value.add(parseTerm(), mc)
                    consume('-') -> value.subtract(parseTerm(), mc)
                    else -> return value
                }
            }
        }

        private fun parseTerm(): BigDecimal {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                value = when {
                    consume('*') -> value.multiply(parseFactor(), mc)
                    consume('/') -> {
                        val divisor = parseFactor()
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                            throw ArithmeticException("division by zero")
                        }
                        value.divide(divisor, mc)
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): BigDecimal {
            skipSpaces()
            if (consume('+')) return parseFactor()
            if (consume('-')) return parseFactor().negate(mc)

            if (consume('(')) {
                val value = parseExpression()
                if (!consume(')')) throw IllegalArgumentException("missing )")
                return value
            }

            return parseNumber()
        }

        private fun parseNumber(): BigDecimal {
            skipSpaces()
            val start = index
            var dotSeen = false
            while (index < source.length) {
                val ch = source[index]
                if (ch.isDigit()) {
                    index++
                    continue
                }
                if (ch == '.' && !dotSeen) {
                    dotSeen = true
                    index++
                    continue
                }
                break
            }
            if (start == index) throw IllegalArgumentException("number expected")
            val token = source.substring(start, index)
            if (token == ".") throw IllegalArgumentException("bad number")
            return token.toBigDecimalOrNull() ?: throw IllegalArgumentException("bad number")
        }

        private fun consume(expected: Char): Boolean {
            skipSpaces()
            if (index < source.length && source[index] == expected) {
                index++
                return true
            }
            return false
        }
    }
}
