package org.hackerkhu.hackerhp.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * DB에서 올라온 실패가 <b>전부 인증 실패로 바뀌는지</b> 본다.
 *
 * <p>콜백 필터는 {@code AuthenticationException}만 잡아 실패 핸들러로 넘긴다. 하나라도 새어나가면 브라우저에 500 JSON이 뜨고 사용자가 SPA
 * 밖 빈 화면에 갇힌다 (T-43).
 *
 * <p>동시성 실패는 실제로 재현하기 어렵고 재현해도 타이밍에 흔들린다. 리포지토리를 대역으로 두면 어떤 예외가 올라와도 같은 결론이 나오는지 확실히 볼 수 있다.
 */
class GoogleAccountServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final GoogleAccountService service = new GoogleAccountService(userRepository);

  /**
   * {@code flush()}가 던질 수 있는 것들.
   *
   * <p>낙관적 잠금 충돌은 {@code DataIntegrityViolationException}이 <b>아니다.</b> 같은 계정이 두 브라우저에서 동시에 로그인하면 두
   * 트랜잭션이 같은 {@code @Version}을 읽고, 뒤쪽이 이 예외로 실패한다.
   */
  static java.util.stream.Stream<DataAccessException> databaseFailures() {
    return java.util.stream.Stream.of(
        new DataIntegrityViolationException("email 중복"),
        new ObjectOptimisticLockingFailureException(User.class, 1L));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("databaseFailures")
  void emailUpdateFailuresBecomeLoginFailures(DataAccessException failure) {
    User existing = User.createFromGoogle("sub-1", "old@khu.ac.kr", "이름");
    when(userRepository.findByGoogleSub("sub-1")).thenReturn(Optional.of(existing));
    when(userRepository.findByEmailAndGoogleSubNot(anyString(), anyString()))
        .thenReturn(Optional.empty());
    doThrow(failure).when(userRepository).flush();

    assertThatThrownBy(() -> service.login("sub-1", "new@khu.ac.kr", "이름"))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("failed");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("databaseFailures")
  void accountCreationFailuresBecomeLoginFailures(DataAccessException failure) {
    when(userRepository.findByGoogleSub("sub-1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

    assertThatThrownBy(() -> service.login("sub-1", "member@khu.ac.kr", "이름"))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("failed");
  }

  /* 이메일이 그대로면 조회도 저장도 하지 않는다 — 매 로그인마다 쓰기가 일어나면 안 된다. */
  @Test
  void unchangedEmailSkipsTheConflictLookup() {
    User existing = User.createFromGoogle("sub-1", "member@khu.ac.kr", "이름");
    when(userRepository.findByGoogleSub("sub-1")).thenReturn(Optional.of(existing));

    service.login("sub-1", "member@khu.ac.kr", "이름");

    org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never())
        .findByEmailAndGoogleSubNot(anyString(), anyString());
    org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).flush();
  }
}
