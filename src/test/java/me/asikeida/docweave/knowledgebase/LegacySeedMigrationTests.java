package me.asikeida.docweave.knowledgebase;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;import org.junit.jupiter.api.Test;
class LegacySeedMigrationTests {
    @Test void v2PendingRowSurvivesV3WithNewDefaults() throws Exception {
        try(var pool=new HikariDataSource()){
            pool.setJdbcUrl("jdbc:h2:mem:legacy_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");pool.setUsername("sa");pool.setPassword("");pool.setMaximumPoolSize(2);
            // 与应用一致复用连接池，避免H2的CHECK表达式引用已关闭的建表连接。
            Flyway.configure().dataSource(pool).target("2").load().migrate();
            try(var connection=pool.getConnection();var sql=connection.createStatement()){
                sql.executeUpdate("INSERT INTO users VALUES ('u','legacy','hash','旧账号','ACTIVE',CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO organizations VALUES ('t','u','旧个人组织',TRUE,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO personal_sample_initializations VALUES ('t','v1','WAITING_FEATURE',CURRENT_TIMESTAMP)");
            }
            Flyway.configure().dataSource(pool).load().migrate();
            try(var connection=pool.getConnection();var sql=connection.createStatement();var rs=sql.executeQuery("SELECT state,import_state,attempt_count FROM personal_sample_initializations WHERE tenant_id='t'")){
                assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("WAITING_FEATURE");assertThat(rs.getString(2)).isEqualTo("PENDING");assertThat(rs.getInt(3)).isZero();
            }
        }
    }
}
