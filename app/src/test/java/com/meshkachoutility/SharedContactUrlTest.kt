/* Copyright (c) 2026 Tai Soluciones - taisoluciones@gmail.com */
package com.meshkachoutility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.AdminProtos.SharedContact
import java.util.Base64

/**
 * Verifies the Meshtastic shared-contact URL format
 * (`https://meshtastic.org/v/#<base64url>`): Base64 (URL-safe) decode of the
 * fragment into a SharedContact proto and the PKI-key presence detection used
 * by the import-by-URL feature.
 */
class SharedContactUrlTest {

    private fun urlToBytes(raw: String): ByteArray {
        val fragment = raw.substringAfter("#").substringBefore("?")
        val b64 = fragment.replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(b64)
    }

    @Test
    fun noKeyUrlDecodesToContactWithoutPublicKey() {
        val url = "https://meshtastic.org/v/#CLOn1PEEEiUKCSE0ZTM1MTNiMxIPTWVzaHRhc3RpYyAwMDYxGgQwMDYxKP8B"
        val contact = SharedContact.parseFrom(urlToBytes(url))
        assertTrue(contact.nodeNum != 0)
        assertNotNull(contact.user)
        assertEquals("!4e3513b3", contact.user.id)
        assertEquals("Meshtastic 0061", contact.user.longName)
        assertEquals("0061", contact.user.shortName)
        assertFalse(contact.user.publicKey.size() > 0)
    }

    @Test
    fun keyUrlDecodesToContactWithPublicKey() {
        val url = "https://meshtastic.org/v/#COnt87INEk8KCSFkNjVjZjZlORIMVDEwMDBlIEl2w6FuGgTwn5OxIgbsPdZc9ukoRzgBQiAdiyTOi-uUx4rgKiuybB11CD3mxa2eKILq-XvvhNRHOUgA"
        val contact = SharedContact.parseFrom(urlToBytes(url))
        assertTrue(contact.nodeNum != 0)
        assertNotNull(contact.user)
        assertEquals("!d65cf6e9", contact.user.id)
        assertTrue(contact.user.publicKey.size() == 32)
    }

    @Test
    fun generatedUrlRoundTrips() {
        val contact = SharedContact.newBuilder()
            .setNodeNum(0x12ab)
            .setUser(
                org.meshtastic.proto.MeshProtos.User.newBuilder()
                    .setId("!12ab")
                    .setLongName("Test Node")
                    .setShortName("TN")
                    .setPublicKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32) { (it + 1).toByte() }))
                    .build()
            )
            .build()
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(contact.toByteArray())
        val url = "https://meshtastic.org/v/#$b64"
        val parsed = SharedContact.parseFrom(urlToBytes(url))
        assertEquals(contact.nodeNum, parsed.nodeNum)
        assertEquals(contact.user.longName, parsed.user.longName)
        assertEquals(contact.user.publicKey, parsed.user.publicKey)
    }
}
