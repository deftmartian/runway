package com.deftmartian.runway

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `byte reads fail with a stable bounded prefix identity`() {
        val input = ByteArrayInputStream("123456".encodeToByteArray())

        val first = runCatching { BoundedStreamInspector.readBytes(input, 5) }
            .exceptionOrNull() as PayloadTooLargeException
        val second = runCatching {
            BoundedStreamInspector.readBytes(ChunkedInput("123456".encodeToByteArray(), 2), 5)
        }.exceptionOrNull() as PayloadTooLargeException

        assertNotNull(first.observedPrefixSha256)
        assertEquals(64, first.observedPrefixSha256?.length)
        assertEquals(first.observedPrefixSha256, second.observedPrefixSha256)
    }

    private class ChunkedInput(
        private val bytes: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int = if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 0xff

        override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - offset)
            bytes.copyInto(buffer, targetOffset, offset, offset + count)
            offset += count
            return count
        }
    }
}
