package org.hackerkhu.hackerhp.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Department;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /departments} (#166, spec 3-2 §3-2-3).
 *
 * <p>신청 폼(비로그인도 화면은 볼 수 있다)이 쓰는 값이라 <b>인증 없이 열려야 한다</b>는 것이 이 테스트의 핵심이다.
 *
 * <p><b>{@code PENDING} 세션까지 확인하는 이유</b> (#243 리뷰). {@code SecurityConfig}의 {@code permitAll}은
 * {@code AccountStatusFilter}보다 뒤에서 돈다 — 로그인한 신청자의 요청은 인증 쿠키가 함께 실리므로, 그 필터가 먼저 이 경로를 막으면 {@code
 * permitAll}에 닿기도 전에 {@code 403 PENDING_APPROVAL}이 나간다. 이 API의 실제 사용자는 바로 그 {@code PENDING} 신청자이므로,
 * 익명 요청만으로는 이 경로가 실제로 열려 있는지 확인되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DepartmentApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /* 로그인하지 않아도 200이다 — 신청 폼은 로그인 여부와 무관하게 이 값을 그려야 한다. */
  @Test
  void anonymousCanListDepartments() throws Exception {
    mockMvc
        .perform(get("/api/v1/departments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(Department.ALL.size()))
        .andExpect(jsonPath("$[0]").value(Department.ALL.get(0)));
  }

  /*
   * 이 API의 진짜 사용자 — 구글 로그인은 했지만 아직 신청서를 내지 않은 PENDING이 신청 폼을
   * 그리려고 부른다. AccountStatusFilter를 통과하는지가 핵심이다.
   */
  @Test
  void pendingApplicantCanListDepartments() throws Exception {
    User pending =
        userRepository.saveAndFlush(Accounts.signedIn("sub-pending", "pending@khu.ac.kr"));

    mockMvc
        .perform(sessions.as(pending, get("/api/v1/departments")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(Department.ALL.size()));
  }

  /* 정지 계정도 막을 이유가 없다 — AccountStatusFilter의 ALWAYS_OPEN은 상태와 무관하게 연다. */
  @Test
  void suspendedMemberCanListDepartments() throws Exception {
    User suspended =
        userRepository.saveAndFlush(
            Accounts.suspended("sub-suspended", "suspended@khu.ac.kr", "20240001"));

    mockMvc
        .perform(sessions.as(suspended, get("/api/v1/departments")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(Department.ALL.size()));
  }
}
