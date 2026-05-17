package com.bootlens.client.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BootLensEndpointTimingFilterTest {

    @Test
    void addsTargetTimingHeadersForActuatorRequests() throws Exception {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        WebEndpointProperties webEndpointProperties = new WebEndpointProperties();
        webEndpointProperties.setBasePath("/actuator");
        BootLensEndpointTimingFilter filter = new BootLensEndpointTimingFilter(properties, webEndpointProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(BootLensEndpointTimingFilter.DURATION_HEADER)).isNotBlank();
        assertThat(response.getHeader(BootLensEndpointTimingFilter.NAME_HEADER)).isEqualTo("health");
    }

    @Test
    void skipsNonActuatorRequests() throws Exception {
        BootLensDiagnosticsProperties properties = new BootLensDiagnosticsProperties();
        WebEndpointProperties webEndpointProperties = new WebEndpointProperties();
        webEndpointProperties.setBasePath("/actuator");
        BootLensEndpointTimingFilter filter = new BootLensEndpointTimingFilter(properties, webEndpointProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(BootLensEndpointTimingFilter.DURATION_HEADER)).isNull();
        assertThat(response.getHeader(BootLensEndpointTimingFilter.NAME_HEADER)).isNull();
    }
}
