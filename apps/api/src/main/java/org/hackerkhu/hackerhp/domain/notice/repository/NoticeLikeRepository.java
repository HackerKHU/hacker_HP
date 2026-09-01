package org.hackerkhu.hackerhp.domain.notice.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.hackerkhu.hackerhp.domain.notice.entity.NoticeLike;
import org.hackerkhu.hackerhp.domain.notice.entity.NoticeLikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeLikeRepository extends JpaRepository<NoticeLike, NoticeLikeId> {

  boolean existsByUserIdAndNoticeId(Long userId, Long noticeId);

  /**
   * 누른다. <b>이미 눌렀으면 아무것도 하지 않는다.</b>
   *
   * <p>확인하고 저장하는 방식으로는 안 된다 — 동시에 도착한 둘이 모두 "없다"를 읽고 지나간다. {@code BookmarkRepository}와 같은 이유로 DB에게
   * 한 문장으로 맡긴다.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO notice_likes (user_id, notice_id, created_at) VALUES (:userId, :noticeId, :createdAt)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIgnoringDuplicate(
      @Param("userId") Long userId,
      @Param("noticeId") Long noticeId,
      @Param("createdAt") Instant createdAt);

  /**
   * 뗀다. <b>없어도 정상이다.</b>
   *
   * <p>파생 삭제가 아니라 JPQL {@code DELETE}로 직접 지운다 — 먼저 읽고 지우면 겹친 두 요청 중 뒤의 것이 stale-state로 터진다 ({@code
   * BookmarkRepository}와 같은 이유).
   */
  @Modifying
  @Query("DELETE FROM NoticeLike l WHERE l.userId = :userId AND l.noticeId = :noticeId")
  int deleteLike(@Param("userId") Long userId, @Param("noticeId") Long noticeId);

  long countByNoticeId(Long noticeId);

  /**
   * 그 페이지에 실린 공지 중 <b>내가 좋아요를 누른</b> 것의 id.
   *
   * <p>행마다 물으면 20건에 질의가 20번 붙는다 — 작성자 이름을 한 번에 모아 읽는 것과 같은 이유(#52).
   */
  @Query(
      "SELECT l.noticeId FROM NoticeLike l WHERE l.userId = :userId AND l.noticeId IN :noticeIds")
  List<Long> findLikedNoticeIdsOf(
      @Param("userId") Long userId, @Param("noticeIds") Collection<Long> noticeIds);

  /**
   * 그 페이지에 실린 공지들의 좋아요 개수를 <b>한 번에</b> 센다.
   *
   * <p>{@code Object[]}의 각 원소는 {@code [noticeId, count]}다 — {@code
   * NoteRepository#countFilesByNoteIds}와 같은 배치 집계 방식이다.
   */
  @Query(
      "SELECT l.noticeId, COUNT(l) FROM NoticeLike l WHERE l.noticeId IN :noticeIds GROUP BY l.noticeId")
  List<Object[]> countByNoticeIds(@Param("noticeIds") Collection<Long> noticeIds);
}
