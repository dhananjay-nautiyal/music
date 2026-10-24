package com.music.music.data.sources.module

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fetch policy's address rules — the whole point of issue #16.
 *
 * Resolution is faked throughout: what matters here is what the policy
 * decides about an address, not what any DNS server would say about a name.
 * The addresses themselves are the real kinds — IPv4 literals, real IPv6
 * forms, and a v4-mapped Inet6Address built byte by byte, because Java
 * downgrades a mapped *literal* to Inet4Address and the mapped shape a DNS
 * AAAA record can deliver is the one worth protecting.
 */
class ModuleFetchPolicyTest {

    // ── Schemes ─────────────────────────────────────────────────────────

    @Test
    fun `https to a public address is allowed`() {
        val allowed = ModuleFetchPolicy.requireAllowed("https://example.com/x") { listOf(v4("93.184.216.34")) }
        assertEquals(listOf("93.184.216.34"), allowed.map { it.hostAddress })
    }

    @Test
    fun `http to a public address is refused`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("http://example.com/x") { listOf(v4("93.184.216.34")) }
        }
        assertTrue(
            "local addresses" in (refused.message ?: ""),
            "message should say what was wanted: ${refused.message}",
        )
    }

    @Test
    fun `anything that is not http or https is refused`() {
        for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)")) {
            val refused = assertFailsWith<FetchNotAllowed>("for $url") {
                ModuleFetchPolicy.requireAllowed(url) { listOf(v4("127.0.0.1")) }
            }
            assertTrue(
                "only http and https" in (refused.message ?: ""),
                "message for $url: ${refused.message}",
            )
        }
    }

    // ── The SSRF targets ────────────────────────────────────────────────

    @Test
    fun `the link-local block is refused on http - this is 169 254 169 254`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("http://169.254.169.254/latest/meta-data/") {
                listOf(v4("169.254.169.254"))
            }
        }
        assertTrue("link-local" in (refused.message ?: ""), "message: ${refused.message}")
    }

    @Test
    fun `the link-local block is refused on https too`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("https://169.254.169.254/") { listOf(v4("169.254.169.254")) }
        }
        assertTrue("link-local" in (refused.message ?: ""), "message: ${refused.message}")
    }

    @Test
    fun `an ipv6 link-local address is refused on https`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("https://[fe80::1]/") { listOf(InetAddress.getByName("fe80::1")) }
        }
        assertTrue("link-local" in (refused.message ?: ""), "message: ${refused.message}")
    }

    @Test
    fun `a v4-mapped link-local address cannot hide behind the mapping`() {
        // What a crafted AAAA record hands back: an Inet6Address, not an
        // Inet4Address — Java never downgrades a mapped value that came
        // from DNS.
        val mapped = Inet6Address.getByAddress(
            "mapped.test",
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0xff.toByte(), 0xff.toByte(),
                169.toByte(), 254.toByte(), 169.toByte(), 254.toByte(),
            ),
            -1,
        )
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("https://metadata.internal/") { listOf(mapped) }
        }
        assertTrue("link-local" in (refused.message ?: ""), "message: ${refused.message}")
    }

    // ── What is allowed over plain http ─────────────────────────────────

    @Test
    fun `http reaches loopback and the private ranges - the self-hosted case`() {
        for (address in listOf("127.0.0.1", "10.0.0.5", "172.16.0.9", "192.168.1.1")) {
            val allowed = ModuleFetchPolicy.requireAllowed("http://local.test/x") { listOf(v4(address)) }
            assertEquals(listOf(address), allowed.map { it.hostAddress }, "for $address")
        }
    }

    @Test
    fun `http reaches ipv6 loopback and unique-local addresses`() {
        for (address in listOf("::1", "fd00::1")) {
            val allowed = ModuleFetchPolicy.requireAllowed("http://local.test/x") {
                listOf(InetAddress.getByName(address))
            }
            assertEquals(1, allowed.size, "for $address")
        }
    }

    @Test
    fun `http to a mixed-resolving host connects only to the local address`() {
        // One public record must not fail the request *or* be connectable:
        // the returned list is what the caller pins the socket to.
        val allowed = ModuleFetchPolicy.requireAllowed("http://dual.test/x") {
            listOf(v4("93.184.216.34"), v4("192.168.1.1"))
        }
        assertEquals(listOf("192.168.1.1"), allowed.map { it.hostAddress })
    }

    @Test
    fun `https to a mixed-resolving host drops the link-local record`() {
        val allowed = ModuleFetchPolicy.requireAllowed("https://dual.test/x") {
            listOf(v4("169.254.1.1"), v4("93.184.216.34"))
        }
        assertEquals(listOf("93.184.216.34"), allowed.map { it.hostAddress })
    }

    // ── Resolution failures and shape checks ────────────────────────────

    @Test
    fun `an unresolvable host is refused with the failure named`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("https://nowhere.test/") {
                throw UnknownHostException("nowhere.test")
            }
        }
        assertTrue("cannot be resolved" in (refused.message ?: ""), "message: ${refused.message}")
    }

    @Test
    fun `a host resolving to nothing is refused`() {
        val refused = assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("https://empty.test/") { emptyList() }
        }
        assertTrue("resolves to nothing" in (refused.message ?: ""), "message: ${refused.message}")
    }

    @Test
    fun `an ipv6 literal reaches the resolver without its brackets`() {
        var seen: String? = null
        ModuleFetchPolicy.requireAllowed("http://[::1]:8080/x") { host ->
            seen = host
            listOf(InetAddress.getByName("::1"))
        }
        assertEquals("::1", seen)
    }

    @Test
    fun `a url with no host at all is refused`() {
        assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.requireAllowed("/just/a/path") { listOf(v4("127.0.0.1")) }
        }
    }

    // ── Redirect targets ────────────────────────────────────────────────

    @Test
    fun `a relative redirect resolves against the url it arrived on`() {
        assertEquals(
            "http://a.test/next",
            ModuleFetchPolicy.resolveRedirect("http://a.test/api/v1/search", "/next"),
        )
    }

    @Test
    fun `a full and a protocol-relative redirect both resolve`() {
        assertEquals(
            "https://b.test/other",
            ModuleFetchPolicy.resolveRedirect("http://a.test/x", "https://b.test/other"),
        )
        assertEquals(
            "http://c.test/y",
            ModuleFetchPolicy.resolveRedirect("http://a.test/x", "//c.test/y"),
        )
    }

    @Test
    fun `an unusable redirect target is refused rather than followed`() {
        assertFailsWith<FetchNotAllowed> {
            ModuleFetchPolicy.resolveRedirect("http://a.test/x", "ht tp://broken")
        }
    }

    private fun v4(address: String): InetAddress = InetAddress.getByName(address)
}
