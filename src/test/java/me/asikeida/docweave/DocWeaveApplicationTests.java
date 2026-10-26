package me.asikeida.docweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(DocWeaveApplicationTests.ValidationTestController.class)
class DocWeaveApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesHealthEndpointWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesOpenApiDefinition() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("DocWeave API"));
    }

    @Test
    void exposesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void returnsAStandardErrorForInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType("application/json")
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("title"));
    }

    @Test
    void preservesNotFoundStatus() throws Exception {
        mockMvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void preservesMethodNotAllowedStatusAndHeader() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void preservesUnsupportedMediaTypeStatus() throws Exception {
        mockMvc.perform(post("/test/validation").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void correlatesUnexpectedErrorResponseWithLoggedStackTrace(CapturedOutput output) throws Exception {
        var response = mockMvc.perform(get("/test/validation/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();
        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(response.getContentAsString())
                .contains(requestId)
                .doesNotContain("IllegalStateException", "synthetic failure for logging verification");
        assertThat(output.getOut())
                .contains("ERROR", requestId, "Unhandled request failure", "java.lang.IllegalStateException",
                        "synthetic failure for logging verification");
        assertThat(MDC.get("requestId")).isNull();
    }

    @RestController
    @RequestMapping("/test/validation")
    static class ValidationTestController {

        @GetMapping("/failure")
        void failForLoggingVerification() {
            throw new IllegalStateException("synthetic failure for logging verification");
        }

        @PostMapping
        void validate(@Valid @RequestBody ValidationRequest request) {
        }
    }

    record ValidationRequest(@NotBlank String title) {
    }
}
