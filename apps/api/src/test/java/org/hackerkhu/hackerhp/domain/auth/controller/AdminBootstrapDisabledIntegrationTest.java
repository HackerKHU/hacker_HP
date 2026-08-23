package org.hackerkhu.hackerhp.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.auth.repository.BootstrapAttemptRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 부트스트랩 값이 설정되지 않은 서버 (spec 3-3 결정 11).
 *
 * <p><b>기동은 된다.</b> 이 경로는 일회성 운영 경로라 기동 조건으로 묶으면 나중에 토큰을 회전하거나 지우는 순간 API 전체가 죽는다. 대신 그 경로만 닫힌다.
 *
 * <p>응답은 <b>다른 거절과 같다.</b> 설정되지 않았다는 것조차 밖에서 알 수 없다 — 알 수 있으면 "언제 이 경로가 열리는지"를 지켜볼 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminBootstrapDisabledIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/auth/bootstrap-admin";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BootstrapAttemptRepository attempts;
  @Autowired private JwtProvider jwtProvider;

  private User founder;

  @BeforeEach
  void createAccount() {
    attempts.deleteAll();
    userRepository.deleteAll();
    User user = User.createFromGoogle("sub-founder", "founder@khu.ac.kr", "구글이름");
    user.submitApplication("20200001", "컴퓨터공학과");
    founder = userRepository.saveAndFlush(user);
  }

  @AfterEach
  void clear() {
    attempts.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void theEndpointIsClosedWithoutRevealingWhy() throws Exception {
    mockMvc
        .perform(
            Csrf.with(sessions.as(founder, post(PATH)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"anything\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(userRepository.findById(founder.getId()).orElseThrow().getRole())
        .isEqualTo(Role.USER);
  }
}
