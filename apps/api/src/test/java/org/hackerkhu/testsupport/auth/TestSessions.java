package org.hackerkhu.testsupport.auth;

import jakarta.servlet.http.Cookie;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 로그인한 상태를 만든다. <b>인증 상태를 바꿔가며 API를 부르는 것이 한 줄이어야 한다</b> (#90).
 *
 * <p><b>진짜 세션 저장소에 만든다.</b> {@code MockHttpSession}으로도 대부분의 검사는 통과하지만, 그러려면 {@code
 * SessionAutoConfiguration}을 꺼야 한다 — 그러면 <b>테스트마다 "어느 쪽 세계냐"를 골라야 하고</b>, 저장소가 없어 세션 반영(#85) 같은 것은
 * 아예 확인할 수 없다. 고를 것이 없는 쪽이 기반으로 낫다.
 *
 * <p>인증은 <b>쿠키 두 개가 함께</b> 있어야 성립한다 (spec 3-1 §3-1-5) — 신원은 {@code ACCESS_TOKEN}(JWT), 인가 상태는
 * {@code SESSION}이다. 그래서 여기서 둘을 함께 만든다. 한쪽만 필요한 사례(T-29·T-30·T-31)는 {@link SignedIn}에서 골라 쓴다.
 */
public class TestSessions {

  public static final String SESSION_COOKIE = "SESSION";
  public static final String TOKEN_COOKIE = "ACCESS_TOKEN";

  private final SessionRepository<? extends Session> sessionRepository;
  private final DefaultCookieSerializer cookieSerializer;
  private final JwtProvider jwtProvider;

  public TestSessions(
      SessionRepository<? extends Session> sessionRepository,
      DefaultCookieSerializer cookieSerializer,
      JwtProvider jwtProvider) {
    this.sessionRepository = sessionRepository;
    this.cookieSerializer = cookieSerializer;
    this.jwtProvider = jwtProvider;
  }

  /** 로그인한 것과 같은 상태를 만들고 <b>브라우저가 받았을 쿠키</b>를 돌려준다. */
  public SignedIn signIn(User user) {
    Session session = sessionRepository.createSession();
    AuthSession.store(session, user);
    save(session);

    MockHttpServletResponse carrier = new MockHttpServletResponse();
    cookieSerializer.writeCookieValue(
        new CookieSerializer.CookieValue(new MockHttpServletRequest(), carrier, session.getId()));
    return new SignedIn(
        session.getId(), carrier.getCookie(SESSION_COOKIE), token(user), sessionRepository);
  }

  /** 그 사람으로 부르는 요청. 대부분의 사례가 쓰는 것은 이것 하나다. */
  public MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
    return signIn(user).on(request);
  }

  /** 신원 토큰만. 세션 없이 토큰만 보내는 사례(T-30)에 쓴다. */
  public Cookie token(User user) {
    return new Cookie(TOKEN_COOKIE, jwtProvider.issue(user.getId()));
  }

  @SuppressWarnings("unchecked")
  private void save(Session session) {
    ((SessionRepository<Session>) sessionRepository).save(session);
  }

  /**
   * 로그인한 브라우저가 들고 있는 것.
   *
   * <p>세션 <b>id</b>를 함께 돌려주는 이유는, 폐기 여부를 저장소에서 직접 확인해야 하는 사례가 있기 때문이다 (T-29 — 응답 쿠키만 지우면 서버에는 살아
   * 있다).
   */
  public record SignedIn(
      String id, Cookie session, Cookie token, SessionRepository<? extends Session> repository) {

    /** 두 쿠키를 모두 실어 보낸다. 인증이 성립하는 정상 경로다. */
    public MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request) {
      return request.cookie(session, token);
    }

    /** 세션 쿠키만. 토큰 없이 세션만 보내는 사례(T-31)다. */
    public MockHttpServletRequestBuilder sessionOnly(MockHttpServletRequestBuilder request) {
      return request.cookie(session);
    }

    /** 저장소에 아직 남아 있는가. 폐기가 응답 쿠키에서 그친 것이 아닌지 본다. */
    public boolean storedInRepository() {
      return repository.findById(id) != null;
    }
  }
}
