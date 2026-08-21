package org.hackerkhu.hackerhp.domain.note.repository;

import java.util.Collection;
import java.util.List;
import org.hackerkhu.hackerhp.domain.note.entity.Bookmark;
import org.hackerkhu.hackerhp.domain.note.entity.BookmarkId;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookmarkRepository extends JpaRepository<Bookmark, BookmarkId> {

  boolean existsByUserIdAndNoteId(Long userId, Long noteId);

  void deleteByUserIdAndNoteId(Long userId, Long noteId);

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
