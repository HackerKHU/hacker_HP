package org.hackerkhu.hackerhp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import java.util.function.Function;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T-56 — 신청서 제출과 관리자 승인이 동시에 일어나도 승인 뒤에 학번·이름이 바뀌지 않는다 (spec/3-1 §3-1-4).
 *
 * <p>엔티티의 {@code status} 검사는 <b>각 트랜잭션이 읽어둔 값</b>만 본다. 두 트랜잭션이 같은 {@code PENDING} 행을 각각 읽으면 둘 다
 * 통과하므로, 이 사례는 인메모리 검사로는 절대 잡히지 않는다. 순차 테스트도 잡지 못한다 — 트랜잭션을 실제로 겹쳐야 한다.
 *
 * <p>이 클래스에는 {@code @Transactional}을 붙이지 않는다. 테스트가 트랜잭션 하나로 묶이면 두 트랜잭션을 겹칠 수 없다.
 */
@SpringBootTest
class UserConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void applicationCommittedAfterApprovalIsRejected() {
    Long id =
        inTransaction(
            em -> {
              User user = User.createFromGoogle("google-sub-race", "race@khu.ac.kr", "구글이름");
              user.submitApplication("20240001", "본명");
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
      toApply.submitApplication("99999999", "덮어쓴이름");

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
          assertThat(saved.getName()).isEqualTo("본명");
          return null;
        });
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
