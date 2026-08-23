package org.hackerkhu.hackerhp.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * 구글 로그인이 계정을 어떻게 찾고 만드는지 (spec 3-1 §3-1-4 ①).
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 거절이 실제로 롤백되는지 보려면 서비스 호출마다 트랜잭션이 따로 끝나야 한다. 테스트가 트랜잭션을
 * 들고 있으면 서비스가 거기 올라타, 거절 뒤에도 쓴 내용이 남아 있는지 확인할 수 없다.
 */
@SpringBootTest
class GoogleAccountServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private GoogleAccountService googleAccountService;
  @Autowired private UserRepository userRepository;

  @BeforeEach
  void clear() {
    userRepository.deleteAll();
  }

  /* 롤백에 기대지 않으므로 뒤에도 지운다. 남기면 같은 이메일을 쓰는 다른 테스트가 제약에 걸린다. */
  @AfterEach
  void clearAfter() {
    userRepository.deleteAll();
  }

  @Test
  void firstLoginCreatesPendingUserAccount() {
    User created = googleAccountService.login("sub-1", "member@khu.ac.kr", "구글이름");

    assertThat(created.getRole()).isEqualTo(Role.USER);
    assertThat(created.getStatus()).isEqualTo(Status.PENDING);
    // 구글은 학번을 주지 않는다. 신청서(#84)가 채운다.
    assertThat(created.getStudentNo()).isNull();
    assertThat(created.getAppliedAt()).isNull();
    assertThat(userRepository.count()).isEqualTo(1);
  }

  /**
   * 학교 Google Workspace 이름은 {@code 손수민[학생](소프트웨어융합대학 컴퓨터공학부)}처럼 실명 뒤에 학적 정보가 붙는다. 첫 대괄호 앞까지만 남긴다 —
   * 이름 글자 수(외자·4자 이상)는 결과에 영향을 주지 않아야 한다.
   */
  @Test
  void schoolWorkspaceSuffixIsStrippedRegardlessOfNameLength() {
    User oneCharName = googleAccountService.login("sub-1", "one@khu.ac.kr", "김[학생](컴퓨터공학부)");
    User fourCharName =
        googleAccountService.login("sub-2", "four@khu.ac.kr", "남궁민수[학생](소프트웨어융합대학 컴퓨터공학부)");
    User plainName = googleAccountService.login("sub-3", "plain@khu.ac.kr", "홍길동");

    assertThat(oneCharName.getName()).isEqualTo("김");
    assertThat(fourCharName.getName()).isEqualTo("남궁민수");
    // 접미사가 없는 이름은 그대로 유지된다.
    assertThat(plainName.getName()).isEqualTo("홍길동");
  }

  /* T-06 — 같은 구글 계정으로 다시 로그인하면 새 계정을 만들지 않는다. */
  @Test
  void secondLoginReusesAccountFoundByGoogleSub() {
    User first = googleAccountService.login("sub-1", "member@khu.ac.kr", "구글이름");

    User second = googleAccountService.login("sub-1", "member@khu.ac.kr", "구글이름");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(userRepository.count()).isEqualTo(1);
  }

  /*
   * T-45 — 구글에서 이메일이 바뀐 채 같은 sub로 로그인하면 users.email이 갱신된다.
   * 갱신하지 않으면 회원 목록과 GET /auth/me에 옛 주소가 남는다.
   */
  @Test
  void changedEmailIsUpdatedOnTheSameAccount() {
    Long id = googleAccountService.login("sub-1", "old@khu.ac.kr", "구글이름").getId();

    googleAccountService.login("sub-1", "new@khu.ac.kr", "구글이름");

    assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("new@khu.ac.kr");
    assertThat(userRepository.count()).isEqualTo(1);
  }

  /*
   * T-55 — 바뀐 이메일을 다른 계정이 이미 쓰고 있으면 로그인을 거부한다.
   *
   * 두 계정을 합치는 것은 사람이 판단할 일이다. 자동으로 합치면 학교가 재활용한 주소를 받은 사람이
   * 남의 계정에 올라탄다.
   */
  @Test
  void loginIsRejectedWhenChangedEmailBelongsToAnotherAccount() {
    googleAccountService.login("sub-other", "taken@khu.ac.kr", "다른사람");
    Long id = googleAccountService.login("sub-1", "mine@khu.ac.kr", "나").getId();

    assertThatThrownBy(() -> googleAccountService.login("sub-1", "taken@khu.ac.kr", "나"))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("failed");

    // 합쳐지지도, 덮어쓰이지도 않았다.
    assertThat(userRepository.count()).isEqualTo(2);
    assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("mine@khu.ac.kr");
  }

  /* T-03 — 정지된 계정은 로그인이 막힌다. 화면은 /login?error=suspended로 안내한다. */
  @Test
  void suspendedAccountCannotLogIn() {
    User user = googleAccountService.login("sub-1", "member@khu.ac.kr", "구글이름");
    suspend(user.getId());

    assertThatThrownBy(() -> googleAccountService.login("sub-1", "member@khu.ac.kr", "구글이름"))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("suspended");
  }

  /* 정지 계정의 행은 건드리지 않는다. 들여보내지 않을 계정을 갱신할 이유가 없다. */
  @Test
  void suspendedAccountKeepsItsStoredEmail() {
    User user = googleAccountService.login("sub-1", "old@khu.ac.kr", "구글이름");
    suspend(user.getId());

    assertThatThrownBy(() -> googleAccountService.login("sub-1", "new@khu.ac.kr", "구글이름"))
        .isInstanceOf(OAuth2AuthenticationException.class);

    assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail())
        .isEqualTo("old@khu.ac.kr");
  }

  /*
   * 구글 프로필 이름이 바뀌어도 users.name은 그대로다.
   *
   * 신청서가 이름을 받지 않으므로(#224) 이 값이 관리자가 심사한 이름이자 회원 목록에 뜨는 이름이다.
   * 매 로그인마다 덮으면 그 이름이 본인도 관리자도 모르게 바뀐다 — 구글 표시 이름은 별명으로
   * 바꿀 수 있는 값이다.
   */
  @Test
  void googleProfileNameDoesNotOverwriteStoredName() {
    Long id = googleAccountService.login("sub-1", "member@khu.ac.kr", "처음이름").getId();

    googleAccountService.login("sub-1", "member@khu.ac.kr", "바뀐별명");

    assertThat(userRepository.findById(id).orElseThrow().getName()).isEqualTo("처음이름");
  }

  /*
   * 이름이 비어 있으면 계정을 만들지 않는다. 이름은 승인 심사 자료이므로, 빈 값으로 만들어 두면
   * 관리자가 누구인지 모르는 행이 남는다.
   */
  @Test
  void blankGoogleNameIsRejectedInsteadOfCreatingNamelessAccount() {
    assertThatThrownBy(() -> googleAccountService.login("sub-1", "member@khu.ac.kr", "  "))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage("failed");

    assertThat(userRepository.count()).isZero();
  }

  /** 관리자가 정지시킨 상태를 만든다. 신청 → 승인 → 정지가 실제 경로다 (§3-1-4). */
  private void suspend(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    user.submitApplication("20240001", "컴퓨터공학과");
    user.approve();
    user.suspend();
    userRepository.saveAndFlush(user);
  }
}
