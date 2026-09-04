package com.openminis.app.tools.lan

import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * [T-android-lan-share] Share a sandbox directory (or anything the agent can
 * serve) over the LAN.
 *
 * PRoot does NOT isolate the network: a server started inside the sandbox
 * shares the phone's network namespace, so it can bind 0.0.0.0 and be
 * reached from any device on the same Wi-Fi/hotspot. There is no proxy to
 * build — the only missing piece is ergonomics:
 *   1. start the listener as a background JOB (survives the tool call),
 *   2. resolve the phone's LAN IPv4 addresses,
 *   3. hand the agent ready-to-use URLs.
 *
 * The listener is `python3 -m http.server <port> --bind 0.0.0.0 --directory
 * <path>` via [com.openminis.app.tools.jobs.JobTools] (job_kill stops it).
 * The agent can also serve custom content by first writing an index.html
 * into the directory it shares.
 */
object LanShareTools {

    private const val TAG = "LanShare"

    suspend fun executeStart(
        argsJson: String,
        sessionId: String,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val port = args.optInt("port", 8080).coerceIn(1024, 65535)
        val path = args.optString("path", "/var/minis/workspace").trim()
        // Basic sandbox path sanity: only /var/minis/** shareable.
        if (!path.startsWith("/var/minis/") || path.contains("..")) {
            return ToolExecutionResult("path must be under /var/minis/ (got: $path)", false)
        }

        val lanIps = localIpv4Addresses()
        if (lanIps.isEmpty()) {
            return ToolExecutionResult(
                "No LAN IPv4 address found (Wi-Fi/mobile/hotspot interfaces). " +
                    "The phone may be offline — connect to a network first.",
                false,
            )
        }

        // Start the server as a background job (reuses job machinery so
        // job_kill / job_poll manage it consistently).
        val startJob = com.openminis.app.tools.jobs.JobTools.executeStart(
            argsJson = JSONObject()
                .put("command", "python3 -m http.server $port --bind 0.0.0.0 --directory $path")
                .put("label", "lan-share :$port $path")
                .toString(),
            sessionId = sessionId,
        )
        if (!startJob.success) return startJob

        val jobId = Regex("id: (j\\w+)").find(startJob.output)?.groupValues?.get(1) ?: "(job id unknown)"
        val urls = lanIps.joinToString("\n") { "  http://$it:$port/" }
        return ToolExecutionResult(
            "LAN share started (job $jobId).\n" +
                "Serving: $path\n" +
                "Reachable from any device on the same network at:\n$urls\n" +
                "Stop it with job_kill {\"id\":\"$jobId\"}. " +
                "Note: the server dies with the app process (on-device sandbox).",
            true,
        )
    }

    /** All non-loopback IPv4 addresses, most-useful interfaces first. */
    internal fun localIpv4Addresses(): List<String> {
        val out = ArrayList<Pair<String, String>>() // ip to interface name
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains(':')) continue // IPv6 only here
                    val ip = host.substringBefore('/')
                    if (ip.startsWith("169.254.")) continue // link-local noise
                    out.add(ip to nif.name.lowercase())
                }
            }
        } catch (_: Exception) {
        }
        // wlan (Wi-Fi) and hotspot (ap*) interfaces first, then the rest.
        return out.distinctBy { it.first }
            .sortedBy { (_, name) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") -> 1
                    name.startsWith("swlan") -> 1
                    else -> 2
                }
            }
            .map { it.first }
    }
}
