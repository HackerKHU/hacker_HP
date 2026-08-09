package org.hackerkhu.hackerhp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hackerkhu.hackerhp.domain.notice.entity.Notice;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** V1__init.sql이 spec/3-2-DESIGN-CONTRACT.md §3-2-2와 일치하는지 ddl-auto=validate로 검증한다. */
@SpringBootTest
@Transactional
class SchemaMigrationIntegrationTest extends AbstractIntegrationTest {

  @PersistenceContext private EntityManager entityManager;

  @Test
  void userAndNoticePersistAndLoadAccordingToSchema() {
    User user = User.applyForMembership("member@hackerkhu.org", "20240001", "테스트", "hashed");
    entityManager.persist(user);

    Notice notice = Notice.write("공지 제목", "공지 내용", user);
    entityManager.persist(notice);

    entityManager.flush();
    entityManager.clear();

    User foundUser = entityManager.find(User.class, user.getId());
    assertThat(foundUser.getEmail()).isEqualTo("member@hackerkhu.org");
    assertThat(foundUser.getStudentNo()).isEqualTo("20240001");
    assertThat(foundUser.getRole()).isEqualTo(Role.USER);
    assertThat(foundUser.getStatus()).isEqualTo(Status.PENDING);
    assertThat(foundUser.getApprovedAt()).isNull();

    Notice foundNotice = entityManager.find(Notice.class, notice.getId());
    assertThat(foundNotice.getTitle()).isEqualTo("공지 제목");
    assertThat(foundNotice.isPinned()).isFalse();
    assertThat(foundNotice.getAuthor().getId()).isEqualTo(user.getId());
  }

  @Test
  void duplicateStudentNoViolatesUniqueConstraint() {
    User first = User.applyForMembership("member1@hackerkhu.org", "20240002", "테스트1", "hashed1");
    entityManager.persist(first);
    entityManager.flush();

    User duplicate =
        User.applyForMembership("member2@hackerkhu.org", "20240002", "테스트2", "hashed2");

    assertThatThrownBy(() -> entityManager.persist(duplicate))
        .isInstanceOf(ConstraintViolationException.class);
  }
}
