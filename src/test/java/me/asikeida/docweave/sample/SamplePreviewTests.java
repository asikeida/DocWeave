package me.asikeida.docweave.sample;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.auth.service.RegistrationService;
import me.asikeida.docweave.auth.service.AuthService;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SamplePreviewTests {
    @Autowired SampleCatalog catalog;
    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    @Test void resourcesHaveStableDigestAndAreNotMarkedSearchable() throws Exception {
        assertThat(catalog.list()).hasSize(2);
        for(var sample:catalog.list()) {
            var preview=catalog.preview(sample.id());
            assertThat(preview.content()).contains("虚构示例资料");
            assertThat(sample.readyForChat()).isFalse();
            assertThat(sample.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preview.content().getBytes(StandardCharsets.UTF_8))));
        }
        assertThatThrownBy(()->catalog.preview("../../application.yaml")).hasMessage("示例资料不存在");
    }
    @Test void endpointsRequireIdentityAndAnActivePersonalArea() throws Exception {
        mvc.perform(get("/api/v1/personal-area/samples")).andExpect(status().isUnauthorized());
        String user="sample-"+UUID.randomUUID().toString().substring(0,8);
        var area=registration.register(new RegistrationRequest(user,"sample-password-123","示例体验"));
        String token=auth.login(user,"sample-password-123",null).response().accessToken();
        mvc.perform(get("/api/v1/personal-area/samples").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/personal-area/samples/employee-handbook").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sample.readyForChat").value(false)).andExpect(jsonPath("$.format").value("plain-text"));
        mvc.perform(get("/api/v1/personal-area/samples/unknown").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        jdbc.update("UPDATE organization_memberships SET active=FALSE WHERE tenant_id=?",area.organizationId());
        mvc.perform(get("/api/v1/personal-area/samples/employee-handbook").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
}
