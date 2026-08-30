package org.hackerkhu.hackerhp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.dto.RejectResponse;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRejectService;
import org.hackerkhu.hackerhp.domain.user.service.UserApplicationService;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 신청서와 경쟁하는 관리자 조작의 직렬화 (T-56, T-250d).
 *
 * <p>승인과 경쟁할 때는 승인 뒤에 학번·학과가 바뀌지 않아야 하고, 거부와 재신청이 경쟁할 때는 새 신청서가 온전히 남거나 명시적인 거부 이력과 함께 온전히 초기화되어야
 * 한다 (spec/3-1 §3-1-4, 3-2 가입 거부).
 *
 * <p>엔티티의 {@code status} 검사는 <b>각 트랜잭션이 읽어둔 값</b>만 본다. 두 트랜잭션이 같은 {@code PENDING} 행을 각각 읽으면 둘 다
 * 통과하므로, 이 사례는 인메모리 검사로는 절대 잡히지 않는다. 순차 테스트도 잡지 못한다 — 트랜잭션을 실제로 겹쳐야 한다.
 *
 * <p>이 클래스에는 {@code @Transactional}을 붙이지 않는다. 테스트가 트랜잭션 하나로 묶이면 두 트랜잭션을 겹칠 수 없다.
 */
@SpringBootTest
class UserConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private AdminUserRejectService rejectService;
  @Autowired private UserApplicationService applicationService;

  @Test
  void applicationCommittedAfterApprovalIsRejected() {
    Long id =
        inTransaction(
            em -> {
              User user = User.createFromGoogle("google-sub-race", "race@khu.ac.kr", "구글이름");
              user.submitApplication("20240001", "컴퓨터공학과");
              em.persist(user);
              return user.getId();
            });

    // 두 트랜잭션이 같은 행을 각각 읽는다. 이 시점에는 둘 다 status=PENDING을 본다.
    EntityManager approving = entityManagerFactory.createEntityManager();
    EntityManager applying = entityManagerFactory.createEntityManager();
    try {
      approving.getTransaction().begin();
      User toApprove = approving.find(User.class, id);

      applying.getTransaction().begin();
      User toApply = applying.find(User.class, id);

      // 관리자가 먼저 승인하고 커밋한다.
      toApprove.approve();
      approving.getTransaction().commit();

      // 신청 트랜잭션은 자기가 읽어둔 PENDING만 보므로 이 호출 자체는 통과한다.
      toApply.submitApplication("99999999", "인공지능학과");

      assertThatThrownBy(() -> applying.getTransaction().commit())
          .as("승인이 먼저 커밋됐으므로 신청 트랜잭션은 실패해야 한다")
          .isInstanceOfAny(RollbackException.class, OptimisticLockException.class);
    } finally {
      rollbackAndClose(applying);
      rollbackAndClose(approving);
    }

    // 실제로 지켜야 할 것은 이것이다 — 관리자가 심사한 내용이 그대로 남아 있는가.
    inTransaction(
        em -> {
          User saved = em.find(User.class, id);
          assertThat(saved.getStatus()).isEqualTo(Status.ACTIVE);
          assertThat(saved.getStudentNo()).isEqualTo("20240001");
          assertThat(saved.getDepartment()).isEqualTo("컴퓨터공학과");
          return null;
        });
  }

  /**
   * 거부와 재신청이 같은 PENDING 행에서 겹쳐도 잠금 순서대로 직렬화된다.
   *
   * <p>최종 상태는 둘 중 하나뿐이다. 거부가 먼저면 새 신청서가 온전히 남고, 재신청이 먼저면 뒤의 거부가 세 필드를 비우며 {@code REJECT} 이력으로 그 제거를
   * 명시한다. 이전 신청 값이나 절반만 갱신된 값이 남으면 제출이 조용히 유실된 것이다.
   */
  @Test
  void rejectionAndResubmissionAreSerializedWithoutSilentlyLosingTheApplication() throws Exception {
    User admin =
        userRepository.saveAndFlush(
            Accounts.admin("sub-concurrent-admin", "concurrent-admin@khu.ac.kr", "20260001"));
    User applicant =
        userRepository.saveAndFlush(
            Accounts.applied(
                "sub-concurrent-applicant", "concurrent-applicant@khu.ac.kr", "20260002"));
    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<RejectResponse> rejecting =
          pool.submit(
              () -> {
                ready.await();
                return rejectService.reject(admin.getId(), List.of(applicant.getId()));
              });
      Future<?> resubmitting =
          pool.submit(
              () -> {
                ready.await();
                applicationService.submit(applicant.getId(), "20999999", "인공지능학과");
                return null;
              });

      RejectResponse response = rejecting.get(10, TimeUnit.SECONDS);
      resubmitting.get(10, TimeUnit.SECONDS);
      assertThat(response.rejected()).containsExactly(applicant.getId());
      assertThat(response.failed()).isEmpty();
    } finally {
      pool.shutdownNow();
    }

    User saved = userRepository.findById(applicant.getId()).orElseThrow();
    if (saved.getAppliedAt() == null) {
      // 재신청이 먼저 직렬화됐고, 뒤의 거부가 그 제출을 감사 이력과 함께 명시적으로 초기화했다.
      assertThat(saved.getStudentNo()).isNull();
      assertThat(saved.getDepartment()).isNull();
    } else {
      // 거부가 먼저 직렬화됐고, 뒤의 재신청은 세 필드가 한 벌로 온전히 남았다.
      assertThat(saved.getStudentNo()).isEqualTo("20999999");
      assertThat(saved.getDepartment()).isEqualTo("인공지능학과");
    }
    assertThat(saved.getStatus()).isEqualTo(Status.PENDING);
    assertThat(
            actions.findByTargetIdOrderByIdAsc(applicant.getId()).stream()
                .map(AdminActionLog::getAction))
        .containsExactly(AdminAction.REJECT);
  }

  private <T> T inTransaction(Function<EntityManager, T> work) {
    EntityManager em = entityManagerFactory.createEntityManager();
    try {
      em.getTransaction().begin();
      T result = work.apply(em);
      em.getTransaction().commit();
      return result;
    } finally {
      rollbackAndClose(em);
    }
  }

  private static void rollbackAndClose(EntityManager em) {
    if (em.isOpen()) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
  }
}
