package org.hackerkhu.hackerhp.domain.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse.Failure;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse.Reason;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입 일괄 승인 (spec 2-2 §2-2-2, 3-1 §3-1-4 ③). */
@Service
public class AdminUserApprovalService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserApprovalService.class);

  private final UserRepository userRepository;

  public AdminUserApprovalService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 고른 계정을 한 번에 승인한다.
   *
   * <p><b>실패를 예외로 던지지 않는다.</b> 한 건 때문에 트랜잭션이 되돌아가면 <b>성공한 승인까지 사라진다</b> — 관리자는 20명을 골랐는데 한 명이 신청서를
   * 내지 않았다는 이유로 아무도 승인되지 않는다. 계약도 "그 건은 실패로 집계하고 그 계정의 상태를 바꾸지 않는다"고 한다 (3-2 §3-2-6 MUST).
   *
   * <p><b>id 오름차순으로 잠근다.</b> 두 관리자가 겹치는 목록을 서로 다른 순서로 보내면 각자 상대가 쥔 행을 기다려 교착한다. 순서를 하나로 정해 두면 그런 짝이
   * 생기지 않는다.
   *
   * <p><b>중복은 여기서 걸러낸다.</b> 요청 DTO에서 걸러내면 {@code @Size} 상한이 원본이 아니라 줄어든 목록을 보게 되어, 같은 id를 101번 담은
   * 요청이 상한을 그냥 통과한다. 거르지 않고 두면 응답의 건수가 부풀려진다 — 화면은 배열 길이를 그대로 "N명을 승인했습니다"로 읽는다.
   */
  @Transactional
  public ApproveResponse approve(List<Long> userIds) {
    List<Long> approved = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();

    for (Long userId : userIds.stream().distinct().sorted().toList()) {
      /*
       * 잠근 채로 다시 읽는다. 화면이 목록을 그린 뒤 관리자가 확인 창을 누르기까지의 사이에
       * 상태가 바뀔 수 있고, 신청서 제출도 같은 행을 노린다 (T-56). 잠그지 않으면 각자
       * 읽어둔 값을 보고 둘 다 통과해, 관리자가 심사한 내용과 저장된 내용이 달라진다.
       */
      Optional<User> found = userRepository.findByIdForUpdate(userId);
      if (found.isEmpty()) {
        failed.add(new Failure(userId, Reason.NOT_FOUND));
        continue;
      }
      User user = found.get();
      if (user.getStatus() != Status.PENDING) {
        failed.add(new Failure(userId, Reason.NOT_PENDING));
        continue;
      }
      // 목록에서 걸렀더라도 API를 직접 부르는 경로가 남아 있다 (T-49).
      if (user.getAppliedAt() == null) {
        failed.add(new Failure(userId, Reason.NOT_APPLIED));
        continue;
      }
      user.approve();
      approved.add(userId);
    }

    log.info("가입 승인: 성공 {}건, 실패 {}건 {}", approved.size(), failed.size(), failed);
    return new ApproveResponse(approved, failed);
  }
}
