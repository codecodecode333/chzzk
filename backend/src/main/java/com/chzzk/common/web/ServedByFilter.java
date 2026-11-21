package com.chzzk.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every response with {@code X-Served-By}: the name of the backend instance that served it (ADR-0005), so the
 * load spread across instances is visible. Runs before everything else, Spring Security included, so 401/403 and
 * other error responses carry it too.
 *
 * <p>The name is {@code instance.name} ({@code HOSTNAME}, which Kubernetes sets to the pod name), or this machine's
 * host name when that is blank.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServedByFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Served-By";

    private final String instanceName;

    ServedByFilter(@Value("${instance.name:}") String configuredName) {
        this.instanceName = configuredName.isBlank() ? localHostName() : configuredName.strip();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceName);
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false; // the container's error page dispatch must keep the header as well
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
