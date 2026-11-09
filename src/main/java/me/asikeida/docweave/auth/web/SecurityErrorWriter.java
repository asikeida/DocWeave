package me.asikeida.docweave.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.asikeida.docweave.common.api.ApiError;
import me.asikeida.docweave.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Security 过滤器错误与 MVC 错误使用同一载荷，不输出认证异常中的令牌信息。 */
@Component
public class SecurityErrorWriter {
    private static final Logger log = LoggerFactory.getLogger(SecurityErrorWriter.class);
    private final JsonMapper json;

    public SecurityErrorWriter(JsonMapper json) { this.json = json; }

    public void write(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        log.warn("Security request rejected: status={}, code={}", status, code);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) { response.setHeader("WWW-Authenticate", "Bearer"); }
        json.writeValue(response.getOutputStream(), ApiError.of(code, message,
                (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)));
    }
}
