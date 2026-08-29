package com.convoyrun.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Convoy event data model (matches desktop ConvoyRecord)
 */
@Serializable
data class ConvoyEvent(
    val schema: String = "convoyrun/event/v1",
    val id: String,
    val peerId: String,
    val nickname: String = "",
    val publishedAt: Long,
    val event: EventData,
    val schedule: Schedule,
    val channel: String = "",
    val flyer: FlyerData? = null,
    val signature: String = ""
)

@Serializable
data class EventData(
    val name: String,
    val eventType: EventType = EventType.Convoy,
    val game: Game = Game.ATS,
    val mode: GameMode = GameMode.Simulation,
    val link: String = "",
    val server: String = "",
    val route: Route = Route(),
    val description: String = "",
    val languages: List<String> = emptyList()
)

@Serializable
data class Schedule(
    val meetingTimestamp: Long,
    val ianaTimeZone: String
)

@Serializable
data class Route(
    val startCity: String = "",
    val startLocation: String = "",
    val destCity: String = "",
    val destLocation: String = ""
)

@Serializable
data class FlyerData(
    val url: String = "",
    val size: Long = 0
)

/**
 * Event types (matches desktop EventType enum)
 */
@Serializable
enum class EventType {
    @SerialName("convoy") Convoy,
    @SerialName("truck_show") TruckShow,
    @SerialName("exploration") Exploration,
    @SerialName("competition") Competition,
    @SerialName("cruise") Cruise,
    @SerialName("other") Other
}

/**
 * Games (matches desktop Game enum)
 */
@Serializable
enum class Game {
    ATS, ETS2, Other
}

/**
 * Game modes (matches desktop Mode enum)
 */
@Serializable
enum class GameMode {
    @SerialName("simulation") Simulation,
    @SerialName("realistic") Realistic,
    @SerialName("arcade") Arcade,
    @SerialName("race") Race,
    @SerialName("other") Other
}

/**
 * Gossip message types (matches desktop GossipMessage enum)
 */
sealed class GossipMessage {
    data class Convoy(val data: String) : GossipMessage()
    data class Vote(val data: String) : GossipMessage()
    data class DeleteConvoy(
        val convoyId: String,
        val peerId: String,
        val signature: String
    ) : GossipMessage()
    data class Channel(val data: String) : GossipMessage()
    data class Blacklist(val data: String) : GossipMessage()
    data class Trustlist(val data: String) : GossipMessage()
}

/**
 * Parse a GossipMessage from JSON string
 */
fun parseGossipMessage(json: String): GossipMessage? {
    return try {
        val jsonObj = Json.parseToJsonElement(json).jsonObject
        val type = jsonObj["type"]?.jsonPrimitive?.content

        when (type) {
            "convoy" -> {
                val data = jsonObj["data"]?.jsonPrimitive?.content ?: return null
                GossipMessage.Convoy(data)
            }
            "vote" -> {
                val data = jsonObj["data"]?.jsonPrimitive?.content ?: return null
                GossipMessage.Vote(data)
            }
            "delete_convoy" -> {
                val convoyId = jsonObj["convoy_id"]?.jsonPrimitive?.content ?: return null
                val peerId = jsonObj["peer_id"]?.jsonPrimitive?.content ?: return null
                val signature = jsonObj["signature"]?.jsonPrimitive?.content ?: return null
                GossipMessage.DeleteConvoy(convoyId, peerId, signature)
            }
            "channel" -> {
                val data = jsonObj["data"]?.jsonPrimitive?.content ?: return null
                GossipMessage.Channel(data)
            }
            "blacklist" -> {
                val data = jsonObj["data"]?.jsonPrimitive?.content ?: return null
                GossipMessage.Blacklist(data)
            }
            "trustlist" -> {
                val data = jsonObj["data"]?.jsonPrimitive?.content ?: return null
                GossipMessage.Trustlist(data)
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse a ConvoyEvent from JSON string
 */
private val lenientJson = Json { ignoreUnknownKeys = true }

fun parseConvoyEvent(json: String): ConvoyEvent? {
    return try {
        lenientJson.decodeFromString<ConvoyEvent>(json)
    } catch (_: Exception) {
        null
    }
}
