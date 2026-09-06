package com.convoyrama.convoyrun.model

import androidx.annotation.StringRes
import com.convoyrama.convoyrun.R
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.datetime.Instant

/**
 * CTES event document model (matches desktop EventDocument).
 */
@Serializable
data class EventDocument(
    @SerialName("specVersion")
    val specVersion: String = "1.0",
    val kind: String = "event",
    val id: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val revision: Long = 1,
    val authorId: String = "",
    val createdAt: String = "1970-01-01T00:00:00Z",
    val updatedAt: String = "1970-01-01T00:00:00Z",
    @SerialName("data")
    val event: EventData,
    val signature: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deleteSignature: String = "",
    @Transient val peerId: String = "",
    @Transient val nickname: String = "",
    @Transient val publishedAt: Long = 0L,
    @Transient val schedule: Schedule = Schedule(),
    @Transient val channel: String = "",
    @Transient val flyer: FlyerData? = null,
    @Transient val deleted: Boolean = false
)

@Serializable
data class EventData(
    val title: String = "",
    val description: String = "",
    val language: String = "",
    val translations: Map<String, Translation> = emptyMap(),
    val eventType: EventType = EventType.Convoy,
    val customEventType: String? = null,
    val game: Game = Game.ATS,
    val customGame: String? = null,
    val network: NetworkData = NetworkData(server = ""),
    val schedule: Schedule = Schedule(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val route: Route = Route(),
    val requirements: Requirements? = null,
    val links: List<Link> = emptyList(),
    val flyer: FlyerData? = null,
    val extensions: Map<String, JsonElement> = emptyMap(),
    @Transient val mode: GameMode = GameMode.Simulation,
    @Transient val link: String = "",
    @Transient val server: String = ""
)

@Serializable
data class Schedule(
    val meetingAt: String = "1970-01-01T00:00:00Z",
    val startAt: String = "1970-01-01T00:00:00Z",
    val endAt: String? = null,
    val timeZone: String = "UTC",
    @Transient val meetingTimestamp: Long = 0L,
    @Transient val ianaTimeZone: String = "UTC"
) {
}

@Serializable
data class Route(
    val origins: List<Place> = emptyList(),
    val waypoints: List<Place> = emptyList(),
    val destination: Place? = null,
    @Transient val startCity: String = "",
    @Transient val startLocation: String = "",
    @Transient val destCity: String = "",
    @Transient val destLocation: String = ""
)

@Serializable
data class FlyerData(
    val url: String = "",
    val size: Long = 0,
    val mediaType: String = "",
    val digest: String = ""
)

@Serializable
data class Place(
    val city: String = "",
    val location: String = "",
    val country: String = ""
)

@Serializable
data class NetworkData(
    val server: String = "",
    val name: String = "",
    val access: String = ""
)

@Serializable
data class Requirements(
    val dlcs: List<String> = emptyList(),
    val mods: List<String> = emptyList(),
    val vehicles: String = "",
    val notes: String = ""
)

@Serializable
data class Link(
    val rel: String,
    val url: String,
    val label: String = ""
)

@Serializable
data class Translation(
    val title: String = "",
    val description: String = ""
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
    @SerialName("other") Other
}

/**
 * Games (matches desktop Game enum)
 */
@Serializable(with = GameSerializer::class)
enum class Game {
    ATS, ETS2, Other
}

object GameSerializer : KSerializer<Game> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Game", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Game) {
        val serialized = when (value) {
            Game.ATS -> "ats"
            Game.ETS2 -> "ets2"
            Game.Other -> "other"
        }
        encoder.encodeString(serialized)
    }

    override fun deserialize(decoder: Decoder): Game {
        return when (decoder.decodeString().trim().lowercase()) {
            "ats" -> Game.ATS
            "ets2" -> Game.ETS2
            "other" -> Game.Other
            else -> Game.Other
        }
    }
}

/**
 * Game modes (matches desktop Mode enum)
 */
@Serializable
enum class GameMode(@StringRes val displayNameRes: Int) {
    @SerialName("simulation") Simulation(R.string.mode_simulation),
    @SerialName("realistic") Realistic(R.string.mode_realistic),
    @SerialName("arcade") Arcade(R.string.mode_arcade),
    @SerialName("race") Race(R.string.mode_race),
    @SerialName("other") Other(R.string.mode_other)
}

/**
 * Gossip message types (matches desktop GossipMessage enum)
 */
sealed class GossipMessage {
    data class Convoy(val data: String) : GossipMessage()
    data class Vote(val data: String) : GossipMessage()
    data class Profile(val data: String) : GossipMessage()
    data class Tombstone(
        val convoyId: String,
        val peerId: String,
        val revision: Long = 1,
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
        if (jsonObj["protocol"]?.jsonPrimitive?.content == "ctes-gossip/1" &&
            jsonObj["type"]?.jsonPrimitive?.content == "document") {
            val document = jsonObj["document"]?.jsonObject ?: return null
            return when (document["kind"]?.jsonPrimitive?.content) {
                "event" -> GossipMessage.Convoy(document.toString())
                "vote" -> GossipMessage.Vote(document.toString())
                "profile" -> GossipMessage.Profile(document.toString())
                "tombstone" -> {
                    val convoyId = document["eventId"]?.jsonPrimitive?.content ?: return null
                    val peerId = document["authorId"]?.jsonPrimitive?.content ?: return null
                    val revision = document["revision"]?.jsonPrimitive?.longOrNull ?: return null
                    val signature = document["signature"]?.jsonPrimitive?.content ?: return null
                    GossipMessage.Tombstone(convoyId, peerId, revision, signature)
                }
                else -> null
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse an EventDocument from JSON string.
 */
private val lenientJson = Json { ignoreUnknownKeys = true }

private fun normalizeEventDocument(event: EventDocument): EventDocument {
    val publishedAt = runCatching { Instant.parse(event.createdAt).epochSeconds }.getOrDefault(0L)
    val schedule = event.event.schedule.copy(
        meetingTimestamp = runCatching { Instant.parse(event.event.schedule.meetingAt).epochSeconds }.getOrDefault(0L),
        ianaTimeZone = event.event.schedule.timeZone
    )
    val route = event.event.route.copy(
        startCity = event.event.route.origins.firstOrNull()?.city.orEmpty(),
        startLocation = event.event.route.origins.firstOrNull()?.location.orEmpty(),
        destCity = event.event.route.destination?.city.orEmpty(),
        destLocation = event.event.route.destination?.location.orEmpty()
    )
    return event.copy(
        peerId = event.authorId,
        publishedAt = publishedAt,
        schedule = schedule,
        flyer = event.event.flyer,
        event = event.event.copy(
            schedule = schedule,
            route = route,
            server = event.event.network.server,
            link = event.event.links.firstOrNull()?.url.orEmpty()
        )
    )
}

fun parseEventDocument(json: String): EventDocument? {
    return try {
        lenientJson.decodeFromString<EventDocument>(json).let(::normalizeEventDocument)
    } catch (_: Exception) {
        null
    }
}

/**
 * Vote record (matches desktop VoteRecord in convoy.rs)
 */
@Serializable
data class VoteRecord(
    val specVersion: String = "1.0",
    val kind: String = "vote",
    val eventId: String,
    val revision: Long,
    val authorId: String,
    val createdAt: String,
    val updatedAt: String,
    val data: VoteData,
    val signature: String = ""
) {
    val vote: Int get() = data.value
}

@Serializable
data class VoteData(val value: Int)

fun VoteRecord.winsOver(current: VoteRecord): Boolean =
    revision > current.revision ||
        (revision == current.revision && signature > current.signature)

fun parseVoteRecord(json: String): VoteRecord? {
    return try {
        lenientJson.decodeFromString<VoteRecord>(json)
    } catch (_: Exception) {
        null
    }
}

@Serializable
data class ProfileRecord(
    val specVersion: String = "1.0",
    val kind: String = "profile",
    val revision: Long,
    val authorId: String,
    val createdAt: String,
    val updatedAt: String,
    val data: ProfileData,
    val signature: String
)

@Serializable
data class ProfileData(
    val nickname: String,
    val links: List<ProfileLink> = emptyList()
)

@Serializable
data class ProfileLink(val rel: String, val url: String, val label: String? = null)

fun ProfileRecord.winsOver(current: ProfileRecord): Boolean =
    revision > current.revision ||
        (revision == current.revision && signature > current.signature)

fun EventDocument.winsOver(current: EventDocument): Boolean =
    revision > current.revision ||
        (revision == current.revision && deleted != current.deleted && deleted) ||
        (revision == current.revision && deleted == current.deleted && signature > current.signature)

fun parseProfileRecord(json: String): ProfileRecord? = runCatching {
    lenientJson.decodeFromString<ProfileRecord>(json)
}.getOrNull()
