package org.hackerkhu.hackerhp.global.config;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * 구글이 준 신원을 받아 {@link GoogleAccountPolicy}로 거른다.
 *
 * <p>이것이 없으면 <b>콜백이 성공한 모든 구글 계정이 인증된다.</b> 기본 {@code oauth2Login()}은 토큰 교환에 성공하면 그대로 {@code
 * SecurityContext}와 세션에 신원을 올리므로, 허용 도메인 검사가 어디에도 끼어들지 않는다.
 *
 * <p><b>계정을 만들지 않는다.</b> {@code users} 행 생성과 상태 판단은 #25·#26의 몫이다. 여기서 하는 것은 들여보낼지 말지뿐이다.
 */
@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OidcUserService delegate = new OidcUserService();
  private final GoogleAccountPolicy policy;

  public GoogleOidcUserService(GoogleAccountPolicy policy) {
    this.policy = policy;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    OidcUser user = delegate.loadUser(userRequest);
    policy.verify(user.getEmail(), user.getEmailVerified());
    return user;
  }
}
