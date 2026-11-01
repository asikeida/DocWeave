package me.asikeida.docweave.organization;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.auth.service.RegistrationService;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "docweave.registration.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PersonalAreaTests {
    @Autowired RegistrationService registration;
    @Autowired me.asikeida.docweave.auth.service.AuthService auth;
    @Autowired PersonalAreaService areas;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactions;
    RegistrationRequest request() { return new RegistrationRequest("new-"+UUID.randomUUID().toString().substring(0,8), "test-password-12345", "测试用户"); }

    @Test void createsCompleteAreaAndNoPlaintextPassword() {
        var request=request(); var area=registration.register(request);
        var user=users.findByUsername(request.username()).orElseThrow();
        assertThat(user.getPasswordHash()).doesNotContain(request.password()).startsWith("{bcrypt}");
        assertThat(area.sampleState()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_memberships WHERE tenant_id=? AND user_id=? AND role='OWNER'", Integer.class, area.organizationId(),user.getId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_grants WHERE tenant_id=? AND user_id=? AND role='ADMIN'", Integer.class,area.organizationId(),user.getId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_events WHERE tenant_id=?",Integer.class,area.organizationId())).isOne();
    }
    @Test void transactionFailureLeavesNoAccountOrOrphanArea() {
        var request=request(); int before=jdbc.queryForObject("SELECT COUNT(*) FROM organizations",Integer.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{registration.register(request);throw new IllegalStateException("rollback probe");})).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByUsername(request.username())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations",Integer.class)).isEqualTo(before);
    }
    @Test void canonicalDuplicateIsRejectedAndPasswordsHaveByteLimit() {
        var request=request(); registration.register(request);
        assertThatThrownBy(()->registration.register(new RegistrationRequest(request.username().toUpperCase(),request.password(),"重复"))).isInstanceOf(AuthFailure.class);
        assertThatThrownBy(()->registration.register(new RegistrationRequest(request().username(),"中".repeat(25),"字节限制"))).isInstanceOf(AuthFailure.class);
    }
    @Test void concurrentInitializationReusesSameArea() throws Exception {
        var request=request(); var original=registration.register(request);var user=users.findByUsername(request.username()).orElseThrow();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->areas.initialize(user.getId())); var two=executor.submit(()->areas.initialize(user.getId()));
            assertThat(one.get()).isEqualTo(original);assertThat(two.get()).isEqualTo(original);
        }
        jdbc.update("UPDATE organization_memberships SET active=FALSE WHERE tenant_id=?",original.organizationId());
        assertThatThrownBy(()->areas.initialize(user.getId())).isInstanceOf(AuthFailure.class);
    }
    @Test void databaseRejectsCrossTenantMemberAndGrant() {
        var one=request();var two=request();var first=registration.register(one);var second=registration.register(two);
        String other=users.findByUsername(two.username()).orElseThrow().getId();
        assertThatThrownBy(()->jdbc.update("UPDATE organization_memberships SET user_id=? WHERE tenant_id=?",other,first.organizationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO workspace_grants VALUES (?,?,?,'ADMIN')",first.organizationId(),second.workspaceId(),other)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(areas.current(users.findByUsername(one.username()).orElseThrow().getId())).isEqualTo(first);
    }
    @Test void authenticatedReadsAreSelfScopedAndInitializeNeedsCsrf() throws Exception {
        var first=request(); var second=request();
        var area=registration.register(first); var other=registration.register(second);
        String access=auth.login(first.username(),first.password(),null).response().accessToken();
        mvc.perform(get("/api/v1/personal-area").param("userId",users.findByUsername(second.username()).orElseThrow().getId()).header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.organizationId").value(area.organizationId()))
            .andExpect(jsonPath("$.workspaceId").value(area.workspaceId()));
        mvc.perform(post("/api/v1/personal-area/initialize").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        assertThat(area.organizationId()).isNotEqualTo(other.organizationId());
    }
    @Test void legacyAccountGetsAreaOnceAndReadDoesNotWrite() {
        var request=request();
        var user=users.saveAndFlush(new me.asikeida.docweave.user.domain.UserAccount(request.username(),"not-a-login-hash","旧账号",java.time.Instant.now()));
        assertThatThrownBy(()->areas.current(user.getId())).isInstanceOf(AuthFailure.class);
        var area=areas.initialize(user.getId());
        assertThat(areas.initialize(user.getId())).isEqualTo(area);
        jdbc.update("DELETE FROM workspace_grants WHERE tenant_id=?",area.organizationId());
        assertThatThrownBy(()->areas.current(user.getId())).isInstanceOf(AuthFailure.class);
    }
    @Test void registrationRequiresCsrfAndDoesNotLogInAutomatically() throws Exception {
        var request=request();
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isForbidden());
        // 序列化writeOnly请求不会带password，实际客户端请求手工构造。
        var csrf=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        var token=json.readTree(csrf.getContentAsString()).get("token").asText();
        var result=mvc.perform(post("/api/v1/auth/register").cookie(csrf.getCookies()).header("X-XSRF-TOKEN",token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",request.username(),"password",request.password(),"displayName",request.displayName()))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sampleState").value("PENDING")).andExpect(header().exists("X-Request-Id")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken",request.password());
        mvc.perform(get("/api/v1/personal-area")).andExpect(status().isUnauthorized());
    }
}
