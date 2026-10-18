package com.mpsdiag.diag

import java.io.IOException
import java.util.Locale

/**
 * One value on a live-data screen.
 *
 * @param request the diagnostic request whose positive reply carries the value
 *        (e.g. `21 10` for a Mitsubishi local id, `01 0C` for an OBD-II PID).
 * @param module ask this module instead of the screen's own (e.g. an engine value on the
 *        transmission screen). It is addressed without opening a session.
 * @param decoder turns the positive reply (starting with the response service id) into text.
 */
class LiveParam(
    val label: String,
    val request: IntArray,
    val module: Module? = null,
    private val decoder: (ByteArray) -> String,
) {
    /** Value from [request] to the screen's own module. */
    constructor(label: String, request: IntArray, decoder: (ByteArray) -> String) :
        this(label, request, null, decoder)

    /** Value from Mitsubishi ReadDataByLocalIdentifier `21 <localId>`. */
    constructor(label: String, localId: Int, decoder: (ByteArray) -> String) :
        this(label, intArrayOf(0x21, localId), null, decoder)

    /** Value from `21 <localId>` of another [module]. */
    constructor(label: String, localId: Int, module: Module, decoder: (ByteArray) -> String) :
        this(label, intArrayOf(0x21, localId), module, decoder)

    val requestKey: String
        get() = (module?.let { "%03X:".format(it.requestId) } ?: "") + request.joinToString("") { "%02X".format(it) }

    /** Decodes [r]; "—" if the reply is too short. */
    fun decode(r: ByteArray): String = try {
        decoder(r)
    } catch (_: IndexOutOfBoundsException) {
        "—"
    }
}

/** A number the user enters before an action runs (or a choice from a list). */
class InputField(
    val label: String,
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    /** The value must be a multiple of this (e.g. 100 km). */
    val multipleOf: Int = 1,
    /** If set, the user picks one of these and the value is its index. */
    val choices: List<String>? = null,
    val hint: String? = null,
) {
    /** Null if [v] is acceptable, otherwise the reason. */
    fun validate(v: Int?): String? = when {
        v == null -> "Enter $label"
        choices != null -> null
        v < min || v > max -> "$label must be between $min and $max"
        v % multipleOf != 0 -> "$label must be a multiple of $multipleOf"
        else -> null
    }
}

/** Thrown when an action is refused before anything is sent (e.g. unsupported ECU). */
class NotSupportedException(message: String) : IOException(message)

/** A one-shot service routine (calibration, reset, actuator test...). */
class ServiceAction(
    val title: String,
    /** Shown in the confirmation dialog: preconditions and what will happen. */
    val instructions: String,
    /** Requests sent in order to the screen's module; each must get a positive reply. */
    val requests: List<IntArray> = emptyList(),
    val successMessage: String = "Command accepted.",
    /** Values asked from the user first; passed to [build]. */
    val inputs: List<InputField> = emptyList(),
    /** Builds the requests from the entered values (used instead of [requests]). */
    val build: ((List<Int>) -> List<IntArray>)? = null,
    /** Runs before sending; returns a reason to refuse, or null to go ahead. */
    val check: ((Diag) -> String?)? = null,
) {
    fun requestsFor(values: List<Int>): List<IntArray> = build?.invoke(values) ?: requests
}

/** One flavour of a screen whose values depend on the hardware (e.g. transmission type). */
class Variant(val name: String, val params: List<LiveParam>)

/** A live-data screen: one module, its values, and optional service actions. */
class LiveScreen(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Home-screen section this screen is listed under. */
    val group: String,
    val module: Module,
    val params: List<LiveParam> = emptyList(),
    val actions: List<ServiceAction> = emptyList(),
    /** Shown above the values (instructions or notes). */
    val note: String? = null,
    /** Shown when the screen opens; the user must accept it to continue. */
    val warning: String? = null,
    /** Hardware variants; when set, [params] is ignored and the chosen variant's values are shown. */
    val variants: List<Variant> = emptyList(),
    /** Picks the variant index from the module's answers (null = unknown, ask the user). */
    val detectVariant: ((Diag) -> Int?)? = null,
    /** Open the Mitsubishi diagnostic session (10 92) before polling. */
    val useSession: Boolean = true,
    /** Extra request sent after opening each round (e.g. TesterPresent 3E 01). */
    val keepAlive: IntArray? = null,
    /** Pause between requests; some modules need it. */
    val requestDelayMs: Long = 0,
    val pollIntervalMs: Long = 300,
) {
    fun paramsFor(variant: Int): List<LiveParam> =
        if (variants.isEmpty()) params else variants[variant.coerceIn(variants.indices)].params
}

object Live {

    /**
     * Remembers requests the module keeps rejecting, so a poll loop stops waiting on them.
     * A request is skipped after [limit] failed rounds in a row, as long as others still answer.
     */
    class Skips(private val limit: Int = 3) {
        private val failures = mutableMapOf<String, Int>()
        @Synchronized fun skipped(key: String) = (failures[key] ?: 0) >= limit
        @Synchronized fun record(key: String, ok: Boolean) {
            failures[key] = if (ok) 0 else (failures[key] ?: 0) + 1
        }
        @Synchronized fun clear() = failures.clear()
    }

    /** Addresses the screen's module and, if the screen uses one, opens its session. */
    @Throws(IOException::class)
    fun open(diag: Diag, screen: LiveScreen) {
        if (screen.useSession) diag.open(screen.module) else diag.select(screen.module)
        screen.keepAlive?.let {
            try {
                diag.request(*it)
            } catch (_: NegativeResponseException) {
            }
        }
    }

    /**
     * Sends each distinct request in [params] once and decodes every value.
     * Values whose request failed show "—". Throws [NoResponseException] only if nothing answered.
     * Values from another module are fetched by switching to it and back.
     */
    @Throws(IOException::class)
    fun poll(
        diag: Diag,
        params: List<LiveParam>,
        skips: Skips? = null,
        home: Module? = null,
        delayMs: Long = 0,
    ): List<String> {
        val replies = mutableMapOf<String, ByteArray?>()
        var lastError: IOException? = null
        val requests = params.distinctBy { it.requestKey }
        for (p in requests) {
            if (skips?.skipped(p.requestKey) == true) {
                replies[p.requestKey] = null
                continue
            }
            if (delayMs > 0) Thread.sleep(delayMs)
            val target = p.module ?: home
            if (target != null) diag.select(target)
            replies[p.requestKey] = try {
                diag.request(*p.request)
            } catch (e: NoResponseException) {
                lastError = e
                null
            } catch (_: NegativeResponseException) {
                null
            }
        }
        if (home != null) diag.select(home)
        val anyAnswer = replies.values.any { it != null }
        if (!anyAnswer && lastError != null) {
            skips?.clear() // the module went quiet: don't blame individual requests
            throw lastError!!
        }
        if (anyAnswer) requests.forEach { p ->
            if (skips?.skipped(p.requestKey) != true) skips?.record(p.requestKey, replies[p.requestKey] != null)
        }
        return params.map { p -> replies[p.requestKey]?.let { p.decode(it) } ?: "—" }
    }

    /** Polls a [screen]'s values for [variant] (opening the module first). */
    @Throws(IOException::class)
    fun poll(diag: Diag, screen: LiveScreen, params: List<LiveParam>, skips: Skips? = null): List<String> {
        open(diag, screen)
        return poll(diag, params, skips, screen.module, screen.requestDelayMs)
    }

    /**
     * Runs [action] on the screen's module: optional check, open, then each request once.
     * Nothing is retried; a refusal surfaces as [NegativeResponseException].
     */
    @Throws(IOException::class)
    fun run(diag: Diag, screen: LiveScreen, action: ServiceAction, values: List<Int> = emptyList()) {
        action.check?.invoke(diag)?.let { throw NotSupportedException(it) }
        open(diag, screen)
        for (r in action.requestsFor(values)) {
            if (screen.requestDelayMs > 0) Thread.sleep(screen.requestDelayMs)
            diag.request(*r, timeoutMs = 5_000)
        }
    }

    /** Check used by engine routines: ECUs identifying as 04 25 00 07 don't support them. */
    val ENGINE_ROUTINES_SUPPORTED: (Diag) -> String? = { d ->
        if (Dpf.openEngine(d)) null else "This engine ECU does not support this function."
    }

    // Formatting helpers shared by screen definitions.
    fun f0(v: Double) = String.format(Locale.UK, "%.0f", v)
    fun f1(v: Double) = String.format(Locale.UK, "%.1f", v)
    fun f2(v: Double) = String.format(Locale.UK, "%.2f", v)
    fun f3(v: Double) = String.format(Locale.UK, "%.3f", v)
    fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** Bit [bit] of byte [i] as On/Off. */
    fun bit(r: ByteArray, i: Int, bit: Int): String = onOff(r.u8(i) shr bit and 1 == 1)

    /** Signed 8-bit value at [i]. */
    fun s8(r: ByteArray, i: Int): Int = r[i].toInt()

    /** Signed big-endian 16-bit value at [i]. */
    fun s16(r: ByteArray, i: Int): Int = r.u16(i).toShort().toInt()

    /** Little-endian 16-bit value at [i]. */
    fun le16(r: ByteArray, i: Int): Int = r.u8(i) or (r.u8(i + 1) shl 8)

    /** Little-endian 24-bit value at [i]. */
    fun le24(r: ByteArray, i: Int): Int = r.u8(i) or (r.u8(i + 1) shl 8) or (r.u8(i + 2) shl 16)

    /** Looks up [v] in [names]; unknown values show as hex. */
    fun enumOf(v: Int, names: Map<Int, String>): String = names[v] ?: "Unknown (%02X)".format(v)
}
