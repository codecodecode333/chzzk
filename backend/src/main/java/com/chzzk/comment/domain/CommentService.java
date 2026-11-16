package com.chzzk.comment.domain;

import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.error.ForbiddenException;
import com.chzzk.common.text.Text;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 on a VOD or a 방송 ({@link CommentTarget}). Reading is public; writing needs a logged-in 회원; editing and
 * deleting are for the 작성자 only, checked here next to the data. Owns the content rule (trimmed, 1-500 chars).
 */
@Service
public class CommentService {

    public static final int CONTENT_MAX_CHARS = 500;

    private final CommentRepository repository;
    private final CurrentMember currentMember;

    CommentService(CommentRepository repository, CurrentMember currentMember) {
        this.repository = repository;
        this.currentMember = currentMember;
    }

    /**
     * The target's 댓글 newest first (by id), {@code size} at a time. Unknown target: {@code 404}.
     *
     * @param before only 댓글 older than this id (the previous page's {@code nextBefore}); {@code null} for the newest
     */
    @Transactional(readOnly = true)
    public CommentPage list(CommentTarget target, Long before, int size) {
        requireExists(target);
        long cursor = before == null ? Long.MAX_VALUE : before;
        Limit limit = Limit.of(size + 1); // one extra tells whether an older page exists
        List<CommentView> found = switch (target) {
            case CommentTarget.Vod vod -> repository.findOnVod(vod.id(), cursor, limit);
            case CommentTarget.Broadcast broadcast -> repository.findOnBroadcast(broadcast.id(), cursor, limit);
        };
        if (found.size() <= size) return new CommentPage(found, null);
        List<CommentView> page = List.copyOf(found.subList(0, size));
        return new CommentPage(page, page.getLast().id());
    }

    /** The current 회원 writes a 댓글 on the target; a visitor gets {@code 401}. */
    @Transactional
    public CommentView write(CommentTarget target, String content) {
        MemberRef author = currentMember.require();
        requireExists(target);
        Comment comment = repository.save(new Comment(target, author.id(), validContent(content), now()));
        return new CommentView(comment, author.nickname());
    }

    /** The 작성자 replaces the text; the 댓글 is then "(수정됨)". */
    @Transactional
    public CommentView edit(long id, String content) {
        MemberRef author = currentMember.require();
        Comment comment = authoredBy(author, id, "내 댓글만 수정할 수 있습니다.");
        comment.edit(validContent(content), now());
        return new CommentView(comment, author.nickname());
    }

    /** The 작성자 removes the 댓글. */
    @Transactional
    public void delete(long id) {
        repository.delete(authoredBy(currentMember.require(), id, "내 댓글만 삭제할 수 있습니다."));
    }

    private Comment authoredBy(MemberRef member, long id, String forbiddenMessage) {
        Comment comment = repository.findById(id)
                .orElseThrow(() -> new CommentNotFoundException(id + "번 댓글을 찾을 수 없습니다."));
        if (comment.getAuthorId() != member.id()) throw new ForbiddenException(forbiddenMessage);
        return comment;
    }

    private void requireExists(CommentTarget target) {
        switch (target) {
            case CommentTarget.Vod vod -> {
                if (!repository.vodExists(vod.id())) {
                    throw new CommentNotFoundException(vod.id() + "번 다시보기를 찾을 수 없습니다.");
                }
            }
            case CommentTarget.Broadcast broadcast -> {
                if (!repository.broadcastExists(broadcast.id())) {
                    throw new CommentNotFoundException(broadcast.id() + "번 방송을 찾을 수 없습니다.");
                }
            }
        }
    }

    private static String validContent(String content) {
        String trimmed = content == null ? "" : Text.trimWhitespace(content);
        if (trimmed.isEmpty()) throw new InvalidCommentException("댓글 내용을 입력해 주세요.");
        if (Text.charCount(trimmed) > CONTENT_MAX_CHARS) {
            throw new InvalidCommentException("댓글은 " + CONTENT_MAX_CHARS + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS); // match TIMESTAMPTZ precision
    }
}
