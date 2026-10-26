package me.asikeida.docweave.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void correlatesResponseAndLogsAndDoesNotReuseIds() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) -> {
                String id = MDC.get("requestId");
                UUID.fromString(id);
                assertEquals(id, response.getHeader("X-Request-Id"));
                assertEquals(id, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
                ids.add(id);
            });
            assertNull(MDC.get("requestId"));
        }
        assertNotEquals(ids.get(0), ids.get(1));
    }

    @Test
    void clearsRequestIdEvenWhenDownstreamFails() {
        assertThrows(ServletException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> { throw new ServletException("测试异常路径"); }
        ));
        assertNull(MDC.get("requestId"));
    }
}
