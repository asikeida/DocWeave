package me.asikeida.docweave.ingestion;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

@EnabledIfEnvironmentVariable(named="DOCWEAVE_TEST_MYSQL_URL", matches=".+")
@TestPropertySource(properties={"spring.datasource.url=${DOCWEAVE_TEST_MYSQL_URL}","spring.datasource.username=${DOCWEAVE_TEST_MYSQL_USERNAME}","spring.datasource.password=${DOCWEAVE_TEST_MYSQL_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"})
class MySqlProcessingTests extends ProcessingTests { }
