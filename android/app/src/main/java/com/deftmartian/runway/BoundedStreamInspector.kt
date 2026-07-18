package com.deftmartian.runway

import java.io.ByteArrayOutputStream
import java.io.InputStream

class PayloadTooLargeException(val maximumBytes: Long) : Exception()

object BoundedStreamInspector {
    fun countBytes(input: InputStream, maximumBytes: Long): Long {
        require(maximumBytes > 0)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read == -1) return total
            if (read == 0) {
                val oneByte = input.read()
                if (oneByte == -1) return total
                total += 1
            } else {
                total += read
            }
            if (total > maximumBytes) throw PayloadTooLargeException(maximumBytes)
        }
    }

    fun readBytes(input: InputStream, maximumBytes: Long): ByteArray {
        require(maximumBytes > 0)
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read == -1) return output.toByteArray()
            if (read == 0) continue
            total += read
            if (total > maximumBytes) throw PayloadTooLargeException(maximumBytes)
            output.write(buffer, 0, read)
        }
    }
}
