package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * <b>본인을 지우면 지금 요청의 세션도 함께 끝나야 한다</b> (spec 2-2 §2-2-4 MUST, #58).
 *
 * <p>저장소에서 세션 행을 지우는 것만으로는 부족하다. 이 요청에 붙어 있는 세션은 <b>응답을 내보낼 때 다시 저장되어</b>, 방금 지운 {@code ADMIN} 세션이
 * 되살아나 만료까지 남는다.
 *
 * <p>그래서 <b>진짜 세션 저장소를 쓰는 컨텍스트</b>로 확인한다 — 세션이 어디에 저장되는지가 이 사례의 전부다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminSelfRemovalIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User admin;
  private SignedIn signedIn;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM admin_actions");
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-admin", "admin@khu.ac.kr", "20200000"));
    // 혼자면 마지막 활성 관리자라 §2-2-7이 막는다. 남을 사람을 하나 둔다.
    userRepository.saveAndFlush(Accounts.admin("sub-other", "other@khu.ac.kr", "20200001"));
    signedIn = sessions.signIn(admin);
  }

  @AfterEach
  void clear() {
    jdbcTemplate.update("DELETE FROM admin_actions");
    userRepository.deleteAll();
  }

  private static boolean expired(MvcResult result, String name) {
    Cookie cookie = result.getResponse().getCookie(name);
    return cookie != null && cookie.getMaxAge() == 0;
  }

  @Test
  void removingYourselfEndsTheCurrentSessionAndToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(Csrf.with(signedIn.on(delete(BASE + "/" + admin.getId()))))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(userRepository.existsById(admin.getId())).isFalse();
    // 응답을 내보낼 때 되살아나지 않는다.
    assertThat(signedIn.storedInRepository()).as("현재 세션이 되살아나면 안 된다").isFalse();
    assertThat(expired(result, "ACCESS_TOKEN")).as("토큰 쿠키도 버린다").isTrue();
  }

  /** 그 쿠키로는 더 이상 아무것도 열리지 않는다. */
  @Test
  void theOldCookiesNoLongerAuthenticate() throws Exception {
    mockMvc
        .perform(Csrf.with(signedIn.on(delete(BASE + "/" + admin.getId()))))
        .andExpect(status().isNoContent());

    mockMvc.perform(signedIn.on(get(BASE))).andExpect(status().isUnauthorized());
  }

  /** 남을 지울 때는 내 세션을 건드리지 않는다. */
  @Test
  void removingSomeoneElseKeepsMySession() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc
        .perform(Csrf.with(signedIn.on(delete(BASE + "/" + member.getId()))))
        .andExpect(status().isNoContent());

    assertThat(signedIn.storedInRepository()).isTrue();
    mockMvc.perform(signedIn.on(get(BASE))).andExpect(status().isOk());
  }
}
