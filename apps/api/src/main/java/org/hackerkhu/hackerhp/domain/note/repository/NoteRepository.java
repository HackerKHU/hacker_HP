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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<Note, Long>, JpaSpecificationExecutor<Note> {

  /**
   * 지금 DB가 참조하는 자료 파일의 S3 키 전체 (#339의 고아 오브젝트 정리가 쓴다).
   *
   * <p>등록이 끝난 행만 이 목록에 있다 — 임시 키({@code notes/uploads/…})는 애초에 {@code NoteFile}로 저장되지 않고, 최종
   * 키({@code notes/…})만 이 테이블에 쓰인다.
   */
  @Query("SELECT f.storedPath FROM NoteFile f")
  List<String> findAllFileStoredPaths();

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

  /**
   * 조회수를 1 올린다 (#245, 3-2 §3-2-4 MUST).
   *
   * <p><b>읽고 더해서 쓰지 않는다</b> (MUST). {@code SELECT → +1 → UPDATE}로 짜면 동시에 들어온 요청들이 <b>같은 값을 읽고 같은 값을
   * 쓴다</b> — 열 명이 동시에 열면 1만 오른다. 가장 많이 열린 자료가 가장 많이 잃는다. 더하기를 DB 안에서 끝내면 그 창이 없다.
   *
   * <p><b>{@code updated_at}을 건드리지 않는다</b> (MUST). 그래서 엔티티가 아니라 이 문장으로 올린다 — 엔티티를 읽어 고치면 수정 시각이 함께
   * 바뀌어 아무도 손대지 않은 자료의 수정일이 오늘이 된다.
   *
   * <p><b>{@code clearAutomatically}·{@code flushAutomatically}를 켜지 않는다.</b> 이 문장은 조회가 끝난 뒤 <b>별도
   * 트랜잭션</b>에서 돌고(3-2 §3-2-4 MUST), 부르는 쪽은 이미 읽어 둔 값에 1을 더해 응답을 만든다 — 영속성 컨텍스트를 비울 이유가 없다.
   *
   * @return 바뀐 행 수. 없는 자료면 {@code 0}이다
   */
  @Modifying
  @Query(value = "UPDATE notes SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
  int increaseViewCount(@Param("id") Long id);

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
