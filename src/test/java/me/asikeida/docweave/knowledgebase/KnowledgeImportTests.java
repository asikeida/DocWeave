package me.asikeida.docweave.knowledgebase;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import me.asikeida.docweave.auth.service.RegistrationService;import me.asikeida.docweave.auth.service.AuthService;
import me.asikeida.docweave.auth.dto.RegistrationRequest;import me.asikeida.docweave.knowledgebase.service.KnowledgeService;
import me.asikeida.docweave.knowledgebase.repository.KnowledgeRepository;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import me.asikeida.docweave.user.repository.UserAccountRepository;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class KnowledgeImportTests {
    @Autowired RegistrationService registration;@Autowired AuthService auth;@Autowired KnowledgeService service;
    @Autowired KnowledgeRepository repository;@Autowired PersonalAreaService areas;@Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired PlatformTransactionManager transactions;
    String user(){String name="kb-"+UUID.randomUUID().toString().substring(0,8);registration.register(new RegistrationRequest(name,"knowledge-password-123","知识用户"));return users.findByUsername(name).orElseThrow().getId();}
    @Test void importsTwoOwnedVersionsAndDoesNotRepeat() {
        String user=user();assertThat(service.list(user)).isEmpty();
        var kb=service.initialize(user).getFirst();var docs=service.documents(user,kb.id());
        assertThat(docs).hasSize(2);assertThat(kb.documentCount()).isEqualTo(2);
        assertThat(service.document(user,docs.getFirst().id()).content()).contains("虚构示例资料");
        assertThat(docs).allSatisfy(d->assertThat(d.indexState()).isEqualTo("NOT_INDEXED"));
        assertThat(service.initialize(user)).containsExactly(kb);
        assertThat(areas.current(user).sampleState()).isEqualTo("IMPORTED_NOT_INDEXED");
    }
    @Test void usersHaveIndependentIdsAndCannotGuessOtherUsersResources() {
        String one=user(),two=user();var first=service.initialize(one).getFirst();var second=service.initialize(two).getFirst();
        var doc=service.documents(one,first.id()).getFirst();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(service.documents(two,second.id()).getFirst().id()).isNotEqualTo(doc.id());
        assertThatThrownBy(()->service.knowledgeBase(two,first.id())).hasMessageContaining("不可访问");
        assertThatThrownBy(()->service.document(two,doc.id())).hasMessageContaining("不可访问");
        jdbc.update("DELETE FROM workspace_grants WHERE user_id=?",one);
        assertThatThrownBy(()->service.document(one,doc.id())).hasMessageContaining("不可用");
    }
    @Test void concurrentInitializationIsIdempotent() throws Exception {
        String user=user();try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->service.initialize(user));var second=executor.submit(()->service.initialize(user));
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(service.documents(user,service.list(user).getFirst().id())).hasSize(2);
    }
    @Test void partialImportFailureRollsBackAndCanRetry() {
        String user=user();var area=areas.current(user);int before=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases",Integer.class);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{service.initialize(user);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(service.list(user)).isEmpty();assertThat(areas.current(user).sampleState()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases",Integer.class)).isEqualTo(before);
        assertThat(service.initialize(user)).hasSize(1);
        jdbc.update("UPDATE documents SET deleted=TRUE WHERE tenant_id=?",area.organizationId());
        service.initialize(user);assertThat(service.documents(user,service.list(user).getFirst().id())).isEmpty();
    }
    @Test void httpRejectsAnonymousCsrfAndCrossTenantIds() throws Exception {
        String one=user(),two=user();String name=users.findById(two).orElseThrow().getUsername();
        var kb=service.initialize(one).getFirst();String token=auth.login(name,"knowledge-password-123",null).response().accessToken();
        mvc.perform(get("/api/v1/knowledge-bases")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/personal-area/sample-import").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/knowledge-bases/"+kb.id()).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void failureBackoffIsPersistedAndSuccessfulManualRetryClearsIt() {
        String user=user();repository.failed(user,java.time.Instant.now().plusSeconds(600));
        assertThat(repository.pending(java.time.Instant.now())).doesNotContain(user);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM personal_sample_initializations WHERE tenant_id=?",Integer.class,areas.current(user).organizationId())).isOne();
        assertThat(service.initialize(user)).hasSize(1);
        assertThat(repository.pending(java.time.Instant.now().plusSeconds(900))).doesNotContain(user);
    }
}
