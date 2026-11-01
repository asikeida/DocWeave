package me.asikeida.docweave.organization;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import me.asikeida.docweave.auth.service.RegistrationService;

class RegistrationAdmissionTests {
    private RegistrationService service(MockEnvironment env) { return new RegistrationService(null,null,null,Clock.systemUTC(),env); }
    @Test void registrationIsClosedByDefaultAndProductionAlwaysRefuses() {
        var env=new MockEnvironment(); env.setActiveProfiles("local");
        assertThatThrownBy(()->service(env).checkAdmission("test")).hasMessageContaining("未开放");
        env.setProperty("docweave.registration.enabled","true");env.setActiveProfiles("local","prod");
        assertThatThrownBy(()->service(env).checkAdmission("test")).hasMessageContaining("未开放");
    }
    @Test void sixthAttemptIsRejectedAndAnotherSourceHasIndependentQuota() {
        var env=new MockEnvironment().withProperty("docweave.registration.enabled","true");env.setActiveProfiles("test");
        var registration=service(env);
        for(int i=0;i<5;i++){registration.checkAdmission("one");}
        assertThatThrownBy(()->registration.checkAdmission("one")).hasMessageContaining("过于频繁");
        registration.checkAdmission("two");
    }
}
