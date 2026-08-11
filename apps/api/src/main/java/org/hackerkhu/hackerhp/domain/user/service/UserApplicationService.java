package org.hackerkhu.hackerhp.domain.user.service;

import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 심사에 필요한 정보를 받는다 (spec 3-1 §3-1-4 ②).
 *
 * <p>구글은 학번을 주지 않으므로 로그인 직후 계정에는 학번이 비어 있다. 이 단계가 그것을 채운다.
 */
@Service
public class UserApplicationService {

  private static final Logger log = LoggerFactory.getLogger(UserApplicationService.class);

  /** PostgreSQL이 {@code student_no}의 UNIQUE에 붙이는 이름 ({@code V1__init.sql}). */
  private static final String STUDENT_NO_UNIQUE_CONSTRAINT = "users_student_no_key";

  private final UserRepository userRepository;

  public UserApplicationService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 신청서를 저장한다. 승인 전까지 다시 내 고칠 수 있다 (T-51).
   *
   * <p><b>행을 잠근 채 상태를 확인한다</b> (§3-1-4 MUST). 잠그지 않으면 제출과 승인이 각자 읽어둔 {@code status}를 보고 모두 통과해, 승인된
   * 계정의 학번·이름이 승인 뒤에 바뀐다 — 관리자가 심사한 내용과 저장된 내용이 달라진다 (T-56).
   *
   * <p>권한(=이 계정이 {@code PENDING}인가)은 {@code @PreAuthorize}가 이미 걸렀다. 여기서 다시 보는 것은 그 판단이 <b>세션의
   * 값</b>이기 때문이다 — 판단과 저장 사이에 관리자가 승인했을 수 있다.
   */
  @Transactional
  public void submit(Long userId, String studentNo, String name) {
    User user =
        userRepository
            .findByIdForUpdate(userId)
            // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

    if (user.getStatus() != Status.PENDING) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이미 승인된 계정입니다. 학번을 바꾸려면 운영진에게 문의해 주세요.");
    }

    if (userRepository.existsByStudentNoAndIdNot(studentNo, userId)) {
      throw new BusinessException(ErrorCode.DUPLICATE_STUDENT_NO);
    }

    user.submitApplication(studentNo, name);
    try {
      userRepository.flush();
    } catch (DataIntegrityViolationException e) {
      if (!violatesStudentNoUniqueness(e)) {
        /*
         * 학번 중복이 아닌 무결성 위반이다. 409로 바꾸면 사용자는 학번을 고치라는 엉뚱한 안내를 받고,
         * 서버 문제가 4xx에 묻혀 감시에서도 빠진다. 그대로 올려 500으로 처리한다.
         */
        throw e;
      }
      // 위 조회와 저장 사이에 다른 계정이 같은 학번을 가져갔다. DB 제약이 마지막 방어선이다.
      log.warn("학번이 제약에 걸렸다. userId={}", userId, e);
      throw new BusinessException(ErrorCode.DUPLICATE_STUDENT_NO);
    }
  }

  /**
   * 이 위반이 <b>학번 유일성</b>인지 본다.
   *
   * <p>{@code DataIntegrityViolationException}은 길이 초과·다른 제약 위반도 함께 나른다. 구분하지 않고 전부 409로 바꾸면 원인과 안내가
   * 어긋난다.
   *
   * <p>제약 이름으로 판단한다. {@code student_no}에 걸린 UNIQUE는 PostgreSQL이 {@code users_student_no_key}로 이름
   * 짓는다 ({@code V1__init.sql}).
   */
  private static boolean violatesStudentNoUniqueness(DataIntegrityViolationException e) {
    String cause = String.valueOf(e.getMostSpecificCause().getMessage());
    return cause.contains(STUDENT_NO_UNIQUE_CONSTRAINT);
  }
}
