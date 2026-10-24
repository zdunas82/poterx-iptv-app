package tv.own.owntv.poterx.vpn

import com.wireguard.config.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PoterxVpnConfigTest {
    private val sample = """
        [Interface]
        PrivateKey = YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.14.0.2/16
        DNS = 162.252.172.57, 149.154.159.92

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = pl-waw.prod.surfshark.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `app routing is added to the interface section`() {
        val routed = PoterxVpn.withAppRouting(sample, "tv.own.owntv")
        val config = Config.parse(ByteArrayInputStream(routed.toByteArray()))
        assertEquals(setOf("tv.own.owntv"), config.`interface`.includedApplications)
        assertEquals(1, config.peers.size)
        assertEquals("pl-waw.prod.surfshark.com", config.peers[0].endpoint.get().host)
    }

    @Test
    fun `a file that already names applications is left alone`() {
        val own = sample.replace("[Interface]", "[Interface]\nExcludedApplications = com.example.other")
        assertEquals(own, PoterxVpn.withAppRouting(own, "tv.own.owntv"))
    }

    @Test
    fun `routing survives Windows line endings and a lower-case header`() {
        val windows = sample.replace("[Interface]", "[interface]").replace("\n", "\r\n")
        val routed = PoterxVpn.withAppRouting(windows, "tv.own.owntv")
        assertTrue(routed.lines().any { it.trim() == "IncludedApplications = tv.own.owntv" })
    }
}
