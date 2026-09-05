package com.convoyrama.convoyrun.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class CtesEnvelopeInteropTest {
    private val json = Json { ignoreUnknownKeys = false }

    private fun envelopeFor(documentJson: String): String {
        return JsonObject(
            mapOf(
                "protocol" to JsonPrimitive("ctes-gossip/1"),
                "type" to JsonPrimitive("document"),
                "document" to json.parseToJsonElement(documentJson),
            )
        ).toString()
    }

    private fun kindOf(documentJson: String): String {
        return json.parseToJsonElement(documentJson).jsonObject["kind"]!!.jsonPrimitive.content
    }

    @Test
    fun parsesAndClassifiesCoreDocumentsFromTheCTESEnvelope() {
        val documents = listOf(
            "event" to checkNotNull(javaClass.getResource("/ctes-v1/event.json")).readText(),
            "profile" to checkNotNull(javaClass.getResource("/ctes-v1/profile.json")).readText(),
            "vote" to checkNotNull(javaClass.getResource("/ctes-v1/vote-up-r1.json")).readText(),
            "tombstone" to checkNotNull(javaClass.getResource("/ctes-v1/tombstone-r2.json")).readText(),
        )

        for ((expectedKind, source) in documents) {
            val envelope = envelopeFor(source)
            val message = parseGossipMessage(envelope)

            when (message) {
                is GossipMessage.Convoy -> assertEquals(expectedKind, kindOf(message.data))
                is GossipMessage.Profile -> assertEquals(expectedKind, kindOf(message.data))
                is GossipMessage.Vote -> assertEquals(expectedKind, kindOf(message.data))
                is GossipMessage.Tombstone -> {
                    assertEquals(expectedKind, kindOf(source))
                    val document = json.parseToJsonElement(source).jsonObject
                    assertEquals(document["eventId"]!!.jsonPrimitive.content, message.convoyId)
                    assertEquals(document["authorId"]!!.jsonPrimitive.content, message.peerId)
                    assertEquals(document["revision"]!!.jsonPrimitive.long, message.revision)
                    assertEquals(document["signature"]!!.jsonPrimitive.content, message.signature)
                }
                else -> throw AssertionError("Unexpected message type for $expectedKind")
            }
        }
    }
}
