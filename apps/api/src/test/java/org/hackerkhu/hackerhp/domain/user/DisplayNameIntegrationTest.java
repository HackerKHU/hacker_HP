package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.storage.FakeStorageConfig;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 표시 이름 (T-427 ~ T-434, spec 2-1 §2-1-1 · 3-2 §3-2-2 · 3-3 결정 18, #300·#301).
 *
 * <p>이름 뒤에 학번 끝 두 자리를 붙여 <b>이름이 같은 부원을 가려낸다</b> — {@code 권승원66}.
 *
 * <p><b>네 도메인을 함께 본다</b> (T-431). 도메인마다 재면 셋을 고치고 하나를 잊어도 전부 통과한다 — 활동사진이 실제로 그런 상태였다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class DisplayNameIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /*
   * 사진 저장소도 이제 FakeStorageConfig가 대신한다 (#213 통합) — PhotoService가 자료와 같은
   * FileStorage를 쓰게 되면서, 위 @Import 하나로 두 도메인 모두의 presigned URL 발급이
   * 가짜로 갈린다. 예전에는 S3StorageService를 별도로 목(mock)해야 했다.
   */

  private User viewer;

  @BeforeEach
  void createViewer() {
    wipe();
    viewer = userRepository.saveAndFlush(Accounts.approved("sub-dv", "dv@khu.ac.kr", "20250901"));
  }

  @AfterEach
  void clear() {
    wipe();
  }

  private void wipe() {
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    jdbcTemplate.update("DELETE FROM photos");
    jdbcTemplate.update("DELETE FROM posts");
    jdbcTemplate.update("DELETE FROM notices");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  /** 이름·학번을 지정한 승인 계정. 학번이 {@code null}이면 신청 전 상태를 흉내 낸다. */
  private User member(String sub, String name, String studentNo) {
    User saved =
        userRepository.saveAndFlush(Accounts.approved(sub, sub + "@khu.ac.kr", sub + "-tmp", name));
    jdbcTemplate.update("UPDATE users SET student_no = ? WHERE id = ?", studentNo, saved.getId());
    return saved;
  }

  private void insertNote(String title, Long uploaderId) {
    jdbcTemplate.update(
        """
        INSERT INTO notes (category, title, subject_name, professor, year, semester, exam_type,
                           uploader_id, created_at, updated_at)
        VALUES ('SUBJECT', ?, '운영체제', NULL, 2025, 'SPRING', NULL, ?, ?, ?)
        """,
        title,
        uploaderId,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
  }

  /**
   * 제목 → 업로더 이름.
   *
   * <p><b>정렬 순서를 가정하지 않는다.</b> 어느 행이 먼저 오는지는 이 사례가 재려는 것이 아니고, 가정하면 정렬 규칙이 바뀔 때 <b>표시 이름과 무관한
   * 이유로</b> 깨진다.
   */
  private java.util.Map<String, String> uploaderNamesByTitle() throws Exception {
    String body =
        mockMvc
            .perform(sessions.as(viewer, get("/api/v1/notes")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    java.util.List<String> titles = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].title");
    java.util.List<String> names =
        com.jayway.jsonpath.JsonPath.read(body, "$.content[*].uploader.name");
    java.util.Map<String, String> byTitle = new java.util.LinkedHashMap<>();
    for (int i = 0; i < titles.size(); i++) {
      byTitle.put(titles.get(i), names.get(i));
    }
    return byTitle;
  }

  /** 자료 목록의 첫 행에 실린 업로더 이름. */
  private String uploaderNameOfFirstNote() throws Exception {
    return com.jayway.jsonpath.JsonPath.read(
        mockMvc
            .perform(sessions.as(viewer, get("/api/v1/notes")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        "$.content[0].uploader.name");
  }

  /* ------------------------------------------------------------------ 조립 규칙 */

  /** T-427. 이름 + 학번 끝 두 자리 (MUST). */
  @Test
  void appendsTheLastTwoDigitsOfTheStudentNumber() throws Exception {
    insertNote("정리본", member("sub-d1", "권승원", "2021102466").getId());

    assertThat(uploaderNameOfFirstNote()).isEqualTo("권승원66");
  }

  /**
   * T-428. <b>학번이 없으면 붙이지 않는다.</b>
   *
   * <p>구글 로그인만 하고 신청서를 내지 않은 계정이 그렇다 — {@code student_no}는 그때 채워진다 (3-2 §3-2-2).
   */
  @Test
  void appendsNothingWhenThereIsNoStudentNumber() throws Exception {
    insertNote("정리본", member("sub-d2", "권승원", null).getId());

    assertThat(uploaderNameOfFirstNote()).isEqualTo("권승원");
  }

  /** T-429. 학번이 한 글자면 그 한 글자만 붙는다 — 있는 만큼. */
  @Test
  void appendsWhatIsThereWhenTheStudentNumberIsShort() throws Exception {
    insertNote("정리본", member("sub-d3", "권승원", "7").getId());

    assertThat(uploaderNameOfFirstNote()).isEqualTo("권승원7");
  }

  /**
   * T-430. 숫자가 아니어도 그대로 붙인다.
   *
   * <p>신규 신청은 숫자만 받지만(3-2 §3-2-3), 규칙 전에 저장된 비숫자 값은 소급 변경하지 않는다. <b>여기서 하려는 일은 구별이지 학번 해석이 아니다.</b>
   * 이 테스트는 그 기존 데이터를 안전하게 표시하는 계약을 고정한다.
   */
  @Test
  void doesNotRequireDigits() throws Exception {
    insertNote("정리본", member("sub-d4", "권승원", "편입2025A").getId());

    assertThat(uploaderNameOfFirstNote()).isEqualTo("권승원5A");
  }

  /**
   * T-434. <b>BMP 밖 문자를 쪼개지 않는다</b> (MUST).
   *
   * <p>{@code substring(length - 2)}는 UTF-16 코드 유닛을 자르므로 이 값에서 <b>깨진 서로게이트와 {@code A}</b>가 나온다. 신규
   * 신청 규칙 전에 저장된 기존 값에는 이런 문자도 남을 수 있다.
   */
  @Test
  void keepsSupplementaryCharactersWhole() throws Exception {
    insertNote("정리본", member("sub-d5", "권승원", "😀A").getId());

    String name = uploaderNameOfFirstNote();
    assertThat(name).isEqualTo("권승원😀A");
    assertThat(name.codePoints().count()).as("깨진 서로게이트가 없다").isEqualTo(5);
  }

  /* ------------------------------------------------------------------ 목적과 한계 */

  /** T-432. 이름이 같고 끝 두 자리가 다르면 <b>가려진다</b> — 이 기능의 목적이다. */
  @Test
  void tellsApartMembersWhoseLastTwoDigitsDiffer() throws Exception {
    User older = member("sub-d6", "권승원", "2021102466");
    User newer = member("sub-d7", "권승원", "2022102477");
    insertNote("먼저", older.getId());
    insertNote("나중", newer.getId());

    assertThat(uploaderNamesByTitle()).containsEntry("먼저", "권승원66").containsEntry("나중", "권승원77");
  }

  /**
   * T-433. <b>끝 두 자리까지 같으면 여전히 못 가른다.</b>
   *
   * <p>결함이 아니라 [결정 18]이 <i>"드물다는 이유로 받아들인다"</i> 고 적은 값이다. 사례로 남기지 않으면 다음 사람이 이것을 버그로 읽고 <b>자리를 늘려
   * 노출을 키운다.</b>
   */
  @Test
  void stillCollidesWhenTheLastTwoDigitsMatch() throws Exception {
    insertNote("먼저", member("sub-d8", "권승원", "2021102466").getId());
    insertNote("나중", member("sub-d9", "권승원", "2022102466").getId());

    assertThat(uploaderNamesByTitle()).containsEntry("먼저", "권승원66").containsEntry("나중", "권승원66");
  }

  /* ------------------------------------------------------------------ 네 도메인 */

  /**
   * T-431. <b>자료·공지·활동사진·자유 게시판의 표시 이름이 같다</b> (MUST).
   *
   * <p>도메인마다 재면 셋을 고치고 하나를 잊어도 전부 통과한다 — <b>활동사진이 실제로 그런 상태였다</b> (`PhotoService`가 문구를 직접 적었다). 네
   * 응답을 한 사례에서 견줘야 어긋남이 드러난다.
   */
  @Test
  void allFourDomainsAgree() throws Exception {
    User admin =
        userRepository.saveAndFlush(Accounts.admin("sub-da", "da@khu.ac.kr", "20200101", "권승원"));
    jdbcTemplate.update("UPDATE users SET student_no = '2020010188' WHERE id = ?", admin.getId());
    Instant now = Instant.now();

    insertNote("정리본", admin.getId());
    jdbcTemplate.update(
        "INSERT INTO notices (title, content, is_pinned, author_id, created_at, updated_at)"
            + " VALUES ('공지', '내용', false, ?, ?, ?)",
        admin.getId(),
        Timestamp.from(now),
        Timestamp.from(now));
    jdbcTemplate.update(
        "INSERT INTO photos (caption, stored_path, uploader_id, created_at)"
            + " VALUES ('사진', 'photos/1/a.jpg', ?, ?)",
        admin.getId(),
        Timestamp.from(now));
    jdbcTemplate.update(
        "INSERT INTO posts (title, content, author_id, created_at, updated_at)"
            + " VALUES ('글', '내용', ?, ?, ?)",
        admin.getId(),
        Timestamp.from(now),
        Timestamp.from(now));

    mockMvc
        .perform(sessions.as(admin, get("/api/v1/notes")))
        .andExpect(jsonPath("$.content[0].uploader.name").value("권승원88"));
    mockMvc
        .perform(sessions.as(admin, get("/api/v1/notices")))
        .andExpect(jsonPath("$.content[0].authorName").value("권승원88"));
    mockMvc
        .perform(sessions.as(admin, get("/api/v1/photos")))
        .andExpect(jsonPath("$.content[0].uploaderName").value("권승원88"));
    mockMvc
        .perform(sessions.as(admin, get("/api/v1/posts")))
        .andExpect(jsonPath("$.content[0].author.name").value("권승원88"));
  }

  /** 탈퇴한 회원은 기존 문구 그대로다 — 붙일 학번이 없다 (3-2 §3-2-2). */
  @Test
  void keepsTheWithdrawnWording() throws Exception {
    insertNote("주인이 없는 자료", null);

    assertThat(uploaderNameOfFirstNote()).isEqualTo("탈퇴한 회원");
  }
}
