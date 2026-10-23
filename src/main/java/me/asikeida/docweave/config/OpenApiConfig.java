package me.asikeida.docweave.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 定义自动生成接口文档时展示的项目元数据。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI docWeaveOpenApi() {
        return new OpenAPI().info(new Info()
                .title("DocWeave API")
                .description("DocWeave 智能文档知识库的 HTTP 接口")
                .version("v1"));
    }
}
