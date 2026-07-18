package com.deftmartian.runway

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
}
