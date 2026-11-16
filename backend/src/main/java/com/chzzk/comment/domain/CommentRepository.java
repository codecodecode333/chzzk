package com.chzzk.comment.domain;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 댓글 storage. Reads the target tables ({@code vod}, {@code broadcast}) and the 작성자's 닉네임 straight from the
 * shared database, which already links them by foreign keys, so the comments module needs no other feature module.
 */
interface CommentRepository extends JpaRepository<Comment, Long> {

    String VIEW = "select new com.chzzk.comment.domain.CommentView(c, a.nickname)"
            + " from Comment c join CommentAuthor a on a.id = c.authorId ";

    @Query(VIEW + "where c.vodId = :vodId and c.id < :before order by c.id desc")
    List<CommentView> findOnVod(long vodId, long before, Limit limit);

    @Query(VIEW + "where c.broadcastId = :broadcastId and c.id < :before order by c.id desc")
    List<CommentView> findOnBroadcast(long broadcastId, long before, Limit limit);

    @Query(value = "select exists (select 1 from vod where id = :id)", nativeQuery = true)
    boolean vodExists(long id);

    @Query(value = "select exists (select 1 from broadcast where id = :id)", nativeQuery = true)
    boolean broadcastExists(long id);
}
