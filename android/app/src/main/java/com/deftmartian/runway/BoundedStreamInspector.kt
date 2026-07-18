package com.deftmartian.runway

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class PayloadTooLargeException(
    val maximumBytes: Long,
    val observedPrefixSha256: String? = null,
) : Exception()

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
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read == -1) return output.toByteArray()
            if (read == 0) continue
            if (total + read > maximumBytes) {
                val prefixBytes = (maximumBytes - total + 1).toInt()
                digest.update(buffer, 0, prefixBytes)
                throw PayloadTooLargeException(
                    maximumBytes = maximumBytes,
                    observedPrefixSha256 = digest.digest().toHex(),
                )
            }
            digest.update(buffer, 0, read)
            total += read
            output.write(buffer, 0, read)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
