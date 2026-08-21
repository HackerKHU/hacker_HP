package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>권한 변경은 이미 로그인해 있는 세션에 즉시 반영된다</b> (spec 3-1 §3-1-5 MUST, T-34, #58).
 *
 * <p>권한 회수의 핵심은 DB의 {@code role}을 바꾸는 것이 아니라 <b>그 사람이 지금 들고 있는 세션이 다음 요청에서 막히는 것</b>이다. 인가는 매 요청 세션
 * 값으로 판단하고 필터는 {@code users}를 다시 읽지 않기 때문에(3-3 결정 12), 세션 반영이 빠지면 <b>회수된 관리자가 만료까지 관리자로 남는다.</b>
 *
 * <p>DB와 이력만 확인하는 테스트는 이 사례를 못 잡는다 — {@code SessionSynchronizer.refresh()} 호출이 통째로 사라져도 통과한다 (#197
 * 리뷰). 그래서 여기서는 <b>회수 전에 만든 그 세션 쿠키</b>를 그대로 다시 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRoleChangeSessionIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User requester;
  private User target;
  private SignedIn requesterSession;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM admin_actions");
    userRepository.deleteAll();
    requester = userRepository.saveAndFlush(Accounts.admin("sub-req", "req@khu.ac.kr", "20200000"));
    target = userRepository.saveAndFlush(Accounts.admin("sub-tgt", "tgt@khu.ac.kr", "20200001"));
    requesterSession = sessions.signIn(requester);
  }

  @AfterEach
  void clear() {
    jdbcTemplate.update("DELETE FROM admin_actions");
    userRepository.deleteAll();
  }

  private String role(String value) {
    return "{\"role\":\"" + value + "\"}";
  }

  /**
   * T-34. <b>이 테스트가 이번 사례의 전부다.</b>
   *
   * <p>대상은 회수 <b>전에</b> 세션을 만들고, 회수 뒤 <b>같은 쿠키</b>로 관리자 API를 부른다. 세션이 갱신되지 않으면 그 세션 값은 여전히 {@code
   * ADMIN}이라 {@code 200}이 나온다.
   */
  @Test
  void revokingAdminBlocksTheSessionTheTargetAlreadyHad() throws Exception {
    SignedIn targetSession = sessions.signIn(target);
    // 회수 전에는 열린다. "원래부터 막혀 있었다"가 아님을 못박는다.
    mockMvc.perform(targetSession.on(get(BASE))).andExpect(status().isOk());

    mockMvc
        .perform(
            Csrf.with(
                requesterSession
                    .on(patch(BASE + "/" + target.getId() + "/role"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(role("USER"))))
        .andExpect(status().isOk());

    mockMvc
        .perform(targetSession.on(get(BASE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    // 지운 것이 아니라 갱신했다 — 지웠으면 401이 되어 화면이 정지·만료를 구별하지 못한다.
    assertThat(targetSession.storedInRepository()).as("세션은 지우지 않고 갱신한다").isTrue();
  }

  /** 반대 방향도 같은 세션에서 즉시 통한다 — 다시 로그인하라고 하지 않는다. */
  @Test
  void grantingAdminOpensTheSessionTheTargetAlreadyHad() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-mem", "mem@khu.ac.kr", "20250002"));
    SignedIn memberSession = sessions.signIn(member);
    mockMvc.perform(memberSession.on(get(BASE))).andExpect(status().isForbidden());

    mockMvc
        .perform(
            Csrf.with(
                requesterSession
                    .on(patch(BASE + "/" + member.getId() + "/role"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(role("ADMIN"))))
        .andExpect(status().isOk());

    mockMvc.perform(memberSession.on(get(BASE))).andExpect(status().isOk());
  }

  /**
   * 제거는 갱신이 아니라 폐기다 (§2-2-4 MUST).
   *
   * <p>계정이 없으면 세션에 써넣을 값이 없다. 남기면 <b>계정 없는 사람이 만료까지 인증된다</b> — 필터는 토큰의 id와 세션 값만 대조한다.
   */
  @Test
  void removingSomeoneDiscardsTheSessionTheyAlreadyHad() throws Exception {
    SignedIn targetSession = sessions.signIn(target);
    mockMvc.perform(targetSession.on(get(BASE))).andExpect(status().isOk());

    mockMvc
        .perform(Csrf.with(requesterSession.on(delete(BASE + "/" + target.getId()))))
        .andExpect(status().isNoContent());

    assertThat(targetSession.storedInRepository()).as("계정이 없으면 세션도 없다").isFalse();
    mockMvc.perform(targetSession.on(get(BASE))).andExpect(status().isUnauthorized());
  }
}
