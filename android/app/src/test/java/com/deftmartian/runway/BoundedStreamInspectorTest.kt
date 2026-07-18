package com.deftmartian.runway

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedStreamInspectorTest {
    @Test
    fun `counts a stream without retaining its contents`() {
        val input = ByteArrayInputStream(ByteArray(256))

        assertEquals(256L, BoundedStreamInspector.countBytes(input, 512))
    }

    @Test(expected = PayloadTooLargeException::class)
    fun `fails immediately after the configured bound`() {
        val input = ByteArrayInputStream(ByteArray(513))

        BoundedStreamInspector.countBytes(input, 512)
    }

    @Test
    fun `reads bytes at the exact bound`() {
        val input = ByteArrayInputStream("12345".encodeToByteArray())

        assertEquals("12345", BoundedStreamInspector.readBytes(input, 5).decodeToString())
    }

    @Test(expected = PayloadTooLargeException::class)
    fun `byte reads fail immediately after the configured bound`() {
        val input = ByteArrayInputStream("123456".encodeToByteArray())

        BoundedStreamInspector.readBytes(input, 5)
    }
}
