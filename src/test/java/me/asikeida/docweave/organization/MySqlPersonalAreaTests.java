package me.asikeida.docweave.organization;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

/** 同一契约在显式提供的独立MySQL库复测，不默认连接开发库。 */
@EnabledIfEnvironmentVariable(named="DOCWEAVE_TEST_MYSQL_URL", matches=".+")
@TestPropertySource(properties={
    "spring.datasource.url=${DOCWEAVE_TEST_MYSQL_URL}",
    "spring.datasource.username=${DOCWEAVE_TEST_MYSQL_USERNAME}",
    "spring.datasource.password=${DOCWEAVE_TEST_MYSQL_PASSWORD}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
class MySqlPersonalAreaTests extends PersonalAreaTests { }
