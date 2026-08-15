/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import org.meshtastic.proto.MeshProtos.FromRadio
import java.io.IOException

class StreamApiUnframer(private val callback: Callback) {

    interface Callback {
        fun onFromRadioDecoded(fromRadio: FromRadio)
        fun onDecodingError(exception: Exception)
    }

    companion object {
        private const val MAGIC_BYTE_1 = 0x94.toByte()
        private const val MAGIC_BYTE_2 = 0xC3.toByte()
        private const val INITIAL_BUFFER_SIZE = 8192
        /** Max plausible payload; larger lengths are treated as false magic matches.
         *  16 KB: big LogRecord packets legitimately exceed 8 KB (see tests), while
         *  ~75% of random 16-bit collision lengths are discarded. */
        private const val MAX_PACKET_LENGTH = 16384
    }

    private val lock = Any()
    private var buffer = ByteArray(INITIAL_BUFFER_SIZE)
    private var bytesInBuffer = 0

    /**
     * Feeds incoming serial chunks into the buffer and parses complete FromRadio packets.
     */
    fun addBytes(data: ByteArray) {
        synchronized(lock) {
            // Grow buffer if capacity is insufficient
            if (bytesInBuffer + data.size > buffer.size) {
                var newCapacity = buffer.size * 2
                while (newCapacity < bytesInBuffer + data.size) {
                    newCapacity *= 2
                }
                val newBuffer = ByteArray(newCapacity)
                System.arraycopy(buffer, 0, newBuffer, 0, bytesInBuffer)
                buffer = newBuffer
            }

            // Append incoming data
            System.arraycopy(data, 0, buffer, bytesInBuffer, data.size)
            bytesInBuffer += data.size

            // Process buffer containing potential multiple packets or fragments
            var readIndex = 0
            while (readIndex <= bytesInBuffer - 4) {
                // Scan for the Magic Bytes: 0x94 0xC3
                if (buffer[readIndex] == MAGIC_BYTE_1 && buffer[readIndex + 1] == MAGIC_BYTE_2) {
                    // Extract payload length (Big Endian 16-bit)
                    val lenMsb = buffer[readIndex + 2].toInt() and 0xFF
                    val lenLsb = buffer[readIndex + 3].toInt() and 0xFF
                    val payloadLength = (lenMsb shl 8) or lenLsb

                    // Sanity cap: an accidental 0x94 0xC3 collision with an
                    // absurd length must be discarded, not awaited forever.
                    if (payloadLength > MAX_PACKET_LENGTH) {
                        readIndex++
                        continue
                    }

                    val totalPacketLength = payloadLength + 4
                    if (readIndex + totalPacketLength <= bytesInBuffer) {
                        // Complete packet has arrived
                        val payload = ByteArray(payloadLength)
                        System.arraycopy(buffer, readIndex + 4, payload, 0, payloadLength)

                        try {
                            val fromRadio = FromRadio.parseFrom(payload)
                            callback.onFromRadioDecoded(fromRadio)
                        } catch (e: Exception) {
                            callback.onDecodingError(e)
                        }

                        // Shift readIndex past the processed packet
                        readIndex += totalPacketLength
                    } else {
                        // Packet payload is not fully received yet; stop parsing and wait
                        break
                    }
                } else {
                    // Current byte is not magic header; discard it and look at next
                    readIndex++
                }
            }

            // Consolidate the buffer by sliding remaining bytes to index 0
            if (readIndex > 0) {
                if (readIndex < bytesInBuffer) {
                    val remainingBytes = bytesInBuffer - readIndex
                    System.arraycopy(buffer, readIndex, buffer, 0, remainingBytes)
                    bytesInBuffer = remainingBytes
                } else {
                    bytesInBuffer = 0
                }
            }
        }
    }

    /**
     * Clears current state buffer.
     */
    fun reset() {
        synchronized(lock) {
            bytesInBuffer = 0
        }
    }
}
