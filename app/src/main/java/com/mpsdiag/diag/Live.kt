package com.mpsdiag.diag

import java.io.IOException
import java.util.Locale

/**
 * One value on a live-data screen.
 *
 * @param request the diagnostic request whose positive reply carries the value
 *        (e.g. `21 10` for a Mitsubishi local id, `01 0C` for an OBD-II PID).
 * @param decoder turns the positive reply (starting with the response service id) into text.
 */
class LiveParam(
    val label: String,
    val request: IntArray,
    private val decoder: (ByteArray) -> String,
) {
    /** Value from Mitsubishi ReadDataByLocalIdentifier `21 <localId>`. */
    constructor(label: String, localId: Int, decoder: (ByteArray) -> String) :
        this(label, intArrayOf(0x21, localId), decoder)

    val requestKey: String get() = request.joinToString("") { "%02X".format(it) }

    /** Decodes [r]; "—" if the reply is too short. */
    fun decode(r: ByteArray): String = try {
        decoder(r)
    } catch (_: IndexOutOfBoundsException) {
        "—"
    }
}

/** A one-shot service routine (calibration, reset, actuator test...). */
class ServiceAction(
    val title: String,
    /** Shown in the confirmation dialog: preconditions and what will happen. */
    val instructions: String,
    /** Requests sent in order to the screen's module; each must get a positive reply. */
    val requests: List<IntArray>,
    val successMessage: String = "Completed.",
)

/** A live-data screen: one module, its values, and optional service actions. */
class LiveScreen(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Home-screen section this screen is listed under. */
    val group: String,
    val module: Module,
    val params: List<LiveParam>,
    val actions: List<ServiceAction> = emptyList(),
    /** Shown above the values (instructions or notes). */
    val note: String? = null,
    val pollIntervalMs: Long = 300,
)

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

    /**
     * Sends each distinct request in [params] once and decodes every value.
     * Values whose request failed show "—". Throws [NoResponseException] only if nothing answered.
     */
    @Throws(IOException::class)
    fun poll(diag: Diag, params: List<LiveParam>, skips: Skips? = null): List<String> {
        val replies = mutableMapOf<String, ByteArray?>()
        var lastError: IOException? = null
        val requests = params.distinctBy { it.requestKey }
        for (p in requests) {
            if (skips?.skipped(p.requestKey) == true) {
                replies[p.requestKey] = null
                continue
            }
            replies[p.requestKey] = try {
                diag.request(*p.request)
            } catch (e: NoResponseException) {
                lastError = e
                null
            } catch (_: NegativeResponseException) {
                null
            }
        }
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

    /** Runs [action] on [module]: opens the session, then sends each request once. */
    @Throws(IOException::class)
    fun run(diag: Diag, module: Module, action: ServiceAction) {
        diag.open(module)
        for (r in action.requests) diag.request(*r, timeoutMs = 5_000)
    }

    // Formatting helpers shared by screen definitions.
    fun f1(v: Double) = String.format(Locale.UK, "%.1f", v)
    fun f2(v: Double) = String.format(Locale.UK, "%.2f", v)
    fun f3(v: Double) = String.format(Locale.UK, "%.3f", v)
    fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** Signed 8-bit value at [i]. */
    fun s8(r: ByteArray, i: Int): Int = r[i].toInt()

    /** Signed big-endian 16-bit value at [i]. */
    fun s16(r: ByteArray, i: Int): Int = r.u16(i).toShort().toInt()
}
