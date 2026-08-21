package org.hackerkhu.hackerhp.domain.note.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.entity.Bookmark;
import org.hackerkhu.hackerhp.domain.note.entity.BookmarkId;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookmarkRepository extends JpaRepository<Bookmark, BookmarkId> {

  boolean existsByUserIdAndNoteId(Long userId, Long noteId);

  /**
   * 담는다. <b>이미 있으면 아무것도 하지 않는다.</b>
   *
   * <p>확인하고 저장하는 방식으로는 안 된다 — 동시에 도착한 둘이 모두 "없다"를 읽고 지나간다. 게다가 이 엔티티는 키를 직접 배정하므로 {@code save()}의
   * INSERT가 커밋까지 미뤄져, PK 위반이 <b>서비스의 {@code try} 밖에서 터져 {@code 500}이 된다</b> (#189 리뷰).
   *
   * <p>그래서 DB에게 한 문장으로 맡긴다. 재시도가 예상되는 경로라 여기서 갈리면 안 된다.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (:userId, :noteId, :createdAt)"
              + " ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIgnoringDuplicate(
      @Param("userId") Long userId,
      @Param("noteId") Long noteId,
      @Param("createdAt") Instant createdAt);

  /**
   * 뺀다. <b>없어도 정상이다.</b>
   *
   * <p>파생 삭제({@code deleteByUserIdAndNoteId})는 <b>먼저 읽고 그 엔티티를 지운다.</b> 겹친 두 요청이 같은 행을 읽으면 뒤의 것은 지울
   * 것이 없어 Hibernate가 stale-state로 터진다 — 멱등이어야 할 재시도가 {@code 500}이 된다.
   *
   * <p>읽지 않고 지우면 0건 삭제도 그냥 0이다.
   */
  @Modifying
  @Query("DELETE FROM Bookmark b WHERE b.userId = :userId AND b.noteId = :noteId")
  int deleteBookmark(@Param("userId") Long userId, @Param("noteId") Long noteId);

  /**
   * 그 페이지에서 <b>내가 표시한 자료</b>의 id.
   *
   * <p>행마다 물으면 20건에 질의가 20번 붙는다 — 업로더 이름·파일 개수와 같은 이유로 한 번에 모아 온다 (#52).
   */
  @Query("SELECT b.noteId FROM Bookmark b WHERE b.userId = :userId AND b.noteId IN :noteIds")
  List<Long> findNoteIdsOf(
      @Param("userId") Long userId, @Param("noteIds") Collection<Long> noteIds);

  /**
   * 내 즐겨찾기 목록.
   *
   * <p><b>정렬은 내가 표시한 순서다</b> — 자료의 등록 시각이 아니다. 이 화면의 기준은 "언제 올라온 자료인가"가 아니라 "언제 내가 담았나"다.
   *
   * <p>마지막 기준으로 자료 id를 붙인다. 같은 시각에 여럿을 담으면 순서가 정해지지 않아 <b>페이지를 넘길 때 같은 자료가 두 번 보이거나 빠진다</b> (#52
   * T-228과 같은 이유다).
   */
  @Query(
      value =
          "SELECT n FROM Note n JOIN Bookmark b ON b.noteId = n.id "
              + "WHERE b.userId = :userId ORDER BY b.createdAt DESC, n.id DESC",
      countQuery = "SELECT COUNT(b) FROM Bookmark b WHERE b.userId = :userId")
  Page<Note> findMyNotes(@Param("userId") Long userId, Pageable pageable);
}
