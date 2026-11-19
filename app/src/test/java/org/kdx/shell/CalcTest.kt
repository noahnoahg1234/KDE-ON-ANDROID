package org.kdx.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kdx.shell.core.Calc
import org.kdx.shell.core.Units

class CalcTest {
    private fun ev(s: String) = Calc.eval(s)

    @Test fun arithmetic() {
        assertEquals(14.0, ev("2+3*4"), 1e-9)
        assertEquals(20.0, ev("(2+3)*4"), 1e-9)
        assertEquals(1024.0, ev("2^10"), 1e-9)
        assertEquals(512.0, ev("2^3^2"), 1e-9) // right associative
        assertEquals(-4.0, ev("-2*2"), 1e-9)
        assertEquals(1.0, ev("10 % 3"), 1e-9)
        assertEquals(120.0, ev("5!"), 1e-9)
        assertEquals(2.5, ev("5 ÷ 2"), 1e-9)
        assertEquals(6.0, ev("2×3"), 1e-9)
    }

    @Test fun functionsAndConstants() {
        assertEquals(9.0, ev("sqrt(81)"), 1e-9)
        assertEquals(Math.PI * 2, ev("2pi"), 1e-9)
        assertEquals(1.0, Calc.eval("sin(90)", degrees = true), 1e-9)
        assertEquals(3.0, ev("log(1000)"), 1e-9)
        assertEquals(1.5e3, ev("1.5e3"), 1e-9)
    }

    @Test fun errors() {
        assertNull(Calc.tryEval("2+"))
        assertNull(Calc.tryEval("hello world"))
        assertNull(Calc.tryEval("(1+2"))
    }

    @Test fun formatting() {
        assertEquals("4", Calc.format(4.0))
        assertEquals("0.3333333333", Calc.format(1.0 / 3))
    }

    @Test fun units() {
        assertEquals("10 km = 6.2137 mi", Units.convert("10 km to mi"))
        assertEquals("100 °C = 212 °F", Units.convert("100 c in f"))
        assertEquals("1 lb = 0.4536 kg", Units.convert("1 lb kg"))
        assertNull(Units.convert("10 km to kg"))
    }
}
