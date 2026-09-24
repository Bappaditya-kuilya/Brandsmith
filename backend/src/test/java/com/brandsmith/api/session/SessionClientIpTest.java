package com.brandsmith.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SessionClientIpTest {

    @Test
    void trustsXffFromLoopbackProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("203.0.113.9", SessionController.clientIp("203.0.113.9", req));
        assertEquals("203.0.113.9", SessionController.clientIp("203.0.113.9, 10.0.0.1", req));
    }

    @Test
    void ignoresXffFromUntrustedPublicClient() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.20");
        assertEquals("198.51.100.20", SessionController.clientIp("9.9.9.9", req));
    }

    @Test
    void fallsBackToRemoteWhenXffMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.20");
        assertEquals("198.51.100.20", SessionController.clientIp(null, req));
        assertEquals("198.51.100.20", SessionController.clientIp("  ", req));
    }

    @Test
    void trustsDockerBridgeAndPrivateRanges() {
        MockHttpServletRequest docker = new MockHttpServletRequest();
        docker.setRemoteAddr("172.18.0.1");
        assertEquals("198.51.100.7", SessionController.clientIp("198.51.100.7", docker));

        MockHttpServletRequest rfc1918 = new MockHttpServletRequest();
        rfc1918.setRemoteAddr("10.1.2.3");
        assertEquals("198.51.100.8", SessionController.clientIp("198.51.100.8", rfc1918));
    }

    @Test
    void takesRightmostNonTrustedHopNotLeftmost() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("9.9.9.9", SessionController.clientIp("1.2.3.4, 9.9.9.9, 10.0.0.1", req));
    }

    @Test
    void fallsBackToRemoteWhenAllHopsAreTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("127.0.0.1", SessionController.clientIp("10.0.0.5, 10.0.0.1", req));
    }
}
