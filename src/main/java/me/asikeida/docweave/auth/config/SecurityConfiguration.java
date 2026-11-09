package me.asikeida.docweave.auth.config;

import java.util.List;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.auth.service.AuthService;
import me.asikeida.docweave.auth.web.SecurityErrorWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.transaction.TransactionException;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, AuthService auth,
            AuthProperties properties, SecurityErrorWriter errors) throws Exception {
        CookieCsrfTokenRepository csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(properties.cookieSecure() ? "__Host-docweave_csrf" : "docweave_csrf");
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/"));
        var authPaths = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            if (exception instanceof AuthenticationServiceException) {
                errors.write(request, response, 503, "AUTH_UNAVAILABLE", "认证服务暂时不可用，请稍后重试");
            } else {
                errors.write(request, response, 401, "UNAUTHENTICATED", "登录已失效，请重新登录");
            }
        };
        // Resource Server默认忽略Bearer请求的CSRF；这里统一使用框架CsrfFilter，明确保护所有API写请求。
        CsrfFilter csrfFilter = new CsrfFilter(csrf);
        csrfFilter.setRequireCsrfProtectionMatcher(request -> authPaths.matches(request)
                && !List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod()));
        csrfFilter.setAccessDeniedHandler((request, response, exception) ->
                errors.write(request, response, 403, "ACCESS_DENIED", "请求未获授权或安全校验未通过"));
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAt(csrfFilter, CsrfFilter.class)
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(config -> config.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(request, response, 403, "ACCESS_DENIED", "请求未获授权或安全校验未通过")))
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(request, response, 403, "ACCESS_DENIED", "请求未获授权"))
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token -> {
                            try {
                                auth.currentUser(token);
                            } catch (AuthFailure failure) {
                                throw new BadCredentialsException("登录不可用");
                            } catch (DataAccessException | TransactionException failure) {
                                throw new AuthenticationServiceException("无法核验当前登录状态");
                            }
                            return new JwtAuthenticationToken(token, List.of(), token.getSubject());
                        })))
                .build();
    }
}
