import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** voiceOverVolume value (0-49) at which the mic is at full strength; 0 = muted. */
private const val MIC_FULL = 49.0
/** A talker's frame older than this (ms) is considered stale and not played. */
private const val FRAME_STALE_MS = 1500L

// --- Noise suppression (pure-JVM, identical on every OS) ---
/** One-pole high-pass coefficient for ~100 Hz at 16 kHz — strips low rumble. */
private const val HP_A = 0.9636
/** Gate envelope coefficients at 16 kHz: fast attack (~5 ms), slow release (~150 ms). */
private const val GATE_ATTACK = 0.0125
private const val GATE_RELEASE = 0.0004

/**
 * Tiny self-contained sync server for overhead "voice" icons and proximity voice.
 *
 * Each connected client reports its player's full state once per game pulse via
 * POST /update: world, tile (x,y,plane) and current voice level (0-100). The
 * server keeps an in-memory {@link Player} per username. No database — purely
 * in-memory; restart clears it.
 *
 * Proximity voice: the loop clip ({@code GET /audio/yup.wav?listener=name}) is
 * attenuated server-side, per listener, by distance to every OTHER talking
 * player. A talker is only heard if it is on the same world, the same plane and
 * within 23 tiles (checked in that order to skip the distance maths early).
 * 23 tiles -> 0% volume, 5 tiles or closer -> 100%, linear between. Mutes are
 * one-way: a talker is silent to a listener only if that listener muted them.
 *
 * Endpoints:
 *   POST   /update/{name}/{world}/{x}/{y}/{plane}/{volume}  per-tick player state
 *   DELETE /icon/{name}                                     remove a player (logout)
 *   GET    /state                                           "name volume" per player (overhead sprite)
 *   POST   /mute/{muter}/{target}                           muter mutes target
 *   DELETE /mute/{muter}/{target}                           muter un-mutes target
 *   GET    /mutes                                           "muter target" per mute pair
 *   GET    /gain/{listener}                                 listener's current attenuated talk gain 0-100
 *   GET    /audio/yup.wav[?listener=name]                   the voice clip, PCM-attenuated for the listener
 *   GET    /health                                          "ok"
 *
 * Config via env vars:
 *   VOICE_PORT  listen port  (default 8080)
 */

/** Hearing range in tiles. At HEAR_FULL or closer the talker is at 100%; at HEAR_MAX, 0%. */
private const val CLOSEST_TILES = 5.0
private const val FARTHEST_TILES = 23.0

/**
 * A connected player, as tracked by the voice server. Updated every pulse.
 *
 * Plane and world exist only to rule out whether one player can hear another;
 * {@link #getDistance} returns -1 for a player on a different world or plane.
 */
class Player(val username: String) {
    @Volatile var worldId: Int = -1
    @Volatile var volume: Int = 0
    @Volatile var tile: Tile = Tile(0, 0, 0)
    /** Clan name ("" = not in a clan). Same clan = heard at full volume anywhere. */
    @Volatile var clanName: String = ""
    // Per-talker noise-suppression state, carried across frames (only one uploader
    // per talker, so no synchronization needed): high-pass filter memory and the
    // noise-gate envelope gain.
    var hpPrevIn: Double = 0.0
    var hpPrevOut: Double = 0.0
    var gateGain: Double = 0.0
    @Volatile var lastRms: Double = 0.0
    /** Time (ms) of the last above-threshold frame, for the gate hold. */
    var lastAboveMs: Long = 0
    /** Names (normalized) this player has muted. Muting is mutual when scoring audio. */
    val mutedPlayers: MutableList<String> = CopyOnWriteArrayList()

    /** A world position. Plane only matters for ruling out audibility. */
    class Tile(val x: Int, val y: Int, val plane: Int) {
        /** Euclidean (pythagorean) distance to another tile, ignoring plane. */
        fun getDistance(other: Tile): Double =
            sqrt((x - other.x).toDouble().pow(2) + (y - other.y).toDouble().pow(2))
    }

    /**
     * Distance in tiles between this player and another, by their tiles.
     * Returns -1 when they cannot hear each other at all: different world or
     * different plane (checked first, so the distance maths is skipped).
     */
    fun getDistance(other: Player): Double {
        if (worldId != other.worldId) return -1.0
        if (tile.plane != other.tile.plane) return -1.0
        return tile.getDistance(other.tile)
    }

    fun hasMuted(name: String): Boolean = mutedPlayers.any { it.equals(name, ignoreCase = true) }
}

/** username (normalized) -> Player. */
private val players: MutableMap<String, Player> = ConcurrentHashMap()

/** username (normalized) -> most recent captured mic frame (raw PCM) + capture time (ms). */
private val voiceFrames: MutableMap<String, Pair<ByteArray, Long>> = ConcurrentHashMap()

/** "listener|talker" (normalized) -> capture time of the last frame of that talker
 *  we served that listener, so each frame plays exactly once per listener instead of
 *  being replayed until a newer one arrives. Keyed per talker now that we mix many. */
private val lastServed: MutableMap<String, Long> = ConcurrentHashMap()

private fun env(key: String, default: Long): Long =
    System.getenv(key)?.toLongOrNull() ?: default

private fun normalize(name: String?): String? =
    name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun player(name: String): Player = players.getOrPut(name) { Player(name) }

private fun attenuatedGain(listenerName: String?): Int = loudestAudible(listenerName)?.second ?: 0

/**
 * Every talker a listener should currently hear, each paired with the gain
 * (0-100) to play them at. A talker qualifies when it is another player,
 * talking (volume > 0), not muted by the listener (one-way), and either a
 * clanmate (heard anywhere) or within proximity range (same world + plane,
 * within FARTHEST_TILES). The talker's voiceOverVolume (0-MIC_FULL) sets the
 * base level; proximity then attenuates it with an ease-out (1 - t)^2 falloff.
 * Empty when nobody is audible.
 */
private fun audibleTalkers(listenerName: String?): List<Pair<Player, Int>> {
    val listener = listenerName?.let { players[it] } ?: return emptyList()
    val out = ArrayList<Pair<Player, Int>>()
    for (talker in players.values) {
        if (talker === listener || talker.volume <= 0) continue
        // Mutes are one-way: only the listener muting the talker silences them.
        // The talker muting the listener does not affect what the listener hears.
        if (listener.hasMuted(talker.username)) continue
        // Talker's own mic level as a 0..100 percent (0-MIC_FULL slider, 49 = full).
        val level = (talker.volume / MIC_FULL).coerceAtMost(1.0) * 100.0

        val gain: Double
        if (listener.clanName.isNotEmpty() && listener.clanName.equals(talker.clanName, ignoreCase = true)) {
            // Same clan: heard at the talker's full mic level regardless of world,
            // plane or distance — only proximity attenuation is skipped.
            gain = level
        } else {
            // Not clanmates: proximity voice — same world and plane, within range.
            if (talker.tile.plane != listener.tile.plane) continue
            if (talker.worldId != listener.worldId) continue
            val d = listener.getDistance(talker) // -1 = different world or plane
            if (d < 0 || d > FARTHEST_TILES) continue
            // Ease-out falloff: full at CLOSEST_TILES, collapsing to *exactly* 0 at
            // FARTHEST_TILES. (1 - t)^2 drops fast up close and decays hard toward
            // the edge so the outer tiles are near-silent.
            val t = ((d - CLOSEST_TILES) / (FARTHEST_TILES - CLOSEST_TILES)).coerceIn(0.0, 1.0)
            gain = level * (1.0 - t) * (1.0 - t)
        }
        val g = gain.roundToInt().coerceIn(0, 100)
        if (g > 0) out.add(talker to g)
    }
    return out
}

/** The single loudest audible talker for a listener (used for the /gain readout). */
private fun loudestAudible(listenerName: String?): Pair<Player, Int>? =
    audibleTalkers(listenerName).maxByOrNull { it.second }

/**
 * Mix several raw 16-bit little-endian PCM frames into one by summing samples,
 * clamping to the signed-16-bit range. Frames may differ in length; shorter
 * ones contribute silence past their end. A single frame is returned as-is.
 */
private fun mixPcm16(frames: List<ByteArray>): ByteArray {
    if (frames.size == 1) return frames[0]
    val maxLen = frames.maxOf { it.size }
    val out = ByteArray(maxLen)
    var j = 0
    while (j + 1 < maxLen) {
        var acc = 0
        for (f in frames) {
            if (j + 1 < f.size) {
                acc += (f[j].toInt() and 0xFF) or (f[j + 1].toInt() shl 8) // signed 16-bit
            }
        }
        val s = acc.coerceIn(-32768, 32767)
        out[j] = (s and 0xFF).toByte()
        out[j + 1] = ((s shr 8) and 0xFF).toByte()
        j += 2
    }
    return out
}

/** Scale raw 16-bit little-endian PCM samples by factor (0..1). */
private fun scalePcm16(src: ByteArray, factor: Double): ByteArray {
    if (factor >= 0.999) return src
    val out = src.copyOf()
    var j = 0
    while (j + 1 < out.size) {
        var s = (out[j].toInt() and 0xFF) or (out[j + 1].toInt() shl 8) // signed 16-bit
        s = (s * factor).roundToInt().coerceIn(-32768, 32767)
        out[j] = (s and 0xFF).toByte()
        out[j + 1] = ((s shr 8) and 0xFF).toByte()
        j += 2
    }
    return out
}

/** Gate RMS threshold (0-32767): frames quieter than this are faded to silence. */
private val GATE_RMS = env("VOICE_GATE", 150L).toDouble()
/** Hold (ms): keep the gate open this long after the last above-threshold frame,
 *  so brief dips between syllables don't chop the speech. */
private val GATE_HOLD_MS = env("VOICE_HOLD", 150L)

/**
 * Clean up a talker's raw 16-bit PCM frame in place: a one-pole high-pass to strip
 * low rumble, then a noise gate that fades the signal toward silence when the frame
 * is below the RMS threshold (fast attack / slow release, smoothed per-sample to
 * avoid clicks). Filter and envelope state persist on the Player across frames.
 * Pure JVM math — behaves identically on every OS.
 */
private fun denoise(p: Player, src: ByteArray): ByteArray {
    val n = src.size / 2
    if (n == 0) return src
    val out = src.copyOf()
    val filtered = DoubleArray(n)
    var hpX = p.hpPrevIn
    var hpY = p.hpPrevOut
    var sumSq = 0.0
    var j = 0
    var i = 0
    while (j + 1 < out.size) {
        val x = ((out[j].toInt() and 0xFF) or (out[j + 1].toInt() shl 8)).toDouble()
        val y = HP_A * (hpY + x - hpX) // high-pass
        hpX = x
        hpY = y
        filtered[i] = y
        sumSq += y * y
        i++
        j += 2
    }
    p.hpPrevIn = hpX
    p.hpPrevOut = hpY

    val rms = sqrt(sumSq / n)
    p.lastRms = rms
    // Hold the gate open for GATE_HOLD_MS after the last loud frame so quiet
    // syllables / short pauses in connected speech don't chop the audio.
    val now = System.currentTimeMillis()
    if (rms >= GATE_RMS) {
        p.lastAboveMs = now
    }
    val target = if (now - p.lastAboveMs <= GATE_HOLD_MS) 1.0 else 0.0
    var g = p.gateGain
    j = 0
    i = 0
    while (j + 1 < out.size) {
        val coef = if (target > g) GATE_ATTACK else GATE_RELEASE
        g += (target - g) * coef // smoothed gate envelope
        val s = (filtered[i] * g).roundToInt().coerceIn(-32768, 32767)
        out[j] = (s and 0xFF).toByte()
        out[j + 1] = ((s shr 8) and 0xFF).toByte()
        i++
        j += 2
    }
    p.gateGain = g
    return out
}

fun main() {
    val port = env("VOICE_PORT", 8080).toInt()

    println("[ktor_voice] starting on port $port")

    embeddedServer(Netty, port = port) {
        routing {
            // Per-tick player state: world, tile, voice level and clan name (?clan=).
            post("/update/{name}/{world}/{x}/{y}/{plane}/{volume}") {
                val name = normalize(call.parameters["name"])
                val world = call.parameters["world"]?.toIntOrNull()
                val x = call.parameters["x"]?.toIntOrNull()
                val y = call.parameters["y"]?.toIntOrNull()
                val plane = call.parameters["plane"]?.toIntOrNull()
                val volume = call.parameters["volume"]?.toIntOrNull()?.coerceIn(0, 100)
                val clan = call.request.queryParameters["clan"]?.trim().orEmpty()
                if (name != null && world != null && x != null && y != null && plane != null && volume != null) {
                    val p = player(name)
                    p.worldId = world
                    p.tile = Player.Tile(x, y, plane)
                    p.volume = volume
                    p.clanName = clan
                }
                call.respondText("ok")
            }
            delete("/icon/{name}") {
                normalize(call.parameters["name"])?.let { players.remove(it) }
                call.respondText("ok")
            }
            get("/state") {
                val body = buildString {
                    players.values.forEach { append(it.username).append(' ').append(it.volume).append('\n') }
                }
                call.respondText(body, ContentType.Text.Plain)
            }
            // TEMP debug: players + whether mic frames are arriving (size, age ms).
            get("/debug") {
                val now = System.currentTimeMillis()
                val body = buildString {
                    append("GATE_RMS=").append(GATE_RMS).append('\n')
                    players.values.forEach { p ->
                        val f = voiceFrames[p.username]
                        append(p.username)
                            .append(" vol=").append(p.volume)
                            .append(" world=").append(p.worldId)
                            .append(" clan=").append(p.clanName)
                            .append(" rms=").append(p.lastRms.toInt())
                            .append(" frame=").append(if (f == null) "none" else "${f.first.size}B age=${now - f.second}ms")
                            .append('\n')
                    }
                }
                call.respondText(body, ContentType.Text.Plain)
            }
            post("/mute/{muter}/{target}") {
                val muter = normalize(call.parameters["muter"])
                val target = normalize(call.parameters["target"])
                if (muter != null && target != null && !player(muter).hasMuted(target)) {
                    player(muter).mutedPlayers.add(target)
                }
                call.respondText("ok")
            }
            delete("/mute/{muter}/{target}") {
                val muter = normalize(call.parameters["muter"])
                val target = normalize(call.parameters["target"])
                if (muter != null && target != null) {
                    players[muter]?.mutedPlayers?.removeIf { it.equals(target, ignoreCase = true) }
                }
                call.respondText("ok")
            }
            get("/mutes") {
                val body = buildString {
                    players.values.forEach { p ->
                        p.mutedPlayers.forEach { t -> append(p.username).append(' ').append(t).append('\n') }
                    }
                }
                call.respondText(body, ContentType.Text.Plain)
            }
            get("/gain/{listener}") {
                call.respondText(attenuatedGain(normalize(call.parameters["listener"])).toString())
            }
            // A talker uploads its latest captured mic frame (raw 16-bit PCM).
            post("/voice/{name}") {
                val name = normalize(call.parameters["name"])
                val bytes = call.receiveStream().readBytes()
                if (name != null && bytes.isNotEmpty()) {
                    // High-pass + noise-gate the frame before storing it.
                    val clean = denoise(player(name), bytes)
                    voiceFrames[name] = clean to System.currentTimeMillis()
                }
                call.respondText("ok")
            }
            // A listener pulls a single frame mixing EVERY audible talker, each
            // PCM-scaled by its own per-listener gain (proximity/clan). 204 when
            // nobody is audible or no talker has a fresh frame to play.
            get("/voice/{listener}") {
                val listener = normalize(call.parameters["listener"])
                val talkers = if (listener == null) emptyList() else audibleTalkers(listener)
                if (listener == null || talkers.isEmpty()) {
                    call.respond(HttpStatusCode.NoContent)
                    return@get
                }
                val now = System.currentTimeMillis()
                val contributions = ArrayList<ByteArray>(talkers.size)
                for ((talker, gain) in talkers) {
                    val frame = voiceFrames[talker.username] ?: continue
                    if (now - frame.second > FRAME_STALE_MS) continue // stale
                    // Serve each talker's frame at most once per listener, so audio
                    // plays through instead of repeating the same syllable.
                    val key = "$listener|${talker.username}"
                    if (frame.second <= (lastServed[key] ?: 0L)) continue
                    lastServed[key] = frame.second
                    contributions.add(scalePcm16(frame.first, gain / 100.0))
                }
                if (contributions.isEmpty()) {
                    call.respond(HttpStatusCode.NoContent)
                    return@get
                }
                call.respondBytes(mixPcm16(contributions), ContentType.Application.OctetStream)
            }
            get("/health") {
                call.respondText("ok")
            }
        }
    }.start(wait = true)
}
