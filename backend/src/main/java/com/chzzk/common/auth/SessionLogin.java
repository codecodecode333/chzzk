package com.chzzk.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/** Makes the current browser session logged in as a 회원. Only the member module's signup and login call it. */
@Component
public class SessionLogin {

    private final SecurityContextRepository repository;
    private final CsrfTokenRepository csrfTokens;

    SessionLogin(SecurityContextRepository repository, CsrfTokenRepository csrfTokens) {
        this.repository = repository;
        this.csrfTokens = csrfTokens;
    }

    public void logIn(MemberRef member, HttpServletRequest request, HttpServletResponse response) {
        // A new session id on login, so a session id planted before login is worthless afterwards.
        if (request.getSession(false) != null) request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null, List.of()));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        rotateCsrfToken(request, response);
    }

    /**
     * A fresh CSRF token (a new {@code XSRF-TOKEN} cookie), as Spring's {@code CsrfAuthenticationStrategy} would do for
     * its own login: a token known before login stops working. The SPA reads the cookie on every write.
     */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken fresh = csrfTokens.generateToken(request);
        csrfTokens.saveToken(fresh, request, response);
        request.setAttribute(CsrfToken.class.getName(), fresh);
        request.setAttribute(fresh.getParameterName(), fresh);
    }
}
