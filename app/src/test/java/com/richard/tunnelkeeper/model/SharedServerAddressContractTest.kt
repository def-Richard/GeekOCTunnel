package com.richard.tunnelkeeper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedServerAddressContractTest {
    @Test
    fun `desktop and Android share server address expectations`() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/server-addresses.tsv"))
        var cases = 0
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val fields = line.split('\t')
                assertEquals(2, fields.size)
                val result = ProfileValidator.validate(id = "fixture", name = "Example", server = fields[0])
                if (fields[1] == "!") assertTrue(fields[0], result.isFailure)
                else assertEquals(fields[0], fields[1], result.getOrThrow().server)
                cases++
            }
        }
        assertTrue(cases >= 25)
    }
}
