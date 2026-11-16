package com.chzzk.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * One browser talking to the API, the way the SPA does: it keeps its own cookies (the {@code SESSION} cookie and the
 * {@code XSRF-TOKEN} cookie) and echoes the CSRF token as {@code X-XSRF-TOKEN} on every request. Tests act as a
 * visitor or as a 회원 only through the real auth API, never by touching sessions or the security context.
 *
 * <pre>{@code
 * ApiSession visitor = ApiSession.visitor(http);          // anonymous, CSRF token fetched
 * ApiSession owner = ApiSession.member(http);             // a fresh 회원, signed up and logged in
 * ApiSession other = ApiSession.member(http);             // another 회원
 * owner.post("/api/broadcasts", Map.of("title", "방송")); // JSON in, Map out
 * owner.exchange("/api/vods", HttpMethod.POST, new HttpEntity<>(form, headers), JSON); // any body
 * }</pre>
 *
 * Each instance has its own cookies, so several 회원 can act in one test. TestRestTemplate itself keeps no cookies.
 */
public final class ApiSession {

    public static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    /** The password {@link #member} signs up with. */
    public static final String PASSWORD = "password1234";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final TestRestTemplate http;
    private final Map<String, String> cookies;
    private final boolean sendsCsrfToken;
    /** Sent as the CSRF header instead of the cookie's token, when set. */
    private final String csrfHeaderOverride;
    private Member member;

    /** Who this session signed up as: its id and 닉네임 as {@code /api/me} reports them, and its credentials. */
    public record Member(long id, String nickname, String email, String password) {}

    private ApiSession(TestRestTemplate http, Map<String, String> cookies, boolean sendsCsrfToken,
                       String csrfHeaderOverride, Member member) {
        this.http = http;
        this.cookies = cookies;
        this.sendsCsrfToken = sendsCsrfToken;
        this.csrfHeaderOverride = csrfHeaderOverride;
        this.member = member;
    }

    /** A logged-out browser that has fetched its CSRF token ({@code GET /api/auth/csrf}). */
    public static ApiSession visitor(TestRestTemplate http) {
        ApiSession session = new ApiSession(http, new LinkedHashMap<>(), true, null, null);
        assertThat(session.get("/api/auth/csrf").getStatusCode().is2xxSuccessful()).isTrue();
        return session;
    }

    /** A fresh 회원 with a unique email and 닉네임, signed up (and so logged in) through {@code /api/auth/signup}. */
    public static ApiSession member(TestRestTemplate http) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return signUp(http, "member-" + unique + "@example.com", PASSWORD, "회원" + unique);
    }

    /** Signs up through the API with exactly these values, asserting {@code 201}; the session is logged in. */
    public static ApiSession signUp(TestRestTemplate http, String email, String password, String nickname) {
        ApiSession session = visitor(http);
        ResponseEntity<Map<String, Object>> response = session.post("/api/auth/signup",
                Map.of("email", email, "password", password, "nickname", nickname));
        assertThat(response.getStatusCode()).as("signup: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        session.member = session.me(email, password);
        return session;
    }

    /** Logs this session in through {@code /api/auth/login}, asserting {@code 204}. */
    public ApiSession logIn(String email, String password) {
        ResponseEntity<Map<String, Object>> response = post("/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(response.getStatusCode()).as("login: %s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        member = me(email, password);
        return this;
    }

    private Member me(String email, String password) {
        Map<String, Object> me = get("/api/me").getBody();
        assertThat(me).containsKeys("id", "nickname");
        return new Member(((Number) me.get("id")).longValue(), (String) me.get("nickname"), email, password);
    }

    /** The 회원 this session signed up or logged in as. */
    public Member member() {
        assertThat(member).as("this session never logged in").isNotNull();
        return member;
    }

    /** Another browser holding a copy of this one's cookies right now (e.g. a stolen or stale session cookie). */
    public ApiSession copy() {
        return new ApiSession(http, new LinkedHashMap<>(cookies), sendsCsrfToken, csrfHeaderOverride, member);
    }

    /** This browser's cookies, but its writes carry no CSRF header (as a cross-site form post would). */
    public ApiSession withoutCsrfToken() {
        return new ApiSession(http, cookies, false, null, member);
    }

    /** This browser's cookies, but its writes echo {@code token} as the CSRF header (e.g. a token from before login). */
    public ApiSession withCsrfHeader(String token) {
        return new ApiSession(http, cookies, true, token, member);
    }

    /** The CSRF token this browser holds right now (its {@code XSRF-TOKEN} cookie). */
    public String csrfToken() {
        return cookies.get(CSRF_COOKIE);
    }

    public ResponseEntity<Map<String, Object>> get(String path) {
        return exchange(path, HttpMethod.GET, null, JSON);
    }

    public ResponseEntity<Map<String, Object>> post(String path, Object jsonBody) {
        return exchange(path, HttpMethod.POST, jsonBody, JSON);
    }

    public ResponseEntity<Map<String, Object>> patch(String path, Object jsonBody) {
        return exchange(path, HttpMethod.PATCH, jsonBody, JSON);
    }

    public ResponseEntity<Map<String, Object>> delete(String path) {
        return exchange(path, HttpMethod.DELETE, null, JSON);
    }

    /**
     * Any request as this browser. {@code body} is the request body, or an {@link HttpEntity} whose headers are kept
     * (e.g. a multipart form, or {@code Authorization}); this session's cookie and CSRF headers are added to them.
     */
    public <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body,
                                          ParameterizedTypeReference<T> responseType) {
        ResponseEntity<T> response = http.exchange(path, method, entity(body), responseType);
        remember(response.getHeaders());
        return response;
    }

    /** As {@link #exchange(String, HttpMethod, Object, ParameterizedTypeReference)}, for a plain response type. */
    public <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body, Class<T> responseType) {
        ResponseEntity<T> response = http.exchange(path, method, entity(body), responseType);
        remember(response.getHeaders());
        return response;
    }

    /** The {@code Cookie} and CSRF headers this browser sends, for requests made without TestRestTemplate. */
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        if (!cookies.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, String.join("; ",
                    cookies.entrySet().stream().map(c -> c.getKey() + "=" + c.getValue()).toList()));
        }
        String csrf = csrfHeaderOverride != null ? csrfHeaderOverride : cookies.get(CSRF_COOKIE);
        if (sendsCsrfToken && csrf != null) headers.add(CSRF_HEADER, csrf);
        return headers;
    }

    private HttpEntity<?> entity(Object body) {
        HttpHeaders headers = headers();
        Object payload = body;
        if (body instanceof HttpEntity<?> given) {
            headers.addAll(given.getHeaders());
            payload = given.getBody();
        }
        return new HttpEntity<>(payload, headers);
    }

    private void remember(HttpHeaders responseHeaders) {
        List<String> setCookies = responseHeaders.getOrEmpty(HttpHeaders.SET_COOKIE);
        for (String header : setCookies) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            }
        }
    }
}
