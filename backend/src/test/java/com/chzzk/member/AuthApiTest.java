package com.chzzk.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.support.ApiSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP API seam for 회원가입·로그인·로그아웃: full Spring context against Testcontainers PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void signingUpLogsTheNewMemberIn() {
        ApiSession browser = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> response = browser.post("/api/auth/signup",
                Map.of("email", "first@example.com", "password", "password1234", "nickname", "  첫회원  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map<String, Object>> me = browser.get("/api/me");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("nickname", "첫회원").containsKey("id");
        assertThat(response.getBody()).containsEntry("nickname", "첫회원").containsEntry("id", me.getBody().get("id"));
    }

    @Test
    void theSessionCookieIsHttpOnlyAndSameSiteLax() {
        ResponseEntity<Map<String, Object>> response = ApiSession.visitor(http).post("/api/auth/signup",
                Map.of("email", "cookie@example.com", "password", "password1234", "nickname", "쿠키회원"));

        String sessionCookie = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void invalidSignupNamesEveryInvalidField() {
        ResponseEntity<Map<String, Object>> response = ApiSession.visitor(http).post("/api/auth/signup",
                Map.of("email", "not-an-email", "password", "short", "nickname", " 김 "));

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(response)).containsExactly(
                Map.of("field", "email", "message", "이메일 형식이 올바르지 않습니다."),
                Map.of("field", "password", "message", "비밀번호는 8자 이상 64자 이하로 입력해 주세요."),
                Map.of("field", "nickname", "message", "닉네임은 2자 이상 20자 이하로 입력해 주세요."));
    }

    @Test
    void signupLengthLimitsAreInclusive() {
        assertThat(signUp("limits-ok@example.com", "a".repeat(64), "가".repeat(20)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(signUp("limits-ok2@example.com", "a".repeat(8), "가나").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> tooLong = signUp("limits-bad@example.com", "a".repeat(65), "가".repeat(21));
        assertProblem(tooLong, HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(tooLong)).extracting(e -> e.get("field")).containsExactly("password", "nickname");
    }

    @Test
    void aTakenEmailIsAConflictWhateverItsCase() {
        ApiSession.signUp(http, "Taken@Example.com", "password1234", "먼저온회원");

        ResponseEntity<Map<String, Object>> response = signUp("  taken@EXAMPLE.com ", "password1234", "나중온회원");

        assertProblem(response, HttpStatus.CONFLICT);
        assertThat(fieldErrors(response)).containsExactly(Map.of("field", "email", "message", "이미 가입된 이메일입니다."));
    }

    @Test
    void aTakenNicknameIsAConflict() {
        ApiSession.signUp(http, "nick1@example.com", "password1234", "같은닉네임");

        ResponseEntity<Map<String, Object>> response = signUp("nick2@example.com", "password1234", " 같은닉네임 ");

        assertProblem(response, HttpStatus.CONFLICT);
        assertThat(fieldErrors(response))
                .containsExactly(Map.of("field", "nickname", "message", "이미 사용 중인 닉네임입니다."));
    }

    @Test
    void aMemberLogsInFromAnotherBrowserWithAnyEmailCase() {
        ApiSession.signUp(http, "login@example.com", "password1234", "로그인회원");
        ApiSession browser = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> response = browser.post("/api/auth/login",
                Map.of("email", "LOGIN@example.com", "password", "password1234"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(browser.get("/api/me").getBody()).containsEntry("nickname", "로그인회원");
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameMessage() {
        ApiSession.signUp(http, "wrong@example.com", "password1234", "틀린비번회원");
        ApiSession browser = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> wrongPassword = browser.post("/api/auth/login",
                Map.of("email", "wrong@example.com", "password", "password9999"));
        ResponseEntity<Map<String, Object>> unknownEmail = browser.post("/api/auth/login",
                Map.of("email", "nobody@example.com", "password", "password1234"));

        for (ResponseEntity<Map<String, Object>> response : List.of(wrongPassword, unknownEmail)) {
            assertProblem(response, HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).containsEntry("detail", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        assertThat(browser.get("/api/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aVisitorIsNotLoggedIn() {
        ResponseEntity<Map<String, Object>> me = ApiSession.visitor(http).get("/api/me");

        assertProblem(me, HttpStatus.UNAUTHORIZED);
        assertThat(me.getBody()).containsEntry("detail", "로그인이 필요합니다.");
    }

    @Test
    void logoutEndsTheSessionOnTheServer() {
        ApiSession member = ApiSession.member(http);
        ApiSession stolenCookie = member.copy();

        ResponseEntity<Map<String, Object>> response = member.post("/api/auth/logout", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(member.get("/api/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stolenCookie.get("/api/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aWriteWithoutTheCsrfTokenIsRejected() {
        ApiSession member = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> signup = ApiSession.visitor(http).withoutCsrfToken().post(
                "/api/auth/signup", Map.of("email", "csrf@example.com", "password", "password1234", "nickname", "씨에스"));
        ResponseEntity<Map<String, Object>> logout = member.withoutCsrfToken().post("/api/auth/logout", null);

        assertProblem(signup, HttpStatus.FORBIDDEN);
        assertProblem(logout, HttpStatus.FORBIDDEN);
        assertThat(member.get("/api/me").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void signupAndLoginIssueAFreshCsrfTokenAndThePreLoginOneStopsWorking() {
        ApiSession browser = ApiSession.visitor(http);
        String beforeSignup = browser.csrfToken();

        assertThat(browser.post("/api/auth/signup", Map.of("email", "rotate@example.com", "password", "password1234",
                "nickname", "회전회원")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String afterSignup = browser.csrfToken();
        assertThat(afterSignup).isNotBlank().isNotEqualTo(beforeSignup);
        assertProblem(browser.withCsrfHeader(beforeSignup).post("/api/broadcasts", Map.of("title", "옛 토큰")),
                HttpStatus.FORBIDDEN);

        ApiSession other = ApiSession.visitor(http);
        String beforeLogin = other.csrfToken();
        other.logIn("rotate@example.com", "password1234");

        assertThat(other.csrfToken()).isNotBlank().isNotEqualTo(beforeLogin);
        assertProblem(other.withCsrfHeader(beforeLogin).post("/api/auth/logout", null), HttpStatus.FORBIDDEN);
        assertThat(other.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void meAndSignupAreJustIdAndNickname() {
        ApiSession member = ApiSession.member(http);

        assertThat(member.get("/api/me").getBody()).containsOnlyKeys("id", "nickname");
        assertThat(ApiSession.visitor(http).post("/api/auth/signup", Map.of("email", "shape@example.com",
                "password", "password1234", "nickname", "모양회원")).getBody()).containsOnlyKeys("id", "nickname");
    }

    @Test
    void theLoginHelperActsAsTheMemberItSignedUp() {
        ApiSession member = ApiSession.member(http);
        ApiSession sameMemberElsewhere = ApiSession.visitor(http)
                .logIn(member.member().email(), member.member().password());

        assertThat(sameMemberElsewhere.member()).isEqualTo(member.member());
    }

    ResponseEntity<Map<String, Object>> signUp(String email, String password, String nickname) {
        return ApiSession.visitor(http).post("/api/auth/signup",
                Map.of("email", email, "password", password, "nickname", nickname));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fieldErrors(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("errors");
    }

    static void assertProblem(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("status", status.value())
                .containsKeys("type", "title", "detail", "instance");
    }
}
