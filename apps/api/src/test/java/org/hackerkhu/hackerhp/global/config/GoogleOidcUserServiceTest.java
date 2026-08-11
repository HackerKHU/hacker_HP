package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hackerkhu.hackerhp.domain.user.service.GoogleAccountService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 콜백 경로에서 <b>검사와 계정 처리가 실제로 불리는지, 그 순서가 맞는지</b> 본다.
 *
 * <p>두 협력자를 각각 따로 시험하는 것으로는 부족하다. 이 호출이 빠지거나 순서가 뒤바뀌어도 그쪽 테스트는 전부 통과한다 — 그런데 순서가 뒤바뀌면 거부할 계정의 행이 먼저
 * 만들어져 T-08·T-41("계정이 생성되지 않는다")이 깨진다.
 */
class GoogleOidcUserServiceTest {

  private static final String SUB = "google-sub-1";
  private static final String EMAIL = "member@khu.ac.kr";
  private static final String NAME = "구글이름";

  @SuppressWarnings("unchecked")
  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate =
      mock(OAuth2UserService.class);

  private final GoogleAccountPolicy policy = mock(GoogleAccountPolicy.class);
  private final GoogleAccountService accountService = mock(GoogleAccountService.class);

  private final GoogleOidcUserService userService =
      new GoogleOidcUserService(delegate, policy, accountService);

  private static OidcUser googleUser() {
    Map<String, Object> claims =
        Map.of(
            StandardClaimNames.SUB, SUB,
            StandardClaimNames.EMAIL, EMAIL,
            StandardClaimNames.EMAIL_VERIFIED, true,
            StandardClaimNames.NAME, NAME);
    return new DefaultOidcUser(
        List.of(new SimpleGrantedAuthority("OIDC_USER")),
        new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims));
  }

  private OidcUser loadUser() {
    when(delegate.loadUser(any())).thenReturn(googleUser());
    return userService.loadUser(mock(OidcUserRequest.class));
  }

  @Test
  void passesGoogleClaimsToTheAccountService() {
    loadUser();

    verify(policy).verify(EMAIL, true);
    verify(accountService).login(SUB, EMAIL, NAME);
  }

  /*
   * 검사가 계정 처리보다 먼저다. 순서가 바뀌면 도메인이 다른 사람의 행이 먼저 만들어지고,
   * 그 뒤에 거부해도 users에 남는다.
   */
  @Test
  void verifiesBeforeTouchingAccounts() {
    loadUser();

    InOrder inOrder = Mockito.inOrder(policy, accountService);
    inOrder.verify(policy).verify(anyString(), any());
    inOrder.verify(accountService).login(anyString(), anyString(), anyString());
  }

  /* T-08·T-41 — 검사에서 거절되면 계정을 만들지 않는다. */
  @Test
  void rejectedAccountNeverReachesTheAccountService() {
    when(delegate.loadUser(any())).thenReturn(googleUser());
    Mockito.doThrow(new OAuth2AuthenticationException(new OAuth2Error("domain"), "domain"))
        .when(policy)
        .verify(anyString(), any());

    assertThatThrownBy(() -> userService.loadUser(mock(OidcUserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("domain");

    verify(accountService, never()).login(anyString(), anyString(), anyString());
  }

  /* 계정 처리가 던진 거절도 그대로 올라가야 실패 핸들러가 받는다 (T-03·T-55). */
  @Test
  void accountRejectionPropagates() {
    when(delegate.loadUser(any())).thenReturn(googleUser());
    when(accountService.login(anyString(), anyString(), anyString()))
        .thenThrow(new OAuth2AuthenticationException(new OAuth2Error("suspended"), "suspended"));

    assertThatThrownBy(() -> userService.loadUser(mock(OidcUserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("suspended");
  }

  @Test
  void returnsWhatGoogleGave() {
    OidcUser loaded = loadUser();

    assertThat(loaded.getSubject()).isEqualTo(SUB);
    assertThat(loaded.getEmail()).isEqualTo(EMAIL);
  }
}
