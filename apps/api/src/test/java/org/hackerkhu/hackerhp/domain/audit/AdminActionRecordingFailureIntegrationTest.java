package org.hackerkhu.hackerhp.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * <b>기록은 조작의 조건이 아니다</b> (spec 2-2 §2-2-7 MUST, #143).
 *
 * <p>이력을 남기지 못했다고 정지가 되돌아가면 안 된다. 예외가 올라가면 관리자는 "정지 실패"로 읽고 다시 누르는데, <b>실제로는 이미 정지돼 있다.</b>
 *
 * <p>저장소를 터지게 만들어 확인한다 — DB 장애나 커넥션 고갈이 그 자리다.
 */
@SpringBootTest
class AdminActionRecordingFailureIntegrationTest extends AbstractIntegrationTest {

  @MockitoBean private AdminActionLogRepository logs;

  @Autowired private AdminUserStatusService statusService;
  @Autowired private UserRepository userRepository;

  private User admin;
  private User member;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-admin", "admin@khu.ac.kr", "20200000"));
    member =
        userRepository.saveAndFlush(
            Accounts.approved("sub-member", "member@khu.ac.kr", "20250001"));
    given(logs.saveAll(any())).willThrow(new DataAccessResourceFailureException("이력 저장소가 죽었다"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  @Test
  void suspensionSucceedsEvenWhenTheRecordFails() {
    assertThatCode(() -> statusService.change(admin.getId(), member.getId(), Target.SUSPENDED))
        .doesNotThrowAnyException();

    assertThat(userRepository.findById(member.getId()).orElseThrow().getStatus())
        .as("이력이 실패해도 정지는 남아야 한다")
        .isEqualTo(Status.SUSPENDED);
  }
}
