package com.chzzk.member.domain;

import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.error.ConflictException;
import com.chzzk.common.error.UnauthenticatedException;
import com.chzzk.member.domain.MemberRules.SignupInput;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 회원가입 and credential checks. Owns the email/password/닉네임 rules and password hashing. */
@Service
public class MemberService {

    static final String EMAIL_TAKEN = "이미 가입된 이메일입니다.";
    static final String NICKNAME_TAKEN = "이미 사용 중인 닉네임입니다.";
    /** One message for an unknown email and a wrong password alike, so emails can't be probed. */
    static final String BAD_CREDENTIALS = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private final MemberRepository members;
    private final PasswordEncoder passwords;
    /** Checked against when the email is unknown, so both failures cost one BCrypt comparison. */
    private final String dummyHash;

    MemberService(MemberRepository members, PasswordEncoder passwords) {
        this.members = members;
        this.passwords = passwords;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    /**
     * Creates a 회원. Invalid input → {@link InvalidMemberException} (400); a taken email (case-insensitively) or
     * 닉네임 → {@link ConflictException} naming the field (409).
     */
    public MemberRef signUp(String email, String password, String nickname) {
        SignupInput input = MemberRules.validate(email, password, nickname);
        if (members.existsByEmail(input.email())) throw ConflictException.onField("email", EMAIL_TAKEN);
        if (members.existsByNickname(input.nickname())) throw ConflictException.onField("nickname", NICKNAME_TAKEN);
        Member member = new Member(input.email(), passwords.encode(input.password()), input.nickname(), Instant.now());
        try {
            return members.saveAndFlush(member).toRef();
        } catch (DataIntegrityViolationException e) {
            // A concurrent signup took it between the check and the insert; the unique indexes are the backstop.
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("uq_member_nickname")) throw ConflictException.onField("nickname", NICKNAME_TAKEN);
            if (cause.contains("uq_member_email")) throw ConflictException.onField("email", EMAIL_TAKEN);
            throw e;
        }
    }

    /** The 회원 with these credentials; anything else → {@link UnauthenticatedException} (401) with one message. */
    public MemberRef authenticate(String email, String password) {
        Optional<Member> member = members.findByEmail(MemberRules.normalizeEmail(email));
        String raw = password == null ? "" : password;
        boolean matches = passwords.matches(raw, member.map(Member::getPasswordHash).orElse(dummyHash));
        if (member.isEmpty() || !matches) throw new UnauthenticatedException(BAD_CREDENTIALS);
        return member.get().toRef();
    }
}
