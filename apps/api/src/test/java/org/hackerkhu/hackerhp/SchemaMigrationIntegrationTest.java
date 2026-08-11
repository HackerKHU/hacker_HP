package org.hackerkhu.hackerhp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    User user = User.createFromGoogle("google-sub-1", "member@khu.ac.kr", "테스트");
    user.submitApplication("20240001", "테스트");
    entityManager.persist(user);

    Notice notice = Notice.write("공지 제목", "공지 내용", user);
    entityManager.persist(notice);

    entityManager.flush();
    entityManager.clear();

    User foundUser = entityManager.find(User.class, user.getId());
    assertThat(foundUser.getGoogleSub()).isEqualTo("google-sub-1");
    assertThat(foundUser.getEmail()).isEqualTo("member@khu.ac.kr");
    assertThat(foundUser.getStudentNo()).isEqualTo("20240001");
    assertThat(foundUser.getRole()).isEqualTo(Role.USER);
    assertThat(foundUser.getStatus()).isEqualTo(Status.PENDING);
    assertThat(foundUser.getAppliedAt()).isNotNull();
    assertThat(foundUser.getApprovedAt()).isNull();

    Notice foundNotice = entityManager.find(Notice.class, notice.getId());
    assertThat(foundNotice.getTitle()).isEqualTo("공지 제목");
    assertThat(foundNotice.isPinned()).isFalse();
    assertThat(foundNotice.getAuthor().getId()).isEqualTo(user.getId());
  }

  /*
   * 구글 로그인만 마친 계정은 student_no가 NULL이다. 그런 계정이 둘 이상 공존해야 한다 —
   * NOT NULL이 남아 있거나 UNIQUE가 NULL을 같은 값으로 보면 두 번째 로그인부터 가입이 막힌다.
   * PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 본다 (spec §3-2-2).
   */
  @Test
  void multipleAccountsCanHaveNullStudentNo() {
    entityManager.persist(User.createFromGoogle("google-sub-a", "a@khu.ac.kr", "가"));
    entityManager.persist(User.createFromGoogle("google-sub-b", "b@khu.ac.kr", "나"));

    assertThatCode(entityManager::flush).doesNotThrowAnyException();
  }

  @Test
  void duplicateStudentNoViolatesUniqueConstraint() {
    User first = User.createFromGoogle("google-sub-1", "member1@khu.ac.kr", "테스트1");
    first.submitApplication("20240002", "테스트1");
    entityManager.persist(first);
    entityManager.flush();

    User duplicate = User.createFromGoogle("google-sub-2", "member2@khu.ac.kr", "테스트2");
    duplicate.submitApplication("20240002", "테스트2");

    assertThatThrownBy(
            () -> {
              entityManager.persist(duplicate);
              entityManager.flush();
            })
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  void duplicateGoogleSubViolatesUniqueConstraint() {
    entityManager.persist(User.createFromGoogle("google-sub-same", "one@khu.ac.kr", "하나"));
    entityManager.flush();

    User duplicate = User.createFromGoogle("google-sub-same", "two@khu.ac.kr", "둘");

    assertThatThrownBy(
            () -> {
              entityManager.persist(duplicate);
              entityManager.flush();
            })
        .isInstanceOf(ConstraintViolationException.class);
  }
}
