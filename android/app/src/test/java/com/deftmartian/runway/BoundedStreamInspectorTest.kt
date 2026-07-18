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
}
