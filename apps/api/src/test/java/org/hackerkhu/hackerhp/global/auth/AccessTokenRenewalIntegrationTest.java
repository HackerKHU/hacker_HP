package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.hackerkhu.testsupport.web.ResponseCookies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 신원 토큰을 <b>요청마다 다시 발급하는가</b> (T-421 ~ T-426, spec 3-1 §3-1-5 MUST, #299).
 *
 * <p><b>토큰 수명을 3초로 줄여 띄운다.</b> 30분을 기다릴 수 없어서다. 짧게 두면 몇 초 안에 "수명을 지나도록 계속 쓰는" 상황이 만들어진다.
 *
 * <p>세션 수명은 건드리지 않는다 (30분). 이 결함은 <b>세션은 밀리는데 토큰만 안 밀리는</b> 것이라, 세션이 넉넉해야 그 어긋남이 드러난다.
 *
 * <p><b>"만료된 토큰으로 가면 401"로는 재지 않는다.</b> {@code NimbusJwtDecoder}가 기본으로 <b>60초의 시계 오차</b>를 허용해 방금
 * 만료된 토큰도 1분은 통과하기 때문이다 — 짧게 기다리면 고치지 않아도 통과하고, 1분을 기다리면 사례 하나가 1분을 먹는다. 그래서 결함의 정체인 <b>만료가 밀리지 않는
 * 것</b>을 직접 잰다 (T-423).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.jwt.expiry=3s")
class AccessTokenRenewalIntegrationTest extends AbstractIntegrationTest {

  /** 인증이 서지 않으면 {@code 401}이 나오는 자리. {@code /auth/me}는 비로그인에게도 열려 있어 쓸 수 없다. */
  private static final String PROTECTED_PATH = "/api/v1/notices";

  private static final String TOKEN = "ACCESS_TOKEN";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;

  private User member;

  @BeforeEach
  void createAccount() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.approved("sub-r", "r@khu.ac.kr", "20250201"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  /** 응답이 새로 내려준 토큰 쿠키. 없으면 {@code null}이다. */
  private static Cookie issued(MvcResult result) {
    return result.getResponse().getCookie(TOKEN);
  }

  /** 브라우저가 이 응답을 처리하고 나면 토큰이 사라지는가. 마지막 헤더가 이긴다 ({@link ResponseCookies}). */
  private static boolean discarded(MvcResult result) {
    return ResponseCookies.discarded(result, TOKEN);
  }

  /**
   * 토큰의 만료 시각. <b>서명을 검증하지 않고 읽기만 한다</b> — 여기서 보려는 것은 "언제까지 유효하다고 적혀 있나"이고, 유효한지는 통과 여부가 이미 말해 준다.
   */
  private static Instant expiryOf(String token) throws ParseException {
    return SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime().toInstant();
  }

  /* ------------------------------------------------------------------ 발급 */

  /**
   * T-421. 인증이 성립한 응답에는 <b>언제나 새 토큰이 실린다.</b>
   *
   * <p>이것이 없으면 토큰은 로그인 시각 + 수명에 브라우저가 버린다 — 세션만 밀려 <b>쓰는 도중에 로그아웃</b>된다.
   */
  @Test
  void everyAuthenticatedResponseCarriesAFreshToken() throws Exception {
    SignedIn signedIn = sessions.signIn(member);
    Cookie first = sessions.token(member);

    MvcResult result =
        mockMvc
            .perform(get(PROTECTED_PATH).cookie(signedIn.session(), first))
            .andExpect(status().isOk())
            .andReturn();

    Cookie renewed = issued(result);
    assertThat(renewed).as("새 토큰이 실린다").isNotNull();
    assertThat(renewed.getValue()).isNotBlank();
    assertThat(renewed.getMaxAge()).as("버리는 쿠키가 아니다").isPositive();
  }

  /**
   * T-422. 재발급된 토큰은 <b>같은 사람의 것</b>이다.
   *
   * <p>새 토큰만으로 다음 요청이 통과하고, 그 신원이 원래 사람과 같은지 본다 — 발급이 {@code sub}를 바꾸면 다음 요청이 T-29(주인이 다른 조합)로 걸려
   * 세션까지 폐기된다.
   */
  @Test
  void theRenewedTokenBelongsToTheSameUser() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    Cookie renewed =
        issued(
            mockMvc
                .perform(get(PROTECTED_PATH).cookie(signedIn.session(), sessions.token(member)))
                .andExpect(status().isOk())
                .andReturn());

    mockMvc
        .perform(get("/api/v1/auth/me").cookie(signedIn.session(), renewed))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.email")
                .value("r@khu.ac.kr"));
  }

  /**
   * T-423. <b>쓰는 동안 만료 시각이 계속 밀린다</b> (#299의 재현과 수정).
   *
   * <p>매번 <b>직전 응답이 준 토큰</b>을 실어 보낸다 — 브라우저가 하는 일과 같다. 요청마다 새 토큰의 만료가 앞 것보다 뒤여야 한다.
   *
   * <p><b>왜 "만료된 토큰으로 가면 401"로 재지 않나.</b> {@code NimbusJwtDecoder}는 기본으로 <b>60초의 시계 오차</b>를 허용해, 방금
   * 만료된 토큰도 1분 동안은 통과한다. 그 시간을 기다리면 사례 하나가 1분을 먹고, 짧게 기다리면 <b>고치지 않아도 통과한다.</b> 그래서 결함의 정체—<b>만료가
   * 밀리지 않는 것</b>—을 직접 잰다.
   */
  @Test
  void pushesTheExpiryForwardWhileInUse() throws Exception {
    SignedIn signedIn = sessions.signIn(member);
    Cookie token = sessions.token(member);
    Instant firstExpiry = expiryOf(token.getValue());
    Instant previous = firstExpiry;

    for (int i = 0; i < 3; i++) {
      Thread.sleep(1200);
      MvcResult result =
          mockMvc
              .perform(get(PROTECTED_PATH).cookie(signedIn.session(), token))
              .andExpect(status().isOk())
              .andReturn();
      token = issued(result);
      assertThat(token).as("%d번째 응답이 새 토큰을 준다".formatted(i + 1)).isNotNull();

      Instant renewed = expiryOf(token.getValue());
      assertThat(renewed).as("만료가 앞 것보다 뒤로 밀린다").isAfter(previous);
      previous = renewed;
    }

    /*
     * 갱신이 없었다면 지금쯤 처음 토큰은 이미 만료 시각을 지났다 (수명 3초, 흐른 시간 3.6초).
     * 세션은 30분이라 멀쩡하므로, 밀어 주지 않으면 여기서 "쓰는 도중 로그아웃"이 된다.
     */
    assertThat(firstExpiry).as("처음 토큰은 이미 만료 시각을 지났다").isBefore(Instant.now());
  }

  /**
   * T-424. 발급되는 토큰의 수명은 <b>설정값 그대로</b>다.
   *
   * <p>없으면 발급이 수명을 조금씩 깎아도 T-423이 통과한다 — 만료는 앞 것보다 뒤이기만 하면 되기 때문이다. 그러면 <b>오래 쓸수록 창이 좁아지다</b> 결국 같은
   * 결함으로 돌아온다.
   */
  @Test
  void theRenewedTokenCarriesTheConfiguredLifetime() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    Instant before = Instant.now();
    Cookie renewed =
        issued(
            mockMvc
                .perform(get(PROTECTED_PATH).cookie(signedIn.session(), sessions.token(member)))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(expiryOf(renewed.getValue()))
        .as("발급 시점 + 설정 수명(3초)이다")
        .isBetween(before.plusSeconds(2), Instant.now().plusSeconds(4));
    assertThat(renewed.getMaxAge()).as("쿠키 수명도 같다").isEqualTo(3);
  }

  /* ------------------------------------------------------------------ 발급하면 안 되는 자리 */

  /**
   * T-425. <b>거부하는 조합에서는 발급하지 않고 폐기한다</b> (T-29·T-30).
   *
   * <p>재발급을 인증 성공보다 앞에 두면 <b>거부해야 할 자격을 오히려 갱신해 준다.</b> 세션 없는 토큰과 주인이 다른 조합 둘 다에서 확인한다 — 하나만 재면 나머지
   * 경로에 발급이 들어가도 통과한다.
   */
  @Test
  void doesNotRenewOnRejectedCombinations() throws Exception {
    User other = userRepository.saveAndFlush(Accounts.approved("sub-o", "o@khu.ac.kr", "20250202"));

    // T-30 — 세션 없이 토큰만.
    MvcResult sessionless =
        mockMvc
            .perform(get(PROTECTED_PATH).cookie(sessions.token(member)))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(discarded(sessionless)).as("갱신이 아니라 폐기다").isTrue();

    // T-29 — 남의 세션에 내 토큰.
    MvcResult mismatched =
        mockMvc
            .perform(
                get(PROTECTED_PATH)
                    .cookie(sessions.signIn(other).session(), sessions.token(member)))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(discarded(mismatched)).as("갱신이 아니라 폐기다").isTrue();
  }

  /**
   * T-426. <b>로그아웃은 그대로 성립한다.</b>
   *
   * <p>로그아웃 요청도 이 필터를 지나므로 <b>응답에 {@code ACCESS_TOKEN} 헤더가 둘 실린다</b> — 필터가 쓴 갱신과 컨트롤러가 붙인 폐기. 같은
   * 이름·경로의 쿠키는 <b>나중 것이 앞 것을 덮으므로</b>(RFC 6265) 브라우저에 남는 것은 폐기다.
   *
   * <p><b>순서에 기대는 동작이라 여기서 고정한다.</b> 필터가 {@code chain.doFilter} 뒤에서 쓰도록 옮기면 순서가 뒤집혀 <b>로그아웃해도 토큰이
   * 남는다</b> — 세션이 사라져 인증은 안 되지만, 폐기하기로 한 계약(3-1 §3-1-5)이 깨진다.
   */
  @Test
  void logoutStillDiscardsTheToken() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    MvcResult result =
        mockMvc
            .perform(
                Csrf.with(
                    post("/api/v1/auth/logout").cookie(signedIn.session(), sessions.token(member))))
            .andExpect(status().isNoContent())
            .andReturn();

    List<String> headers = ResponseCookies.headers(result, TOKEN);
    assertThat(headers).as("갱신과 폐기가 함께 실린다").hasSize(2);
    assertThat(headers.getLast()).as("마지막이 폐기다 — 브라우저가 적용하는 것").contains("Max-Age=0");
    assertThat(signedIn.storedInRepository()).as("세션도 사라진다").isFalse();
  }
}
