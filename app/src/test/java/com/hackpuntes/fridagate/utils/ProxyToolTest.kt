package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyToolTest {

    @Test
    fun caUrlForEachTool() {
        assertEquals("http://192.168.1.10:8080/cert", ProxyTool.BURP.caUrl("192.168.1.10", 8080))
        assertEquals("http://192.168.1.10:8080/ca.crt", ProxyTool.CAIDO.caUrl("192.168.1.10", 8080))
        assertEquals("http://mitm.it/cert/pem", ProxyTool.MITMPROXY.caUrl("192.168.1.10", 8080))
    }

    @Test
    fun onlyMitmproxyServesItsCaThroughTheProxy() {
        assertFalse(ProxyTool.BURP.caViaProxy)
        assertFalse(ProxyTool.CAIDO.caViaProxy)
        assertTrue(ProxyTool.MITMPROXY.caViaProxy)
    }

    @Test
    fun unknownSavedNamesFallBackToBurp() {
        assertEquals(ProxyTool.CAIDO, ProxyTool.fromName("CAIDO"))
        assertEquals(ProxyTool.BURP, ProxyTool.fromName(null))
        assertEquals(ProxyTool.BURP, ProxyTool.fromName("ZAP"))
    }
}
