package com.chzzk.member.domain;

import com.chzzk.common.text.Text;
import com.chzzk.common.web.Problems.FieldError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The signup rules for email, password and 닉네임, mirrored by the frontend's {@code members/validateMemberForms.ts}
 * (this side stays authoritative).
 */
final class MemberRules {

    static final int EMAIL_MAX_CHARS = 254;
    static final int PASSWORD_MIN_CHARS = 8;
    static final int PASSWORD_MAX_CHARS = 64;
    /** BCrypt reads at most 72 bytes; a longer password (possible only with non-ASCII characters) is refused. */
    static final int PASSWORD_MAX_BYTES = 72;
    static final int NICKNAME_MIN_CHARS = 2;
    static final int NICKNAME_MAX_CHARS = 20;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private MemberRules() {
    }

    /** Signup input as stored: email trimmed and lower-cased, 닉네임 trimmed, password as typed. */
    record SignupInput(String email, String password, String nickname) {}

    /** The normalized input, or {@link InvalidMemberException} listing every invalid field. */
    static SignupInput validate(String email, String password, String nickname) {
        List<FieldError> errors = new ArrayList<>();
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isEmpty()) {
            errors.add(new FieldError("email", "이메일을 입력해 주세요."));
        } else if (Text.charCount(normalizedEmail) > EMAIL_MAX_CHARS || !EMAIL.matcher(normalizedEmail).matches()) {
            errors.add(new FieldError("email", "이메일 형식이 올바르지 않습니다."));
        }
        String pw = password == null ? "" : password;
        int pwChars = Text.charCount(pw);
        if (pwChars < PASSWORD_MIN_CHARS || pwChars > PASSWORD_MAX_CHARS) {
            errors.add(new FieldError("password",
                    "비밀번호는 " + PASSWORD_MIN_CHARS + "자 이상 " + PASSWORD_MAX_CHARS + "자 이하로 입력해 주세요."));
        } else if (pw.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            errors.add(new FieldError("password", "비밀번호가 너무 깁니다. 한글 등은 더 짧게 입력해 주세요."));
        }
        String trimmedNickname = nickname == null ? "" : Text.trimWhitespace(nickname);
        int nicknameChars = Text.charCount(trimmedNickname);
        if (nicknameChars < NICKNAME_MIN_CHARS || nicknameChars > NICKNAME_MAX_CHARS) {
            errors.add(new FieldError("nickname",
                    "닉네임은 " + NICKNAME_MIN_CHARS + "자 이상 " + NICKNAME_MAX_CHARS + "자 이하로 입력해 주세요."));
        }
        if (!errors.isEmpty()) throw new InvalidMemberException(errors);
        return new SignupInput(normalizedEmail, pw, trimmedNickname);
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : Text.trimWhitespace(email).toLowerCase(Locale.ROOT);
    }
}
