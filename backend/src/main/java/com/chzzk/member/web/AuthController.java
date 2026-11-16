package com.chzzk.member.web;

import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.auth.SessionLogin;
import com.chzzk.member.domain.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입, 로그인 and the current 회원. 로그아웃 ({@code POST /api/auth/logout}) is Spring Security's logout filter,
 * configured in {@code common.auth.SecurityConfig}.
 */
@RestController
class AuthController {

    private final MemberService members;
    private final SessionLogin sessionLogin;
    private final CurrentMember currentMember;

    AuthController(MemberService members, SessionLogin sessionLogin, CurrentMember currentMember) {
        this.members = members;
        this.sessionLogin = sessionLogin;
        this.currentMember = currentMember;
    }

    /** Sets the {@code XSRF-TOKEN} cookie (if not set yet); the token is also in the body for non-browser clients. */
    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    record SignupRequest(String email, String password, String nickname) {}

    /** {@code 201} with {@code {id, nickname}}, and the browser is logged in as the new 회원. */
    @PostMapping("/api/auth/signup")
    ResponseEntity<MemberRef> signUp(@RequestBody SignupRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        MemberRef member = members.signUp(body.email(), body.password(), body.nickname());
        sessionLogin.logIn(member, request, response);
        return ResponseEntity.created(URI.create("/api/me")).body(member);
    }

    record LoginRequest(String email, String password) {}

    @PostMapping("/api/auth/login")
    ResponseEntity<Void> logIn(@RequestBody LoginRequest body,
                               HttpServletRequest request, HttpServletResponse response) {
        sessionLogin.logIn(members.authenticate(body.email(), body.password()), request, response);
        return ResponseEntity.noContent().build();
    }

    /** {@code {id, nickname}} of the logged-in 회원; {@code 401} for a visitor. */
    @GetMapping("/api/me")
    MemberRef me() {
        return currentMember.require();
    }
}
