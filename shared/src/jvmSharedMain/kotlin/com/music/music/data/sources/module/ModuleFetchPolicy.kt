package com.music.music.data.sources.module

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Raised when a module's fetch target is refused by [ModuleFetchPolicy]. */
class FetchNotAllowed(message: String) : SecurityException(message)

/** Raised when a module fetch response runs past [ModuleFetchPolicy.MAX_RESPONSE_BYTES]. */
class FetchTooLarge(message: String) : IOException(message)

/**
 * What module JavaScript may fetch, and how much of it it may keep.
 *
 * The `fetch` binding takes its URL from module code, and module code takes
 * its index from the network — so the URL is attacker-influenced by design
 * (issue #16). Before a request goes out, two things have to be true:
 *
 * - **The address has to be one a stranger could reach too.** The device's
 *   own LAN and, above all, the link-local block that holds cloud metadata
 *   endpoints (169.254.169.254 answers on every cloud VM) are the SSRF
 *   payoff: a hostile index can make a phone ask its own router, or a cloud
 *   host ask itself for its credentials, and the answer comes back inside
 *   the module's response. So: https anywhere public; http only for
 *   addresses that cannot leave the machine (loopback and the private
 *   ranges — the self-hosted addon case); link-local never, on either
 *   scheme.
 * - **The response has to fit in a budget.** One fetch used to buffer
 *   whatever the server sent with `body.string()`, unbounded: a hostile or
 *   simply enormous response took the heap with it. [MAX_RESPONSE_BYTES]
 *   caps it, counted as bytes arrive rather than trusted from a header.
 *
 * The address is resolved once, checked, and handed back so the connection
 * is made *to those addresses* — a second lookup at connect time is where
 * DNS rebinding would swap in a different answer between the check and the
 * socket. Redirects are the same story: the caller follows them by hand so
 * every hop comes back through [requireAllowed].
 */
object ModuleFetchPolicy {

    /** The largest response body a module fetch may buffer, in bytes. */
    const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024

    /** How many redirects one fetch may follow — each hop checked like the first. */
    const val MAX_REDIRECTS = 5

    /**
     * Checks [url] and returns the addresses it may be connected to.
     *
     * Throws [FetchNotAllowed] when the URL is not fetchable under the class
     * note's rules; the message says which rule refused it, because it ends
     * up in the module's error payload and the module's log.
     *
     * [resolve] is injectable so tests can hand back fixed addresses without
     * touching DNS; the default is the real lookup.
     */
    fun requireAllowed(
        url: String,
        resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): List<InetAddress> {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw FetchNotAllowed("fetch to '$url' is refused: it is not a URL this host understands")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw FetchNotAllowed("fetch to '$url' is refused: only http and https may be fetched")
        }
        // URI keeps IPv6 literals in brackets; the resolver wants them bare.
        val host = uri.host?.removeSurrounding("[", "]")
            ?: throw FetchNotAllowed("fetch to '$url' is refused: it names no host")
        val resolved = runCatching { resolve(host) }.getOrElse {
            throw FetchNotAllowed("fetch to '$url' is refused: $host cannot be resolved (${it.message})")
        }
        if (resolved.isEmpty()) {
            throw FetchNotAllowed("fetch to '$url' is refused: $host resolves to nothing")
        }
        val allowed = resolved.filter { permits(it, scheme) }
        if (allowed.isEmpty()) {
            val addresses = resolved.joinToString { it.hostAddress ?: it.toString() }
            val why = if (resolved.all { isLinkLocal(it) }) {
                "link-local addresses are never reachable, on either scheme"
            } else {
                "http:// is only for local addresses, and this host has none"
            }
            throw FetchNotAllowed("fetch to '$url' is refused: $addresses — $why")
        }
        return allowed
    }

    /**
     * Resolves a redirect's Location against the URL it arrived on, so
     * relative targets ("/next", "../x"), protocol-relative targets
     * ("//other/host") and full URLs all land somewhere the next hop's
     * [requireAllowed] can judge.
     */
    fun resolveRedirect(from: String, location: String): String =
        runCatching { URI(from).resolve(location.trim()).toString() }.getOrElse {
            throw FetchNotAllowed("fetch to '$location' is refused: it is not a URL this host understands")
        }

    /** Per-address rules: link-local never; otherwise https anywhere, http only locally. */
    private fun permits(address: InetAddress, scheme: String): Boolean =
        !isLinkLocal(address) && (scheme == "https" || isLocal(address))

    /**
     * 169.254.0.0/16 and fe80::/10 — where link-local services live, cloud
     * metadata endpoints among them. Never fetchable, on either scheme.
     */
    private fun isLinkLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isLinkLocalV4(address.address)
        is Inet6Address -> embeddedV4(address)?.let { isLinkLocalV4(it) }
            ?: run {
                val b = address.address
                (b[0].toInt() and 0xff) == 0xfe && (b[1].toInt() and 0xc0) == 0x80
            }

        else -> false
    }

    private fun isLinkLocalV4(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and 0xff) == 169 && (bytes[1].toInt() and 0xff) == 254

    /**
     * Addresses that cannot leave the machine: loopback, the RFC1918 private
     * ranges, and IPv6 unique-local (fc00::/7, the v6 counterpart of
     * 192.168/16) — plus the IPv4-mapped forms of all of them, which a
     * literal like `::ffff:127.0.0.1` or a crafted AAAA record can hide
     * behind.
     */
    private fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isLocalV4(address.address)
        is Inet6Address -> {
            val v4 = embeddedV4(address)
            when {
                v4 != null -> isLocalV4(v4)
                isLoopbackV6(address.address) -> true
                (address.address[0].toInt() and 0xfe) == 0xfc -> true // fc00::/7
                else -> false
            }
        }

        else -> false
    }

    private fun isLocalV4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return when {
            first == 127 -> true // loopback
            first == 10 -> true // 10.0.0.0/8
            first == 172 && second in 16..31 -> true // 172.16.0.0/12
            first == 192 && second == 168 -> true // 192.168.0.0/16
            first == 0 -> true // "this network" — never routes off-host
            else -> false
        }
    }

    private fun isLoopbackV6(bytes: ByteArray): Boolean {
        for (i in 0..14) if (bytes[i] != 0.toByte()) return false
        return bytes[15] == 1.toByte()
    }

    /**
     * The embedded IPv4 of a v4-mapped address (::ffff:a.b.c.d), or null.
     *
     * Checked by hand rather than by class: Java hands back an [Inet4Address]
     * for a mapped *literal*, but a mapped address arriving from DNS as an
     * AAAA record stays an [Inet6Address] — exactly the shape a crafted
     * record would use to hide 169.254.169.254 behind "::ffff:".
     */
    private fun embeddedV4(address: Inet6Address): ByteArray? {
        val b = address.address
        if (b.size != 16) return null
        for (i in 0..9) if (b[i] != 0.toByte()) return null
        if ((b[10].toInt() and 0xff) != 0xff || (b[11].toInt() and 0xff) != 0xff) return null
        return b.copyOfRange(12, 16)
    }
}
