/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import org.meshtastic.proto.MeshProtos.ToRadio

object StreamApiFramer {
    private const val START_BYTE_1 = 0x94.toByte()
    private const val START_BYTE_2 = 0xC3.toByte()

    /**
     * Frames a ToRadio message for transmission over USB serial.
     *
     * Format:
     * - Byte 0: 0x94 (Magic Byte 1)
     * - Byte 1: 0xC3 (Magic Byte 2)
     * - Byte 2: Payload Length MSB (Big Endian)
     * - Byte 3: Payload Length LSB (Big Endian)
     * - Bytes 4+: Serialized ToRadio protobuf payload
     *
     * @param toRadio The ToRadio message to frame.
     * @return The framed byte array.
     */
    fun frameToRadio(toRadio: ToRadio): ByteArray {
        val payload = toRadio.toByteArray()
        val length = payload.size
        
        val framed = ByteArray(length + 4)
        framed[0] = START_BYTE_1
        framed[1] = START_BYTE_2
        framed[2] = ((length ushr 8) and 0xFF).toByte()
        framed[3] = (length and 0xFF).toByte()
        
        System.arraycopy(payload, 0, framed, 4, length)
        return framed
    }
}
