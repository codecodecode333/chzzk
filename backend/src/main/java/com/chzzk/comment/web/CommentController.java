package com.chzzk.comment.web;

import com.chzzk.comment.domain.CommentPage;
import com.chzzk.comment.domain.CommentService;
import com.chzzk.comment.domain.CommentTarget;
import com.chzzk.comment.domain.CommentView;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.web.Paging;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 댓글 API. Listing and writing are per target ({@code /api/vods/{id}/comments}, {@code /api/broadcasts/{id}/comments});
 * editing and deleting address the 댓글 itself ({@code /api/comments/{id}}). Listing pages older 댓글 with
 * {@code before} and newer ones with {@code after} (for polling).
 */
@RestController
class CommentController {

    private final CommentService comments;

    CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/api/vods/{vodId}/comments")
    CommentPageResponse listOnVod(@PathVariable long vodId, @RequestParam(required = false) Long before,
                                  @RequestParam(required = false) Long after,
                                  @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        return list(CommentTarget.vod(vodId), before, after, size);
    }

    @PostMapping("/api/vods/{vodId}/comments")
    ResponseEntity<CommentResponse> writeOnVod(@PathVariable long vodId, @RequestBody ContentRequest request) {
        return write(CommentTarget.vod(vodId), request);
    }

    @GetMapping("/api/broadcasts/{broadcastId}/comments")
    CommentPageResponse listOnBroadcast(@PathVariable long broadcastId, @RequestParam(required = false) Long before,
                                        @RequestParam(required = false) Long after,
                                        @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        return list(CommentTarget.broadcast(broadcastId), before, after, size);
    }

    @PostMapping("/api/broadcasts/{broadcastId}/comments")
    ResponseEntity<CommentResponse> writeOnBroadcast(@PathVariable long broadcastId,
                                                     @RequestBody ContentRequest request) {
        return write(CommentTarget.broadcast(broadcastId), request);
    }

    @PatchMapping("/api/comments/{id}")
    CommentResponse edit(@PathVariable long id, @RequestBody ContentRequest request) {
        return CommentResponse.of(comments.edit(id, request.content()));
    }

    @DeleteMapping("/api/comments/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        comments.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** With {@code after} (polling for newer 댓글), {@code before} is ignored. */
    private CommentPageResponse list(CommentTarget target, Long before, Long after, int size) {
        int pageSize = Paging.of(0, size).size();
        CommentPage page = after != null
                ? comments.listAfter(target, after, pageSize)
                : comments.list(target, before, pageSize);
        return new CommentPageResponse(page.content().stream().map(CommentResponse::of).toList(), page.nextBefore(),
                page.nextAfter());
    }

    private ResponseEntity<CommentResponse> write(CommentTarget target, ContentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommentResponse.of(comments.write(target, request.content())));
    }

    record ContentRequest(String content) {}

    record CommentPageResponse(List<CommentResponse> content, Long nextBefore, Long nextAfter) {}

    record CommentResponse(long id, String content, MemberRef author, Instant createdAt, boolean edited) {
        static CommentResponse of(CommentView view) {
            return new CommentResponse(view.id(), view.content(), view.author(), view.createdAt(), view.edited());
        }
    }
}
