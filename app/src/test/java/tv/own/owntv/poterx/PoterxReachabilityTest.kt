package tv.own.owntv.poterx

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.poterx.PoterxReachability.Block
import tv.own.owntv.poterx.PoterxReachability.Dns
import tv.own.owntv.poterx.PoterxReachability.Http
import tv.own.owntv.poterx.PoterxReachability.Tcp
import tv.own.owntv.poterx.PoterxReachability.Verdict
import tv.own.owntv.poterx.PoterxReachability.Companion.classify

class PoterxReachabilityTest {

    @Test
    fun `no DNS answer or a bogus one is a DNS block`() {
        assertEquals(Verdict.Blocked(Block.DNS), classify(Dns.FAILED, Tcp.FAILED, Http.Failed(false)))
        assertEquals(Verdict.Blocked(Block.DNS), classify(Dns.BOGUS, Tcp.OK, Http.Answered(200, json = false)))
    }

    @Test
    fun `a dropped or reset connection is an address block`() {
        assertEquals(Verdict.Blocked(Block.CONNECTION), classify(Dns.OK, Tcp.FAILED, Http.Failed(false)))
        assertEquals(Verdict.Blocked(Block.CONNECTION), classify(Dns.OK, Tcp.OK, Http.Failed(reset = true)))
    }

    @Test
    fun `a panel answering JSON is reachable whatever the status`() {
        assertEquals(Verdict.Ok, classify(Dns.OK, Tcp.OK, Http.Answered(200, json = true)))
        assertEquals(Verdict.Ok, classify(Dns.OK, Tcp.OK, Http.Answered(401, json = true)))
    }

    @Test
    fun `only a web page or a 451 in the panel's place is a page block`() {
        assertEquals(Verdict.Blocked(Block.PAGE), classify(Dns.OK, Tcp.OK, Http.Answered(200, json = false, html = true)))
        assertEquals(Verdict.Blocked(Block.PAGE), classify(Dns.OK, Tcp.OK, Http.Answered(403, json = false, html = true)))
        assertEquals(Verdict.Blocked(Block.PAGE), classify(Dns.OK, Tcp.OK, Http.Answered(451, json = false)))
    }

    @Test
    fun `an empty body, a 4xx, a 5xx or a timeout is the panel's own doing, not a block`() {
        // The v0.3.4 false alarm: a panel asked without credentials answered an empty page.
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Answered(200, json = false)))
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Answered(403, json = false)))
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Answered(404, json = false)))
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Answered(302, json = false)))
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Answered(502, json = false)))
        assertEquals(Verdict.PanelDown, classify(Dns.OK, Tcp.OK, Http.Failed(reset = false)))
    }
}
