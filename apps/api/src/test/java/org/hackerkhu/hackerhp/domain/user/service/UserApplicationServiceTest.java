package org.hackerkhu.hackerhp.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 저장 실패를 <b>구분해서</b> 옮기는지 본다.
 *
 * <p>무결성 위반을 전부 {@code 409 DUPLICATE_STUDENT_NO}로 바꾸면, 학번과 무관한 장애까지 "학번을 고치라"는 안내가 되고 서버 문제가 4xx에
 * 묻혀 감시에서도 빠진다.
 */
class UserApplicationServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final UserApplicationService service = new UserApplicationService(userRepository);

  private void givenPendingApplicant() {
    when(userRepository.findByIdForUpdate(anyLong()))
        .thenReturn(Optional.of(User.createFromGoogle("sub-1", "member@khu.ac.kr", "이름")));
    when(userRepository.existsByStudentNoAndIdNot(anyString(), anyLong())).thenReturn(false);
  }

  @Test
  void studentNoUniquenessViolationBecomesConflict() {
    givenPendingApplicant();
    doThrow(
            new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException(
                    "ERROR: duplicate key value violates unique constraint"
                        + " \"users_student_no_key\"")))
        .when(userRepository)
        .flush();

    assertThatThrownBy(() -> service.submit(1L, "20240001", "이름"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("학번");
  }

  /* 다른 제약 위반은 그대로 올라가 500이 된다 — 사용자가 고칠 수 있는 문제가 아니다. */
  @Test
  void otherIntegrityViolationsAreNotDisguisedAsConflict() {
    givenPendingApplicant();
    DataIntegrityViolationException unrelated =
        new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException("ERROR: value too long for type character varying(20)"));
    doThrow(unrelated).when(userRepository).flush();

    assertThatThrownBy(() -> service.submit(1L, "20240001", "이름")).isSameAs(unrelated);
  }
}
