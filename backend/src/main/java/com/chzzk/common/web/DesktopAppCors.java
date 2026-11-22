package com.chzzk.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The only CORS this backend allows (ADR-0006): the 데스크톱 앱's own origin ({@code desktop-app.origin}, env
 * {@code DESKTOP_APP_ORIGIN}, default {@code app://chzzk}) may {@code GET} the public 방송 list and detail, without
 * credentials. Any other origin or endpoint gets no CORS configuration at all, so the request goes on as before and a
 * browser refuses to hand a cross-origin response to its page; a non-{@code GET} from the app is refused outright.
 * Requests from other origins are left alone on purpose: the web SPA's own requests pass through proxies (Vite, nginx,
 * Caddy) whose address differs from the page's, and must not be judged as cross-origin here.
 */
@Component
public class DesktopAppCors implements CorsConfigurationSource {

    private static final List<PathPattern> PUBLIC_BROADCAST_READS = List.of(
            PathPatternParser.defaultInstance.parse("/api/broadcasts"),
            PathPatternParser.defaultInstance.parse("/api/broadcasts/{id}"));

    private final String origin;
    private final CorsConfiguration publicReads;

    DesktopAppCors(@Value("${desktop-app.origin}") String origin) {
        this.origin = origin;
        this.publicReads = new CorsConfiguration();
        publicReads.addAllowedOrigin(origin);
        publicReads.addAllowedMethod(HttpMethod.GET);
        publicReads.setAllowCredentials(false);
        publicReads.setMaxAge(Duration.ofHours(1));
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN))) return null;
        PathContainer path = PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length()));
        return PUBLIC_BROADCAST_READS.stream().anyMatch(p -> p.matches(path)) ? publicReads : null;
    }
}
