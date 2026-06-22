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
import de.maxhenkel.opus4j.OpusDecoder
import de.maxhenkel.opus4j.OpusEncoder
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// --- Opus codec (Concentus, pure JVM) -----------------------------------------
// The wire carries raw 16 kHz/16-bit PCM compressed with Opus. A "chunk" (any run
// of PCM) is sent as a stream of length-prefixed 20 ms Opus packets:
//   [2-byte big-endian length][opus bytes] ... repeated.
// 20 ms is a valid Opus frame at 16 kHz (320 samples); 100 ms is not, hence the
// sub-framing. Encoder/decoder state is per continuous stream (per talker uplink,
// per listener downlink), so callers keep one of each and reuse it.
private const val OPUS_FS = 16000
private const val OPUS_SUBFRAME = 320 // samples = 20 ms @ 16 kHz

private fun newOpusEncoder(): OpusEncoder =
    OpusEncoder(OPUS_FS, 1, OpusEncoder.Application.VOIP).apply {
        setMaxPacketLossPercentage(0.1f) // 10% (opus4j expects a 0..1 fraction) — loss-robust
    }

private fun newOpusDecoder(): OpusDecoder = OpusDecoder(OPUS_FS, 1).apply { setFrameSize(OPUS_SUBFRAME) }

/** Encode 16-bit LE PCM into length-prefixed 20 ms Opus packets. Drops a trailing
 *  partial sub-frame (< 20 ms). */
private fun opusEncode(enc: OpusEncoder, pcm: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(pcm.size / 8)
    val shorts = ShortArray(OPUS_SUBFRAME)
    val totalSamples = pcm.size / 2
    var s = 0
    while (s + OPUS_SUBFRAME <= totalSamples) {
        for (k in 0 until OPUS_SUBFRAME) {
            val i = (s + k) * 2
            shorts[k] = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
        }
        val packet = enc.encode(shorts)
        out.write((packet.size shr 8) and 0xFF)
        out.write(packet.size and 0xFF)
        out.write(packet)
        s += OPUS_SUBFRAME
    }
    return out.toByteArray()
}

/** Decode a stream of length-prefixed Opus packets back to 16-bit LE PCM. */
private fun opusDecode(dec: OpusDecoder, data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size * 8)
    var p = 0
    while (p + 2 <= data.size) {
        val len = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
        p += 2
        if (len <= 0 || p + len > data.size) break
        val packet = data.copyOfRange(p, p + len)
        p += len
        val pcm = dec.decode(packet) ?: continue
        for (v in pcm) {
            val iv = v.toInt()
            out.write(iv and 0xFF)
            out.write((iv shr 8) and 0xFF)
        }
    }
    return out.toByteArray()
}

/** Per-talker uplink decoder and per-listener downlink encoder (each a continuous stream). */
private val opusDecoders: MutableMap<String, OpusDecoder> = ConcurrentHashMap()
private val opusEncoders: MutableMap<String, OpusEncoder> = ConcurrentHashMap()

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
 * Config via env vars or a `.env` file in the working directory:
 *   VOICE_HOST  bind address (default 0.0.0.0 = all interfaces)
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

/** Most recent N frames kept per talker (~N*100ms of audio). A listener may poll
 *  slower than a talker uploads (network RTT > the 100ms frame cadence), so we keep
 *  a short backlog instead of a single slot and hand the listener every frame it has
 *  not yet seen — otherwise frames are overwritten before they're fetched and the
 *  audio drops out. */
private const val MAX_BACKLOG = 12

/** username (normalized) -> recent captured mic frames (raw PCM) + capture time (ms),
 *  oldest first. Access is synchronized on the deque (one uploader, many listeners). */
private val voiceFrames: MutableMap<String, ArrayDeque<Pair<ByteArray, Long>>> = ConcurrentHashMap()

/** "listener|talker" (normalized) -> capture time of the last frame of that talker
 *  we served that listener, so each frame plays exactly once per listener instead of
 *  being replayed until a newer one arrives. Keyed per talker now that we mix many. */
private val lastServed: MutableMap<String, Long> = ConcurrentHashMap()

/**
 * Values loaded from a `.env` file in the working directory at startup (if present).
 * Real environment variables always take precedence over `.env` entries.
 */
private val dotenv: Map<String, String> = run {
    val f = java.io.File(".env")
    if (!f.isFile) emptyMap()
    else f.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=').trim().trim('"', '\'')
            k to v
        }
}

private fun cfg(key: String): String? = System.getenv(key) ?: dotenv[key]

private fun env(key: String, default: Long): Long =
    cfg(key)?.toLongOrNull() ?: default

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
    val host = cfg("VOICE_HOST") ?: "0.0.0.0"

    println("[ktor_voice] starting on $host:$port")

    embeddedServer(Netty, host = host, port = port) {
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
                normalize(call.parameters["name"])?.let {
                    players.remove(it)
                    voiceFrames.remove(it)
                    opusDecoders.remove(it)?.close()
                    opusEncoders.remove(it)?.close()
                }
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
                        val dq = voiceFrames[p.username]
                        val last = dq?.let { synchronized(it) { it.lastOrNull() } }
                        append(p.username)
                            .append(" vol=").append(p.volume)
                            .append(" world=").append(p.worldId)
                            .append(" clan=").append(p.clanName)
                            .append(" rms=").append(p.lastRms.toInt())
                            .append(" backlog=").append(dq?.size ?: 0)
                            .append(" frame=").append(if (last == null) "none" else "${last.first.size}B age=${now - last.second}ms")
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
                    // Opus-decode the uplink to PCM, then high-pass + noise-gate it.
                    val dec = opusDecoders.getOrPut(name) { newOpusDecoder() }
                    val pcm = synchronized(dec) { opusDecode(dec, bytes) }
                    if (pcm.isEmpty()) {
                        call.respondText("ok")
                        return@post
                    }
                    val clean = denoise(player(name), pcm)
                    val dq = voiceFrames.getOrPut(name) { ArrayDeque() }
                    synchronized(dq) {
                        dq.addLast(clean to System.currentTimeMillis())
                        while (dq.size > MAX_BACKLOG) dq.removeFirst() // drop oldest
                    }
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
                // Per talker, every frame this listener hasn't seen yet (and isn't
                // stale), scaled by that talker's gain — oldest first. Polling slower
                // than the 100ms upload cadence just yields several frames at once
                // instead of dropping the ones overwritten between polls.
                val backlogs = ArrayList<List<ByteArray>>(talkers.size)
                for ((talker, gain) in talkers) {
                    val dq = voiceFrames[talker.username] ?: continue
                    val key = "$listener|${talker.username}"
                    val since = lastServed[key] ?: 0L
                    val fresh = ArrayList<ByteArray>()
                    var newest = since
                    synchronized(dq) {
                        for ((frame, ts) in dq) {
                            if (ts <= since || now - ts > FRAME_STALE_MS) continue
                            fresh.add(scalePcm16(frame, gain / 100.0))
                            if (ts > newest) newest = ts
                        }
                    }
                    if (fresh.isNotEmpty()) {
                        lastServed[key] = newest
                        backlogs.add(fresh)
                    }
                }
                if (backlogs.isEmpty()) {
                    call.respond(HttpStatusCode.NoContent)
                    return@get
                }
                // Concatenate frame slots; within each slot mix the talkers. Backlogs
                // are right-aligned (newest frames coincide) so simultaneous talkers
                // stay in sync when their backlogs differ in length.
                val slots = backlogs.maxOf { it.size }
                val out = ByteArrayOutputStream()
                for (i in 0 until slots) {
                    val slice = backlogs.mapNotNull { b ->
                        val idx = i - (slots - b.size)
                        if (idx in b.indices) b[idx] else null
                    }
                    if (slice.isNotEmpty()) out.write(mixPcm16(slice))
                }
                // Opus-encode the mixed PCM for this listener's downlink stream.
                val enc = opusEncoders.getOrPut(listener) { newOpusEncoder() }
                val body = synchronized(enc) { opusEncode(enc, out.toByteArray()) }
                if (body.isEmpty()) {
                    call.respond(HttpStatusCode.NoContent)
                    return@get
                }
                call.respondBytes(body, ContentType.Application.OctetStream)
            }
            get("/health") {
                call.respondText("ok")
            }
        }
    }.start(wait = true)
}
