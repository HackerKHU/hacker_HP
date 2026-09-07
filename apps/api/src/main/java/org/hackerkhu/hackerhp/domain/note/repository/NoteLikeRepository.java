package org.hackerkhu.hackerhp.domain.note.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.entity.NoteLike;
import org.hackerkhu.hackerhp.domain.note.entity.NoteLikeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteLikeRepository extends JpaRepository<NoteLike, NoteLikeId> {

  boolean existsByUserIdAndNoteId(Long userId, Long noteId);

  /**
   * 누른다. <b>이미 눌렀으면 아무것도 하지 않는다.</b>
   *
   * <p>확인하고 저장하는 방식으로는 안 된다 — 동시에 도착한 둘이 모두 "없다"를 읽고 지나간다. {@code BookmarkRepository}와 같은 이유로 DB에게
   * 한 문장으로 맡긴다.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO note_likes (user_id, note_id, created_at) VALUES (:userId, :noteId, :createdAt)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIgnoringDuplicate(
      @Param("userId") Long userId,
      @Param("noteId") Long noteId,
      @Param("createdAt") Instant createdAt);

  /**
   * 뗀다. <b>없어도 정상이다.</b>
   *
   * <p>파생 삭제가 아니라 JPQL {@code DELETE}로 직접 지운다 — 먼저 읽고 지우면 겹친 두 요청 중 뒤의 것이 stale-state로 터진다 ({@code
   * BookmarkRepository}와 같은 이유).
   */
  @Modifying
  @Query("DELETE FROM NoteLike l WHERE l.userId = :userId AND l.noteId = :noteId")
  int deleteLike(@Param("userId") Long userId, @Param("noteId") Long noteId);

  /**
   * 자료들의 <b>좋아요 개수와 내가 눌렀는지를 한 문장으로</b> 읽는다 (#367 리뷰).
   *
   * <p><b>두 질의로 나누면 모순된 응답이 나간다.</b> 기본 {@code READ COMMITTED}에서는 문장마다 새 스냅샷을 잡으므로, 개수를 센 뒤 내 상태를
   * 묻는 사이에 내가 누른 좋아요가 커밋되면 <b>{@code likeCount=0}인데 {@code likedByMe=true}</b>인 응답이 만들어진다 — 화면은 이
   * 둘을 함께 믿고 숫자와 버튼을 그린다.
   *
   * <p>행마다 물으면 20건에 질의가 20번 붙으므로 페이지 전체를 한 번에 모은다 — 업로더 이름을 모아 읽는 것과 같은 이유(#52). <b>상세도 이 메서드를
   * 쓴다</b> — id 하나짜리 목록일 뿐이라 질의를 따로 둘 이유가 없다.
   *
   * <p>{@code Object[]}의 각 원소는 {@code [noteId, count, 내가 누른 수]}다. 뒤 값은 복합 PK 덕에 {@code 0} 아니면
   * {@code 1}이다. 좋아요가 하나도 없는 자료는 결과에 없으므로 부르는 쪽이 {@code 0}·{@code false}로 채운다.
   */
  @Query(
      "SELECT l.noteId, COUNT(l), SUM(CASE WHEN l.userId = :userId THEN 1 ELSE 0 END)"
          + " FROM NoteLike l WHERE l.noteId IN :noteIds GROUP BY l.noteId")
  List<Object[]> countWithMineByNoteIds(
      @Param("userId") Long userId, @Param("noteIds") Collection<Long> noteIds);
}
