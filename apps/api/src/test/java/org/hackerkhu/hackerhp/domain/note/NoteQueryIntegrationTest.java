package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.dto.Uploader;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자료 목록·검색·필터·상세 (#52, spec 2-1 §2-1-1, 3-2 §3-2-4).
 *
 * <p><b>자료는 SQL로 직접 넣는다.</b> 등록 API가 아직 없고(#53), 조회가 지켜야 하는 것은 "무엇이 저장돼 있을 때 무엇을 돌려주는가"라서 넣는 방법과
 * 무관하다 — {@code V4} 스키마 테스트가 쓰는 방식과 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoteQueryIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/notes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User member;
  private User uploader;

  @BeforeEach
  void createAccounts() {
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    uploader =
        userRepository.saveAndFlush(Accounts.approved("sub-u", "u@khu.ac.kr", "20250002", "올린이"));
  }

  @AfterEach
  void clear() {
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  /** 자료 하나를 넣고 id를 준다. {@code createdAt}을 받아 정렬을 결정적으로 만든다. */
  private Long insertNote(
      String category,
      String title,
      String subject,
      String professor,
      int year,
      String semester,
      String examType,
      Long uploaderId,
      Instant createdAt) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notes (category, title, subject_name, professor, year, semester, exam_type,
                           uploader_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
        """,
        Long.class,
        category,
        title,
        subject,
        professor,
        year,
        semester,
        examType,
        uploaderId,
        java.sql.Timestamp.from(createdAt),
        java.sql.Timestamp.from(createdAt));
  }

  private Long subjectNote(String title, String subject, String professor, Long uploaderId) {
    return insertNote(
        "SUBJECT", title, subject, professor, 2025, "SPRING", null, uploaderId, Instant.now());
  }

  private void insertFile(Long noteId, String originalName) {
    jdbcTemplate.update(
        "INSERT INTO note_files (note_id, original_name, stored_path, size_bytes) VALUES (?, ?, ?, ?)",
        noteId,
        originalName,
        "notes/" + java.util.UUID.randomUUID() + ".pdf",
        1024L);
  }

  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
    return sessions.as(member, request);
  }

  /* ------------------------------------------------------------------ 목록 */

  /** 기본 정렬은 최신순이다 (2-1 §2-1-1). */
  @Test
  void listsNewestFirst() throws Exception {
    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    insertNote(
        "SUBJECT",
        "먼저",
        "운영체제",
        null,
        2025,
        "SPRING",
        null,
        uploader.getId(),
        base.minusSeconds(60));
    insertNote("SUBJECT", "나중", "운영체제", null, 2025, "SPRING", null, uploader.getId(), base);

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].title").value("나중"))
        .andExpect(jsonPath("$.content[1].title").value("먼저"))
        .andExpect(jsonPath("$.page.totalElements").value(2));
  }

  /**
   * <b>검색은 제목·과목명·교수명을 한 번에 본다</b> (2-1 §2-1-1 MUST).
   *
   * <p>필드를 나눠 받지 않으므로 어느 칸에 들어 있든 같은 검색어로 찾혀야 한다.
   */
  @Test
  void searchesTitleSubjectAndProfessorWithOneKeyword() throws Exception {
    subjectNote("자료구조 정리", "알고리즘", "김교수", uploader.getId());
    subjectNote("중간 정리", "자료구조", "이교수", uploader.getId());
    subjectNote("기말 정리", "운영체제", "자료구조교수", uploader.getId());
    subjectNote("관계없는 것", "네트워크", "박교수", uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("q", "자료구조"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(3));
  }

  /** 교수명이 없는 자료가 검색에서 통째로 빠지면 안 된다. */
  @Test
  void findsNotesWithoutAProfessor() throws Exception {
    subjectNote("운영체제 정리", "운영체제", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("q", "운영체제"))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  /** 대소문자를 가리지 않는다. */
  @Test
  void searchIgnoresCase() throws Exception {
    subjectNote("Operating System", "OS", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("q", "operating"))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  /**
   * {@code _}는 글자 그대로 찾는다.
   *
   * <p>escape하지 않으면 "아무 글자 하나"로 해석되어 <b>찾지 않은 자료가 섞인다.</b>
   */
  @Test
  void treatsWildcardsAsLiterals() throws Exception {
    subjectNote("a_b 정리", "네트워크", null, uploader.getId());
    subjectNote("axb 정리", "네트워크", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("q", "a_b"))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].title").value("a_b 정리"));
  }

  /** <b>검색어와 필터는 AND로 함께 걸린다</b> (2-1 §2-1-1 MUST). */
  @Test
  void appliesSearchAndFiltersTogether() throws Exception {
    insertNote(
        "EXAM", "정리", "운영체제", "김교수", 2025, "SPRING", "MIDTERM", uploader.getId(), Instant.now());
    insertNote(
        "EXAM", "정리", "운영체제", "김교수", 2024, "SPRING", "MIDTERM", uploader.getId(), Instant.now());
    insertNote(
        "EXAM", "정리", "네트워크", "김교수", 2025, "SPRING", "MIDTERM", uploader.getId(), Instant.now());

    mockMvc
        .perform(
            asMember(get(PATH)).param("q", "정리").param("subject", "운영체제").param("year", "2025"))
        .andExpect(jsonPath("$.page.totalElements").value(1));
  }

  /**
   * 있을 수 없는 조합은 <b>오류가 아니라 결과 0건</b>이다.
   *
   * <p>조회에 검증을 넣으면 화면이 필터를 조합하는 순간마다 {@code 400}을 받는다.
   */
  @Test
  void anImpossibleCombinationIsEmptyNotAnError() throws Exception {
    subjectNote("정리", "운영체제", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("category", "SUBJECT").param("examType", "MIDTERM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  /** 제목순 정렬. 모르는 값은 기본값(최신순)으로 본다. */
  @Test
  void sortsByTitleWhenAsked() throws Exception {
    subjectNote("나", "운영체제", null, uploader.getId());
    subjectNote("가", "운영체제", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)).param("sort", "title"))
        .andExpect(jsonPath("$.content[0].title").value("가"));
    mockMvc
        .perform(asMember(get(PATH)).param("sort", "bogus"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].title").value("가"));
  }

  /**
   * 완료 조건 — <b>100건에서 과목명 일부로 검색하면 20건 단위로 페이징된다.</b>
   *
   * <p>같은 시각·같은 제목이 여럿이라 <b>순서를 가르는 마지막 기준이 없으면 페이지마다 배치가 달라진다</b> — 같은 자료가 두 번 보이거나 아예 빠진다. 두 페이지를
   * 합쳐 중복이 없는지로 그것을 본다.
   */
  @Test
  void pagesA100ItemSearchInBlocksOf20() throws Exception {
    Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    for (int index = 0; index < 100; index++) {
      insertNote(
          "SUBJECT",
          "정리",
          index < 60 ? "운영체제" : "네트워크",
          null,
          2025,
          "SPRING",
          null,
          uploader.getId(),
          same);
    }

    mockMvc
        .perform(asMember(get(PATH)).param("q", "운영"))
        .andExpect(jsonPath("$.page.totalElements").value(60))
        .andExpect(jsonPath("$.page.size").value(20))
        .andExpect(jsonPath("$.page.totalPages").value(3))
        .andExpect(jsonPath("$.content.length()").value(20));

    String first =
        mockMvc
            .perform(asMember(get(PATH)).param("q", "운영").param("page", "0"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mockMvc
            .perform(asMember(get(PATH)).param("q", "운영").param("page", "1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(noteIdsIn(first)).hasSize(20);
    assertThat(noteIdsIn(first)).doesNotContainAnyElementsOf(noteIdsIn(second));
  }

  /**
   * 응답에서 <b>자료</b> id만 뽑는다.
   *
   * <p>{@code "id"}를 통째로 긁으면 업로더 id까지 섞여, 두 페이지가 겹치지 않아도 겹친 것으로 보인다. 자료 객체는 언제나 {@code category}가
   * 뒤따르므로 그것으로 가른다.
   */
  private static java.util.List<String> noteIdsIn(String body) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{\"id\":(\\d+),\"category\"").matcher(body);
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return ids;
  }

  /* ------------------------------------------------------------- 업로더 표시 */

  /** 업로더의 이름과 id를 함께 준다. 소유자 판단은 id로 한다 (3-2 §3-2-2). */
  @Test
  void showsTheUploader() throws Exception {
    subjectNote("정리", "운영체제", null, uploader.getId());

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(jsonPath("$.content[0].uploader.id").value(uploader.getId()))
        .andExpect(jsonPath("$.content[0].uploader.name").value("올린이"));
  }

  /**
   * <b>탈퇴한 회원의 자료도 그대로 보인다</b> (2-2 §2-2-4).
   *
   * <p>이름 자리는 서버가 채운다 — {@code null}을 내려보내고 화면이 채우게 하면 화면마다 문구가 갈린다 (3-2 §3-2-2 MUST).
   */
  @Test
  void fillsInTheNameOfAWithdrawnUploader() throws Exception {
    subjectNote("정리", "운영체제", null, null);

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].uploader.id").doesNotExist())
        .andExpect(jsonPath("$.content[0].uploader.name").value(Uploader.WITHDRAWN));
  }

  /* ------------------------------------------------------------------ 상세 */

  /** 목록은 파일 <b>개수</b>만, 상세는 <b>목록</b>을 준다. */
  @Test
  void listsFileCountAndDetailsListFiles() throws Exception {
    Long noteId = subjectNote("정리", "운영체제", null, uploader.getId());
    insertFile(noteId, "1주차.pdf");
    insertFile(noteId, "2주차.pdf");

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(jsonPath("$.content[0].fileCount").value(2))
        .andExpect(jsonPath("$.content[0].files").doesNotExist());

    mockMvc
        .perform(asMember(get(PATH + "/" + noteId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(2))
        .andExpect(jsonPath("$.files[0].originalName").value("1주차.pdf"))
        .andExpect(jsonPath("$.files[0].id").isNumber())
        .andExpect(jsonPath("$.files[0].sizeBytes").value(1024));
  }

  /** <b>S3 키는 어디에도 나가지 않는다.</b> 버킷이 비공개라 쓸모도 없고, 키 구조를 드러낼 이유도 없다. */
  @Test
  void neverExposesTheStorageKey() throws Exception {
    Long noteId = subjectNote("정리", "운영체제", null, uploader.getId());
    insertFile(noteId, "1주차.pdf");

    String detail =
        mockMvc
            .perform(asMember(get(PATH + "/" + noteId)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(detail).doesNotContain("storedPath").doesNotContain("notes/");
  }

  /** 없는 자료는 {@code 404}다. */
  @Test
  void missingNoteIsNotFound() throws Exception {
    mockMvc
        .perform(asMember(get(PATH + "/999999")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* ------------------------------------------------------------------ 필터 */

  /**
   * <b>필터 옵션은 실제 등록된 값에서 만든다</b> (2-1 §2-1-1 MUST).
   *
   * <p>없는 과목을 고를 수 있으면 결과가 늘 0건이고, 등록된 과목이 빠지면 찾을 방법이 사라진다.
   */
  @Test
  void buildsFilterOptionsFromWhatIsActuallyStored() throws Exception {
    insertNote(
        "SUBJECT", "가", "운영체제", "김교수", 2025, "SPRING", null, uploader.getId(), Instant.now());
    insertNote("SUBJECT", "나", "네트워크", null, 2024, "FALL", null, uploader.getId(), Instant.now());
    insertNote("SUBJECT", "다", "운영체제", "김교수", 2025, "FALL", null, uploader.getId(), Instant.now());

    mockMvc
        .perform(asMember(get(PATH + "/filters")))
        .andExpect(status().isOk())
        // 중복 없이, 가나다순
        .andExpect(jsonPath("$.subjects.length()").value(2))
        .andExpect(jsonPath("$.subjects[0]").value("네트워크"))
        // 교수명이 없는 자료는 옵션을 만들지 않는다 — 화면이 빈 항목을 그린다
        .andExpect(jsonPath("$.professors.length()").value(1))
        .andExpect(jsonPath("$.professors[0]").value("김교수"))
        // 최신 연도가 위다
        .andExpect(jsonPath("$.years.length()").value(2))
        .andExpect(jsonPath("$.years[0]").value(2025));
  }

  /* ------------------------------------------------------------------ 권한 */

  /** 비로그인은 목록을 볼 수 없다 (3-1 §3-1-3). */
  @Test
  void requiresAuthentication() throws Exception {
    mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mockMvc.perform(get(PATH + "/filters")).andExpect(status().isUnauthorized());
  }

  /** 승인 대기 회원은 막힌다 — {@code AccountStatusFilter}가 인가보다 먼저 본다. */
  @Test
  void pendingMemberIsBlocked() throws Exception {
    User pending =
        userRepository.saveAndFlush(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));

    mockMvc
        .perform(sessions.as(pending, get(PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }
}
