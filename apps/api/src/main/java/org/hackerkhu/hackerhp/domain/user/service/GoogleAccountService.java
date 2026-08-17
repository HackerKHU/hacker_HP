package org.hackerkhu.hackerhp.domain.user.service;

import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.config.LoginErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구글 로그인으로 계정을 찾거나 만든다 (spec 3-1 §3-1-4 ①).
 *
 * <p>허용 도메인·{@code email_verified} 검사는 이미 지난 뒤에 불린다. 여기서 보는 것은 <b>이 구글 계정에 대응하는 {@code users}
 * 행</b>이다.
 *
 * <p><b>모든 거절은 {@link OAuth2AuthenticationException}으로 던진다</b> (MUST). 콜백 필터는 {@code
 * AuthenticationException}만 잡아 실패 핸들러로 넘긴다. 다른 예외를 던지면 필터 밖으로 빠져나가 <b>브라우저에 500 JSON이 그대로 뜨고</b>,
 * 사용자가 SPA 밖 빈 화면에 갇힌다 (T-43).
 */
@Service
public class GoogleAccountService {

  private static final Logger log = LoggerFactory.getLogger(GoogleAccountService.class);

  private final UserRepository userRepository;

  public GoogleAccountService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 계정을 찾거나 만든다.
   *
   * @param googleSub ID 토큰의 {@code sub}. 계정의 신원 키다
   * @param email ID 토큰의 {@code email}. 바뀌었으면 갱신한다
   * @param name 구글 프로필 이름. <b>계정을 만들 때만 쓴다</b>
   */
  @Transactional
  public User login(String googleSub, String email, String name) {
    User existing = userRepository.findByGoogleSub(googleSub).orElse(null);
    if (existing == null) {
      return create(googleSub, email, name);
    }

    /*
     * 정지 계정은 세션을 발급하지 않고 로그인 화면으로 되돌린다 (3-1 §3-1-5, T-03).
     * 이메일 갱신보다 먼저 본다 — 들여보내지 않을 계정의 행을 건드릴 이유가 없다.
     */
    if (existing.getStatus() == Status.SUSPENDED) {
      throw reject(LoginErrorCode.SUSPENDED);
    }

    updateEmailIfChanged(existing, email);
    return existing;
  }

  /**
   * 로그인 성공 처리에서 세션에 담을 계정을 읽는다.
   *
   * <p>구글이 준 신원에는 우리 {@code users.id}도 {@code role}·{@code status}도 없다. 콜백에서 이미 만들어졌거나 갱신된 행을 여기서
   * 다시 읽는다.
   */
  @Transactional(readOnly = true)
  public User findByGoogleSub(String googleSub) {
    return userRepository
        .findByGoogleSub(googleSub)
        .orElseThrow(
            () -> {
              // 콜백이 방금 만든 계정이 사라졌다. 사용자가 할 수 있는 일이 없으므로 일반 실패로 돌린다.
              log.warn("로그인 직후 계정을 찾지 못했다. sub={}", googleSub);
              return reject(LoginErrorCode.FAILED);
            });
  }

  private User create(String googleSub, String email, String name) {
    /*
     * profile 스코프면 구글이 이름을 준다. 그래도 없으면 이름이 빈 계정을 만드는 대신 로그인을 거부한다 —
     * 이름은 승인 심사 자료다(§3-1-4). 빈 값으로 만들어 두면 관리자가 누구인지 모르는 행이 남는다.
     */
    if (name == null || name.isBlank()) {
      log.warn("구글이 이름을 주지 않아 계정을 만들지 못했다. sub={}", googleSub);
      throw reject(LoginErrorCode.FAILED);
    }

    try {
      return userRepository.saveAndFlush(User.createFromGoogle(googleSub, email, name.trim()));
    } catch (DataAccessException e) {
      // 같은 계정으로 동시에 첫 로그인했거나, 그 이메일을 다른 계정이 막 가져갔다.
      log.warn("계정 생성이 실패했다. sub={}", googleSub, e);
      throw reject(LoginErrorCode.FAILED);
    }
  }

  /**
   * 이메일이 바뀌었으면 갱신한다 (T-45). 갱신하지 않으면 회원 목록과 {@code GET /auth/me}에 옛 주소가 남는다.
   *
   * <p><b>그 주소를 다른 계정이 이미 쓰고 있으면 로그인을 거부한다</b> (T-55). 두 계정을 합치는 것은 사람이 판단할 일이고, 자동으로 합치면 남의 계정에
   * 올라탈 수 있다.
   *
   * <p>이름은 갱신하지 않는다. 신청서에서 본명을 다시 받으므로(§3-1-4 ②), 매 로그인마다 구글 프로필 이름으로 덮으면 <b>관리자가 심사한 이름이 사라진다.</b>
   * 구글 이름은 별명일 수 있다.
   */
  private void updateEmailIfChanged(User user, String email) {
    if (user.getEmail().equals(email)) {
      return;
    }
    userRepository
        .findByEmailAndGoogleSubNot(email, user.getGoogleSub())
        .ifPresent(
            conflicting -> {
              /*
               * 두 계정의 id를 남긴다 (spec 3-2 §3-2-2). 관리자가 어느 쪽이 유효한 계정인지 판단해
               * 정리해야 하는 상황이고, 자동으로 해결하지 않는다. id만 남기고 이메일은 남기지 않는다 —
               * 정리에 필요한 것은 어느 행인지이지 주소가 아니다.
               */
              log.warn(
                  "이메일 충돌로 로그인을 거부했다. 로그인 계정 id={}, 그 주소를 쓰는 계정 id={}",
                  user.getId(),
                  conflicting.getId());
              throw reject(LoginErrorCode.FAILED);
            });

    user.updateEmail(email);
    try {
      userRepository.flush();
    } catch (DataAccessException e) {
      /*
       * 두 가지가 여기 걸린다.
       *   ① 위 조회와 저장 사이에 다른 트랜잭션이 같은 주소를 가져갔다 — UNIQUE 제약 위반
       *   ② 같은 계정이 두 브라우저에서 동시에 로그인했다 — @Version 낙관적 잠금 충돌
       *
       * ②는 DataIntegrityViolationException이 아니라 ObjectOptimisticLockingFailureException이다.
       * DataAccessException으로 받지 않으면 필터 밖으로 빠져나가 콜백에 500 JSON이 뜬다 (T-43).
       */
      log.warn("이메일 갱신이 실패했다. id={}", user.getId(), e);
      throw reject(LoginErrorCode.FAILED);
    }
  }

  private OAuth2AuthenticationException reject(LoginErrorCode code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code.value()), code.value());
  }
}
