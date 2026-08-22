package org.hackerkhu.hackerhp.domain.note.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.note.entity.Note;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

  /**
   * 자료별 파일 개수를 <b>한 번에</b> 센다.
   *
   * <p>목록에서 {@code note.getFiles().size()}를 부르면 행마다 질의가 하나씩 붙는다 — 20건이면 20번이다. 개수만 필요한 자리라 세어서 받는다.
   */
  @Query("SELECT f.note.id, COUNT(f) FROM NoteFile f WHERE f.note.id IN :ids GROUP BY f.note.id")
  List<Object[]> countFilesByNoteIds(@Param("ids") Collection<Long> ids);

  /** 상세는 파일까지 함께 읽는다. 따로 읽으면 질의가 하나 더 붙는다. */
  @EntityGraph(attributePaths = "files")
  Optional<Note> findWithFilesById(Long id);

  /**
   * <b>같은 자료의 수정·삭제를 한 줄로 세운다</b> (#211 리뷰).
   *
   * <p>수정은 "남길 첨부 전부"를 받아 통째로 갈아끼운다. 두 요청이 각자 기존 목록을 읽고 각자 갈아끼우면 <b>보낸 목록이 최종 상태라는 계약이 깨진다</b> — A를
   * B로 바꾸는 요청과 A를 C로 바꾸는 요청이 겹치면 B와 C가 함께 남는다. 요청자 계정 행을 잠그는 것으로는 막히지 않는다. 서로 <b>다른</b> 사람이기 때문이다.
   *
   * <p>수정과 삭제가 겹치면 더 나쁘다 — 삭제가 미리 모아 둔 키만 지우고 나가므로, 그 사이에 붙은 새 오브젝트는 <b>DB 행도 없이 영원히 남는다.</b>
   *
   * <p><b>파일을 함께 읽지 않는다.</b> {@code @EntityGraph}는 LEFT JOIN을 만드는데, Postgres는 바깥 조인의 널 쪽에 {@code
   * FOR UPDATE}를 걸지 못한다. 잠근 뒤에 컬렉션을 건드리면 그때 읽힌다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from Note n where n.id = :id")
  Optional<Note> findByIdForUpdate(@Param("id") Long id);

  /*
   * 필터 옵션은 실제 등록된 값에서 만든다 (2-1 §2-1-1 MUST). 목록에 없는 과목을 고를 수
   * 있으면 결과가 늘 0건이고, 등록된 과목이 빠지면 찾을 방법이 사라진다.
   */

  @Query("SELECT DISTINCT n.subjectName FROM Note n ORDER BY n.subjectName")
  List<String> findDistinctSubjectNames();

  /** 교수명은 없을 수 있다. 널을 옵션으로 내보내면 화면이 빈 항목을 그린다. */
  @Query(
      "SELECT DISTINCT n.professor FROM Note n WHERE n.professor IS NOT NULL ORDER BY n.professor")
  List<String> findDistinctProfessors();

  /** 최신 연도가 위다 — 사람들이 찾는 것은 대개 최근 학기다. */
  @Query("SELECT DISTINCT n.year FROM Note n ORDER BY n.year DESC")
  List<Integer> findDistinctYears();
}
