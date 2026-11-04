package com.mg.player.data.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class FormParserTest {

    @Test
    fun `dekodiert Formulardaten`() {
        val form = QrSetupServer.parseForm("t=abc&server=http%3A%2F%2Fsrv%3A8080&user=M%C3%BCller&pass=a+b")
        assertEquals("abc", form["t"])
        assertEquals("http://srv:8080", form["server"])
        assertEquals("Müller", form["user"])
        assertEquals("a b", form["pass"])
    }
}
