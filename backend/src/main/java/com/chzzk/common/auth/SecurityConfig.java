package com.chzzk.common.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authentication and the URL-level access policy (ADR-0003): server sessions (stored in PostgreSQL by Spring
 * Session JDBC, cookie settings in {@code application.yml}), a cookie-to-header CSRF token, and every 401/403 as the
 * shared Problem Details.
 *
 * <ul>
 *   <li><b>Session:</b> the logged-in 회원 is a {@link MemberRef} principal saved through
 *       {@link #securityContextRepository()} (the member module's login and signup do this). Features read it only
 *       through {@link CurrentMember}.</li>
 *   <li><b>CSRF:</b> {@code GET /api/auth/csrf} sets the readable {@code XSRF-TOKEN} cookie; every state-changing
 *       request must echo it as {@code X-XSRF-TOKEN}. Login and signup issue a fresh token. The MediaMTX callback is exempt: it is no browser, and is
 *       guarded by its shared secret (see {@code broadcast.web.MediaServerAuthController}).</li>
 *   <li><b>Access:</b> every URL is reachable here; login is enforced where the data is, by domain services calling
 *       {@link CurrentMember#require()} (401), and ownership/authorship the same way ({@code ForbiddenException},
 *       403). Add URL rules below only for whole areas that need login.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String MEDIA_SERVER_CALLBACK = "/internal/mediamtx/auth";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems,
                                    SecurityContextRepository securityContextRepository,
                                    CsrfTokenRepository csrfTokens) throws Exception {
        http
                .authorizeHttpRequests(access -> access
                        .requestMatchers(MEDIA_SERVER_CALLBACK).permitAll() // shared secret, checked in the controller
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        // The token as the cookie holds it (no BREACH masking): it never appears in a response body
                        // other than GET /api/auth/csrf, and the SPA echoes the cookie value as is.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(MEDIA_SERVER_CALLBACK))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout") // POST only, CSRF-protected; invalidates the session
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) ->
                                problems.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) ->
                                problems.resolveException(request, response, null, e)))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The readable {@code XSRF-TOKEN} cookie. {@link SessionLogin} replaces the token on login and signup (manual
     * login skips Spring's own {@code CsrfAuthenticationStrategy}), so a token known before login is useless after.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return csrfTokens;
    }

    /** Where a login is kept: the HTTP session, which Spring Session stores in PostgreSQL. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
