package org.hackerkhu.hackerhp.domain.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.note.service.NoteViewService;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 자료 조회수 (T-405 ~ T-415, spec 2-1 §2-1-1 · 3-2 §3-2-4, #244·#245).
 *
 * <p><b>세는 규칙이 곧 기능이다.</b> 숫자가 오르는지만 보면 "그럴듯하게 짜면 통과하는" 구현이 그대로 남는다 — 중복을 슬쩍 걸러내거나, 목록에서도 세거나, 동시
 * 조회를 잃거나, {@code updated_at}을 함께 밀어 버리는 구현이 전부 그렇다. 그래서 사례가 각각을 겨냥한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeStorageConfig.class)
class NoteViewCountIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/notes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NoteViewService noteViewService;

  /**
   * <b>{@code @MockitoBean}이 아니라 spy다.</b> 통째로 갈아끼우면 자료를 읽는 것부터 막혀 <i>"증가만 실패했을 때"</i>를 만들 수 없다 —
   * 그러면 T-410이 재려는 것과 다른 상황을 재게 된다.
   */
  @MockitoSpyBean private NoteRepository notes;

  private User member;
  private User uploader;

  @BeforeEach
  void createAccounts() {
    wipe();
    member = userRepository.saveAndFlush(Accounts.approved("sub-vm", "vm@khu.ac.kr", "20250101"));
    uploader =
        userRepository.saveAndFlush(Accounts.approved("sub-vu", "vu@khu.ac.kr", "20250102", "올린이"));
  }

  @AfterEach
  void clear() {
    wipe();
  }

  private void wipe() {
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private Long insertNote(String title, Long uploaderId, Instant createdAt) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notes (category, title, subject_name, professor, year, semester, exam_type,
                           uploader_id, created_at, updated_at)
        VALUES ('SUBJECT', ?, '운영체제', NULL, 2025, 'SPRING', NULL, ?, ?, ?) RETURNING id
        """,
        Long.class,
        title,
        uploaderId,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
  }

  private Long note(String title) {
    return insertNote(title, uploader.getId(), Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }

  private long viewCountOf(Long noteId) {
    Long value =
        jdbcTemplate.queryForObject(
            "SELECT view_count FROM notes WHERE id = ?", Long.class, noteId);
    return value == null ? -1 : value;
  }

  private Timestamp updatedAtOf(Long noteId) {
    return jdbcTemplate.queryForObject(
        "SELECT updated_at FROM notes WHERE id = ?", Timestamp.class, noteId);
  }

  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
    return sessions.as(member, request);
  }

  private void openDetail(Long noteId) throws Exception {
    mockMvc.perform(asMember(get(PATH + "/" + noteId))).andExpect(status().isOk());
  }

  /* ------------------------------------------------------------------ 세는 자리 */

  /** T-405. 상세를 한 번 열면 정확히 1 오른다. */
  @Test
  void countsOneDetailView() throws Exception {
    Long id = note("운영체제 정리본");

    openDetail(id);

    assertThat(viewCountOf(id)).isEqualTo(1);
  }

  /**
   * T-406. <b>중복을 걸러내지 않는다</b> (2-1 §2-1-1, #244 D2).
   *
   * <p>이것이 정책 자체다. 중복 제거를 "나중에 넣을 개선"으로 여긴 구현이 {@code (회원, 자료)} 캐시를 슬쩍 끼워도 T-405는 통과한다 — <b>세 번 열어
   * 3인지 보는 사례가 있어야</b> 그 캐시가 정책 위반으로 드러난다.
   */
  @Test
  void countsEveryViewOfTheSameReader() throws Exception {
    Long id = note("같은 사람이 세 번");

    openDetail(id);
    openDetail(id);
    openDetail(id);

    assertThat(viewCountOf(id)).isEqualTo(3);
  }

  /** T-407. 업로더 본인이 열어도 센다 (#244 D3) — 남이 열었을 때와 규칙이 같다. */
  @Test
  void countsTheUploaderReadingTheirOwnNote() throws Exception {
    Long id = note("내가 올린 것");

    mockMvc.perform(sessions.as(uploader, get(PATH + "/" + id))).andExpect(status().isOk());

    assertThat(viewCountOf(id)).isEqualTo(1);
  }

  /**
   * T-408. <b>세는 자리는 상세 하나뿐이다</b> (3-2 §3-2-4 MUST).
   *
   * <p>목록에서 올리면 한 번 훑을 때마다 스무 건이 함께 오르고, 그때부터 숫자는 "몇 번 열렸나"를 뜻하지 못한다. <b>셋을 한 사례로 묶은 것은 "조회처럼 보이는
   * 경로"가 셋이기 때문이다</b> — 하나만 재면 나머지 둘에 증가가 들어가도 통과한다.
   */
  @Test
  void doesNotCountListingBookmarksOrDownloadUrl() throws Exception {
    Long id = note("목록에서는 안 센다");
    jdbcTemplate.update(
        "INSERT INTO note_files (note_id, original_name, stored_path, size_bytes) VALUES (?, ?, ?, ?)",
        id,
        "정리본.pdf",
        "notes/view-count-test.pdf",
        1024L);
    Long fileId =
        jdbcTemplate.queryForObject("SELECT id FROM note_files WHERE note_id = ?", Long.class, id);
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, ?)",
        member.getId(),
        id,
        Timestamp.from(Instant.now()));

    mockMvc.perform(asMember(get(PATH))).andExpect(status().isOk());
    mockMvc.perform(asMember(get("/api/v1/bookmarks"))).andExpect(status().isOk());
    mockMvc.perform(asMember(get(PATH + "/" + id + "/files/" + fileId))).andExpect(status().isOk());

    assertThat(viewCountOf(id)).isZero();
  }

  /* ------------------------------------------------------------------ 응답의 숫자 */

  /**
   * T-409. 성공했을 때 상세 응답은 <b>올린 뒤의 값</b>이다 (3-2 §3-2-4 MUST).
   *
   * <p>올리기 전 값을 주면 목록으로 돌아갔을 때 1 큰 숫자가 보여 두 화면이 어긋난다. 그래서 <b>직후의 목록 조회와 같은 숫자</b>인지까지 본다.
   */
  @Test
  void detailAnswersWithTheIncreasedCount() throws Exception {
    Long id = note("응답의 숫자");

    mockMvc
        .perform(asMember(get(PATH + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(1));

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].viewCount").value(1));
  }

  /**
   * T-410. <b>세지 못해도 자료는 보인다</b> (3-2 §3-2-4 MUST). 그때 응답은 <b>증가 전 값</b>이다.
   *
   * <p>T-409와 나눈 것은 두 MUST가 부딪치는 자리이기 때문이다 — 증가에 실패하면 "올린 뒤의 값"을 줄 방법이 없고, 없는 증가를 응답에서만 더하면 DB에 없는
   * 숫자가 나가 직후 목록과 어긋난다. 한 사례로 묶으면 <b>어느 쪽 기대값을 쓸지 구현자가 고른다.</b>
   */
  @Test
  void stillAnswersWhenCountingFails() throws Exception {
    Long id = note("세지 못해도 열린다");
    openDetail(id);
    doThrow(new DataAccessResourceFailureException("조회수 증가 실패"))
        .when(notes)
        .increaseViewCount(anyLong());

    mockMvc
        .perform(asMember(get(PATH + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("세지 못해도 열린다"))
        .andExpect(jsonPath("$.viewCount").value(1));

    assertThat(viewCountOf(id)).as("실패했으면 DB도 그대로다").isEqualTo(1);
  }

  /**
   * T-411. <b>{@code updated_at}을 건드리지 않는다</b> (3-2 §3-2-4 MUST).
   *
   * <p>엔티티를 읽어 고치면 수정 시각이 함께 바뀌어 <b>아무도 손대지 않은 자료의 수정일이 오늘이 된다.</b> T-410과 나란히 두는 이유는, 증가를 별도
   * 트랜잭션으로 옮기는 것만으로는 이쪽이 고쳐지지 않기 때문이다 — 엔티티를 쓰는 한 여기서 깨진다.
   */
  @Test
  void doesNotTouchUpdatedAt() throws Exception {
    Long id = insertNote("수정일은 그대로", uploader.getId(), Instant.parse("2025-03-01T00:00:00Z"));
    Timestamp before = updatedAtOf(id);

    openDetail(id);

    assertThat(updatedAtOf(id)).isEqualTo(before);
  }

  /**
   * T-412. <b>동시에 N번 열면 정확히 N이다</b> (3-2 §3-2-4 MUST).
   *
   * <p>{@code SELECT → +1 → UPDATE}로 짠 구현은 <b>혼자 눌러 보는 동안 완벽하게 동작하고</b> 사람이 몰리는 자료에서만 숫자를 잃는다 — 가장
   * 많이 열린 자료가 가장 많이 잃는다. 그래서 사람이 몰리는 상황을 직접 만든다.
   *
   * <p><b>서비스를 직접 부른다.</b> 재려는 것은 "여러 요청이 겹칠 때 더하기를 잃는가"이고, 그 판단은 서비스가 쓰는 문장에 달려 있다.
   */
  @Test
  void losesNothingWhenReadConcurrently() throws Exception {
    Long id = note("동시에 열린다");
    int readers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(readers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(readers);
    AtomicInteger failures = new AtomicInteger();

    try {
      for (int i = 0; i < readers; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                noteViewService.read(member.getId(), id);
              } catch (Exception e) {
                failures.incrementAndGet();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 조회가 끝난다").isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures.get()).as("조회 자체가 실패하지 않는다").isZero();
    assertThat(viewCountOf(id)).isEqualTo(readers);
  }

  /* ------------------------------------------------------------------ 정렬 */

  /**
   * T-413. {@code sort=views}는 조회수 내림차순이고, <b>동률은 {@code id}로 안정 정렬된다</b> (3-2 §3-2-4 MUST).
   *
   * <p>T-228과 같은 위험이지만 더 심한 자리다 — 새로 올라온 자료는 <b>전부 {@code 0}이라 동률이 목록을 통째로 채운다.</b> 마지막 기준이 없으면
   * 페이지를 넘길 때마다 배치가 달라져 같은 자료가 두 번 보이거나 빠진다.
   */
  @Test
  void sortsByViewsWithStableTieBreaker() throws Exception {
    Long few = note("조금 본 것");
    Long many = note("많이 본 것");
    Long tieA = note("동률 A");
    Long tieB = note("동률 B");

    openDetail(few);
    openDetail(many);
    openDetail(many);

    mockMvc
        .perform(asMember(get(PATH + "?sort=views")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(many))
        .andExpect(jsonPath("$.content[1].id").value(few))
        // 조회수가 같은 둘은 id 내림차순이다 — 나중에 들어간 것이 먼저다.
        .andExpect(jsonPath("$.content[2].id").value(Math.max(tieA, tieB)))
        .andExpect(jsonPath("$.content[3].id").value(Math.min(tieA, tieB)));

    // 같은 요청을 다시 보내도 같은 배치다. 마지막 기준이 없으면 여기서 흔들린다.
    mockMvc
        .perform(asMember(get(PATH + "?sort=views&size=2&page=1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(Math.max(tieA, tieB)))
        .andExpect(jsonPath("$.content[1].id").value(Math.min(tieA, tieB)));
  }

  /** 모르는 정렬 값은 기본값으로 본다 — {@code 500}이 아니다 (§3-2-4, #52). */
  @Test
  void treatsUnknownSortAsDefault() throws Exception {
    note("모르는 정렬");

    mockMvc.perform(asMember(get(PATH + "?sort=bogus"))).andExpect(status().isOk());
  }

  /* ------------------------------------------------------------------ 경계 */

  /**
   * T-414. <b>목록과 상세의 숫자가 다른 것이 정상이다.</b>
   *
   * <p>목록은 세지 않고(T-408) 상세는 올린 뒤의 값을 주므로(T-409), 새 자료를 목록에서 보면 {@code 0}이고 처음 열면 {@code 1}이다. 하나로
   * 맞추려 들면 둘 중 하나가 깨진다 — 목록에서 세거나, 상세가 증가 전 값을 주거나.
   */
  @Test
  void listShowsZeroAndFirstDetailShowsOne() throws Exception {
    Long id = note("새로 올라온 자료");

    mockMvc
        .perform(asMember(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].viewCount").value(0));

    mockMvc
        .perform(asMember(get(PATH + "/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(1));
  }

  /**
   * T-415. {@code INACTIVE} 부원은 자료 갈래에서 통째로 막히므로 <b>조회수도 오르지 않는다</b> (T-338).
   *
   * <p>막히는 것과 세지 않는 것이 함께 성립해야 한다 — 필터가 통과시키기 시작하면 여기서도 드러난다.
   */
  @Test
  void doesNotCountWhenInactiveIsBlocked() throws Exception {
    Long id = note("비활동은 못 연다");
    User resting =
        userRepository.saveAndFlush(Accounts.inactive("sub-vi", "vi@khu.ac.kr", "20250103"));

    mockMvc
        .perform(sessions.as(resting, get(PATH + "/" + id)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INACTIVE"));

    assertThat(viewCountOf(id)).isZero();
  }

  /**
   * 없는 자료는 {@code 404}이고 <b>아무것도 올리지 않는다.</b>
   *
   * <p>계약이 "성공할 때 올린다"이므로, 증가를 조회보다 앞에 두면 이 사례가 깨진다.
   */
  @Test
  void doesNotCountMissingNote() throws Exception {
    List<Long> before = jdbcTemplate.queryForList("SELECT view_count FROM notes", Long.class);

    mockMvc.perform(asMember(get(PATH + "/999999"))).andExpect(status().isNotFound());

    assertThat(jdbcTemplate.queryForList("SELECT view_count FROM notes", Long.class))
        .isEqualTo(before);
  }
}
