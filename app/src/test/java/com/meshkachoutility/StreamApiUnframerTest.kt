/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.LogRecord

class StreamApiUnframerTest {

    private class RecordingCallback : StreamApiUnframer.Callback {
        val decoded = mutableListOf<FromRadio>()
        val errors = mutableListOf<Exception>()

        override fun onFromRadioDecoded(fromRadio: FromRadio) {
            decoded.add(fromRadio)
        }

        override fun onDecodingError(exception: Exception) {
            errors.add(exception)
        }
    }

    private fun frame(fromRadio: FromRadio): ByteArray {
        val payload = fromRadio.toByteArray()
        return frameRaw(payload)
    }

    private fun frameRaw(payload: ByteArray): ByteArray {
        val framed = ByteArray(payload.size + 4)
        framed[0] = 0x94.toByte()
        framed[1] = 0xC3.toByte()
        framed[2] = ((payload.size ushr 8) and 0xFF).toByte()
        framed[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, framed, 4, payload.size)
        return framed
    }

    private fun samplePacket(id: Int): FromRadio =
        FromRadio.newBuilder().setId(id).build()

    private fun feedInChunks(
        unframer: StreamApiUnframer,
        data: ByteArray,
        chunkSize: Int,
        callback: RecordingCallback
    ) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            unframer.addBytes(data.copyOfRange(offset, end))
            offset = end
        }
    }

    @Test
    fun singleCompletePacket_isDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(7)

        unframer.addBytes(frame(packet))

        assertEquals(1, callback.decoded.size)
        assertEquals(7, callback.decoded[0].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun fragmentedPacket_isAssembledAndDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(42)
        val data = frame(packet)

        feedInChunks(unframer, data, chunkSize = 3, callback = callback)

        assertEquals(1, callback.decoded.size)
        assertEquals(42, callback.decoded[0].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun fragmentedPacketMagicBytesSplitAcrossChunks_isDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(99)
        val data = frame(packet)

        feedInChunks(unframer, data, chunkSize = 1, callback = callback)

        assertEquals(1, callback.decoded.size)
        assertEquals(99, callback.decoded[0].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun twoPacketsInOneChunk_areBothDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)

        val first = frame(samplePacket(1))
        val second = frame(samplePacket(2))
        unframer.addBytes(first + second)

        assertEquals(2, callback.decoded.size)
        assertEquals(1, callback.decoded[0].id)
        assertEquals(2, callback.decoded[1].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun interleavedPacketsSplitAcrossFragments_areBothDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)

        val combined = frame(samplePacket(11)) + frame(samplePacket(22))
        feedInChunks(unframer, combined, chunkSize = 5, callback = callback)

        assertEquals(2, callback.decoded.size)
        assertEquals(11, callback.decoded[0].id)
        assertEquals(22, callback.decoded[1].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun incompleteFrame_noCallbackUntilRemainderArrives() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(5)
        val data = frame(packet)

        unframer.addBytes(data.copyOfRange(0, 4))

        assertTrue(callback.decoded.isEmpty())
        assertTrue(callback.errors.isEmpty())

        unframer.addBytes(data.copyOfRange(4, data.size))

        assertEquals(1, callback.decoded.size)
        assertEquals(5, callback.decoded[0].id)
    }

    @Test
    fun headerOnlyWithoutPayload_waitsForMoreData() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(6)
        val payload = packet.toByteArray()
        val header = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, payload.size.toByte())

        unframer.addBytes(header)

        assertTrue(callback.decoded.isEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun oversizedHeaderLength_isDiscardedAsFalseMagic() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(7)
        // 0x94 0xC3 with an absurd 16-bit length (65535): must be skipped as a
        // false magic collision, not awaited forever.
        val falseHeader = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte(), 0xFF.toByte())
        unframer.addBytes(falseHeader + frame(packet))

        assertEquals(1, callback.decoded.size)
        assertEquals(7, callback.decoded[0].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun oversizedHeaderAlone_doesNotDecodeNorWait() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val falseHeader = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte(), 0xFF.toByte())
        unframer.addBytes(falseHeader)

        assertTrue(callback.decoded.isEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun garbageBeforeMagicBytes_isSkippedAndPacketDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(13)

        val noise = byteArrayOf(0x00, 0xFF.toByte(), 0x01, 0x94.toByte(), 0x00)
        unframer.addBytes(noise + frame(packet))

        assertEquals(1, callback.decoded.size)
        assertEquals(13, callback.decoded[0].id)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun randomNoiseOnly_emitsNoCallbacks() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val noise = ByteArray(4096)
        for (i in noise.indices) {
            noise[i] = ((i * 31 + 7) and 0xFF).toByte()
        }
        // Remove any accidental magic-header byte sequence to keep the test deterministic.
        for (i in 0 until noise.size - 1) {
            if (noise[i] == 0x94.toByte() && noise[i + 1] == 0xC3.toByte()) {
                noise[i + 1] = 0x00.toByte()
            }
        }

        unframer.addBytes(noise)

        assertTrue(callback.decoded.isEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun reset_discardsPendingData() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val packet = samplePacket(8)
        val data = frame(packet)

        unframer.addBytes(data.copyOfRange(0, data.size / 2))
        unframer.reset()
        unframer.addBytes(data.copyOfRange(data.size / 2, data.size))

        assertTrue(callback.decoded.isEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun largePacketBeyondInitialBuffer_isDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val bigMessage = "x".repeat(12000)
        val packet = FromRadio.newBuilder()
            .setLogRecord(
                LogRecord.newBuilder()
                    .setMessage(bigMessage)
                    .setSource("test")
                    .build()
            )
            .build()

        // Serialized payload must exceed the 8192-byte initial buffer to exercise growth.
        assertTrue(packet.toByteArray().size > 8192)

        feedInChunks(unframer, frame(packet), chunkSize = 1024, callback = callback)

        assertEquals(1, callback.decoded.size)
        assertEquals(bigMessage, callback.decoded[0].logRecord.message)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun malformedPayload_triggersErrorAndRecovers() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)
        val garbagePayload = ByteArray(64) { it.toByte() }
        val validPacket = samplePacket(77)

        unframer.addBytes(frameRaw(garbagePayload))
        unframer.addBytes(frame(validPacket))

        assertEquals(1, callback.errors.size)
        assertEquals(1, callback.decoded.size)
        assertEquals(77, callback.decoded[0].id)
    }

    @Test
    fun emptyPayload_isDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)

        unframer.addBytes(frameRaw(ByteArray(0)))

        assertEquals(1, callback.decoded.size)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun manyFragmentedPackets_areAllDecoded() {
        val callback = RecordingCallback()
        val unframer = StreamApiUnframer(callback)

        var stream = ByteArray(0)
        for (id in 0 until 50) {
            stream += frame(samplePacket(id))
        }

        feedInChunks(unframer, stream, chunkSize = 17, callback = callback)

        assertEquals(50, callback.decoded.size)
        for (id in 0 until 50) {
            assertEquals(id, callback.decoded[id].id)
        }
        assertTrue(callback.errors.isEmpty())
    }
}
