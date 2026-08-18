package org.hackerkhu.hackerhp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V4__notes_bookmarks_photos.sql이 spec/3-2-DESIGN-CONTRACT.md §3-2-2와 일치하는지 본다 (#51).
 *
 * <p>엔티티가 아직 없어서 {@code SchemaMigrationIntegrationTest}처럼 JPA로 검증할 수 없다. 대신 {@link JdbcTemplate}로
 * 직접 행을 넣어 <b>CHECK 제약과 CASCADE가 DB 레벨에서 보장되는지</b> 확인한다 — 애플리케이션 코드가 아직 없으니 그것 말고는 지킬 것이 없다.
 */
@SpringBootTest
@Transactional
class NotesBookmarksPhotosSchemaIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;

  private Long uploaderId() {
    return userRepository
        .save(User.createFromGoogle("google-sub-notes", "notes@khu.ac.kr", "테스트"))
        .getId();
  }

  private Long insertNote(Long uploaderId, String category, String examType) {
    jdbcTemplate.update(
        "INSERT INTO notes (category, title, subject_name, year, semester, exam_type,"
            + " uploader_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
        category,
        "제목",
        "과목명",
        2026,
        "SPRING",
        examType,
        uploaderId);
    return jdbcTemplate.queryForObject("SELECT max(id) FROM notes", Long.class);
  }

  /* category=EXAM인데 exam_type이 없으면 거부한다 (spec §3-2-2 MUST). */
  @Test
  void examNoteWithoutExamTypeViolatesCheckConstraint() {
    Long uploaderId = uploaderId();

    assertThatThrownBy(() -> insertNote(uploaderId, "EXAM", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /* category=SUBJECT인데 exam_type이 있으면 거부한다 — 반대 방향도 막아야 한다. */
  @Test
  void subjectNoteWithExamTypeViolatesCheckConstraint() {
    Long uploaderId = uploaderId();

    assertThatThrownBy(() -> insertNote(uploaderId, "SUBJECT", "MIDTERM"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void validExamAndSubjectNotesAreAccepted() {
    Long uploaderId = uploaderId();

    Long examNoteId = insertNote(uploaderId, "EXAM", "MIDTERM");
    Long subjectNoteId = insertNote(uploaderId, "SUBJECT", null);

    assertThat(examNoteId).isNotNull();
    assertThat(subjectNoteId).isNotNull();
  }

  /* note_files는 note_id에 CASCADE가 걸려 있다 — 자료를 지우면 첨부파일도 함께 지워진다. */
  @Test
  void deletingNoteCascadesToNoteFiles() {
    Long noteId = insertNote(uploaderId(), "SUBJECT", null);
    jdbcTemplate.update(
        "INSERT INTO note_files (note_id, original_name, stored_path, size_bytes) VALUES (?, ?,"
            + " ?, ?)",
        noteId,
        "파일.pdf",
        "notes/uuid.pdf",
        1024);

    jdbcTemplate.update("DELETE FROM notes WHERE id = ?", noteId);

    Integer remaining =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM note_files WHERE note_id = ?", Integer.class, noteId);
    assertThat(remaining).isZero();
  }

  /* bookmarks도 note_id에 CASCADE가 걸려 있다 (spec §2-1-3). */
  @Test
  void deletingNoteCascadesToBookmarks() {
    Long uploaderId = uploaderId();
    Long noteId = insertNote(uploaderId, "SUBJECT", null);
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
        uploaderId,
        noteId);

    jdbcTemplate.update("DELETE FROM notes WHERE id = ?", noteId);

    Integer remaining =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM bookmarks WHERE note_id = ?", Integer.class, noteId);
    assertThat(remaining).isZero();
  }

  /*
   * bookmarks도 user_id에 CASCADE가 걸려 있다 — 회원이 지워지면 그 즐겨찾기도 지워진다.
   *
   * 업로더와는 다른 사람으로 즐겨찾기한다. 같은 사람이면 notes.uploader_id의 FK(CASCADE
   * 없음 — #4 미결정이라 지금은 업로더가 있는 자료는 삭제를 막아야 한다)에 먼저 걸려,
   * 이 테스트가 보려는 것과 무관한 이유로 삭제 자체가 거부된다.
   */
  @Test
  void deletingUserCascadesToBookmarks() {
    Long uploaderId = uploaderId();
    Long noteId = insertNote(uploaderId, "SUBJECT", null);
    User bookmarker =
        userRepository.save(User.createFromGoogle("google-sub-bookmarker", "bm@khu.ac.kr", "북마커"));
    Long bookmarkerId = bookmarker.getId();
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
        bookmarkerId,
        noteId);

    jdbcTemplate.update("DELETE FROM users WHERE id = ?", bookmarkerId);

    Integer remaining =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM bookmarks WHERE user_id = ?", Integer.class, bookmarkerId);
    assertThat(remaining).isZero();
  }

  /* (user_id, note_id) 복합 PK로 같은 조합의 중복 등록을 막는다. */
  @Test
  void duplicateBookmarkViolatesPrimaryKey() {
    Long uploaderId = uploaderId();
    Long noteId = insertNote(uploaderId, "SUBJECT", null);
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
        uploaderId,
        noteId);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
                    uploaderId,
                    noteId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void photoRowCanBeInsertedAndLoaded() {
    Long uploaderId = uploaderId();

    jdbcTemplate.update(
        "INSERT INTO photos (caption, stored_path, uploader_id, created_at) VALUES (?, ?, ?,"
            + " now())",
        "사진 설명",
        "photos/1/uuid.jpg",
        uploaderId);

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM photos WHERE uploader_id = ?", Integer.class, uploaderId);
    assertThat(count).isEqualTo(1);
  }
}
