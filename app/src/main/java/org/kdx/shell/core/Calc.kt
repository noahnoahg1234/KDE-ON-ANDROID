package org.kdx.shell.core

import kotlin.math.*

/**
 * Small recursive-descent expression evaluator shared by KCalc and KRunner.
 * Supports + - * / % ^, parentheses, unary minus, constants (pi, e) and
 * common functions (sin, cos, tan, asin, acos, atan, sqrt, ln, log, abs, exp, floor, ceil, round).
 */
object Calc {
    class ParseException(msg: String) : Exception(msg)

    fun eval(input: String, degrees: Boolean = false): Double {
        val p = Parser(
            input.replace('×', '*').replace('÷', '/').replace('−', '-').replace("π", "pi").trim(),
            degrees,
        )
        val v = p.expr()
        p.skip()
        if (p.pos < p.s.length) throw ParseException("Unexpected '${p.s[p.pos]}'")
        return v
    }

    fun tryEval(input: String): Double? = try {
        val v = eval(input)
        if (v.isNaN()) null else v
    } catch (_: Exception) {
        null
    }

    fun format(v: Double): String {
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        val s = "%.10g".format(v).trimEnd('0').trimEnd('.')
        return if (s.contains('e')) s else s
    }

    private class Parser(val s: String, val degrees: Boolean) {
        var pos = 0

        fun skip() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun eat(c: Char): Boolean {
            skip()
            if (pos < s.length && s[pos] == c) {
                pos++; return true
            }
            return false
        }

        fun expr(): Double {
            var v = term()
            while (true) {
                v = when {
                    eat('+') -> v + term()
                    eat('-') -> v - term()
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = factor()
            while (true) {
                v = when {
                    eat('*') -> v * factor()
                    eat('/') -> v / factor()
                    eat('%') -> v % factor()
                    else -> {
                        // implicit multiplication: 2pi, 3(4+1)
                        skip()
                        if (pos < s.length && (s[pos] == '(' || s[pos].isLetter())) v * factor() else return v
                    }
                }
            }
        }

        fun factor(): Double {
            if (eat('-')) return -factor()
            if (eat('+')) return factor()
            val base = postfix()
            return if (eat('^')) base.pow(factor()) else base
        }

        fun postfix(): Double {
            var v = atom()
            while (eat('!')) {
                if (v < 0 || v != floor(v) || v > 170) throw ParseException("Bad factorial")
                var r = 1.0
                for (i in 2..v.toInt()) r *= i
                v = r
            }
            return v
        }

        fun atom(): Double {
            skip()
            if (eat('(')) {
                val v = expr()
                if (!eat(')')) throw ParseException("Missing )")
                return v
            }
            val start = pos
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E') &&
                    pos + 1 < s.length && (s[pos + 1].isDigit() || s[pos + 1] == '-' || s[pos + 1] == '+')
                ) {
                    pos += 2
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                return s.substring(start, pos).toDoubleOrNull() ?: throw ParseException("Bad number")
            }
            if (pos < s.length && s[pos].isLetter()) {
                while (pos < s.length && s[pos].isLetterOrDigit()) pos++
                val name = s.substring(start, pos).lowercase()
                when (name) {
                    "pi" -> return PI
                    "e" -> return E
                    "tau" -> return 2 * PI
                }
                if (!eat('(')) throw ParseException("Unknown name $name")
                val a = expr()
                if (!eat(')')) throw ParseException("Missing )")
                val rad = if (degrees) Math.toRadians(a) else a
                fun inv(x: Double) = if (degrees) Math.toDegrees(x) else x
                return when (name) {
                    "sin" -> sin(rad)
                    "cos" -> cos(rad)
                    "tan" -> tan(rad)
                    "asin" -> inv(asin(a))
                    "acos" -> inv(acos(a))
                    "atan" -> inv(atan(a))
                    "sqrt" -> sqrt(a)
                    "cbrt" -> cbrt(a)
                    "ln" -> ln(a)
                    "log" -> log10(a)
                    "log2" -> log2(a)
                    "abs" -> abs(a)
                    "exp" -> exp(a)
                    "floor" -> floor(a)
                    "ceil" -> ceil(a)
                    "round" -> a.roundToLong().toDouble()
                    else -> throw ParseException("Unknown function $name")
                }
            }
            throw ParseException("Unexpected end")
        }
    }
}

/** KRunner-style unit conversion: "10 km to mi", "72 f in c", "5 lb kg". */
object Units {
    private val length = mapOf(
        "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
        "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344,
    )
    private val mass = mapOf(
        "mg" to 1e-6, "g" to 0.001, "kg" to 1.0, "t" to 1000.0, "oz" to 0.028349523125, "lb" to 0.45359237,
    )
    private val volume = mapOf(
        "ml" to 0.001, "l" to 1.0, "gal" to 3.785411784, "qt" to 0.946352946, "cup" to 0.2365882365, "floz" to 0.0295735295625,
    )
    private val data = mapOf(
        "b" to 1.0, "kb" to 1e3, "mb" to 1e6, "gb" to 1e9, "tb" to 1e12,
        "kib" to 1024.0, "mib" to 1048576.0, "gib" to 1073741824.0,
    )
    private val speed = mapOf("kmh" to 1 / 3.6, "mph" to 0.44704, "ms" to 1.0, "kn" to 0.514444)
    private val tables = listOf(length, mass, volume, data, speed)
    private val temps = setOf("c", "f", "k")

    private val re = Regex("""^\s*(-?[\d.]+)\s*([a-zA-Z/]+)\s*(?:to|in|->|=)?\s*([a-zA-Z/]+)\s*$""")

    fun convert(query: String): String? {
        val m = re.matchEntire(query) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = norm(m.groupValues[2])
        val to = norm(m.groupValues[3])
        if (from in temps && to in temps) {
            val k = when (from) { "c" -> value + 273.15; "f" -> (value - 32) * 5 / 9 + 273.15; else -> value }
            val r = when (to) { "c" -> k - 273.15; "f" -> (k - 273.15) * 9 / 5 + 32; else -> k }
            return "${Calc.format(value)} °${from.uppercase()} = ${Calc.format(round2(r))} °${to.uppercase()}"
        }
        for (t in tables) {
            val a = t[from] ?: continue
            val b = t[to] ?: continue
            return "${Calc.format(value)} ${m.groupValues[2]} = ${Calc.format(round2(value * a / b))} ${m.groupValues[3]}"
        }
        return null
    }

    private fun round2(v: Double) = (v * 10000).roundToLong() / 10000.0

    private fun norm(u: String) = when (val l = u.lowercase().replace("/", "")) {
        "meter", "meters", "metre" -> "m"
        "kilometer", "kilometers" -> "km"
        "mile", "miles" -> "mi"
        "foot", "feet" -> "ft"
        "inch", "inches" -> "in"
        "pound", "pounds", "lbs" -> "lb"
        "kilogram", "kilograms", "kgs" -> "kg"
        "gram", "grams" -> "g"
        "liter", "liters", "litre" -> "l"
        "celsius" -> "c"
        "fahrenheit" -> "f"
        "kelvin" -> "k"
        "kph" -> "kmh"
        else -> l
    }
}
