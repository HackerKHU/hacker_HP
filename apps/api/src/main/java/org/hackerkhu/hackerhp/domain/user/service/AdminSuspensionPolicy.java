package org.hackerkhu.hackerhp.domain.user.service;

import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/** 관리자 계정을 직접 정지하지 못하게 하는 회원 관리 정책 (spec 2-2 §2-2-3, #296). */
@Component
public class AdminSuspensionPolicy {

  public static final String MESSAGE = "관리자 계정은 바로 정지할 수 없습니다. 먼저 관리자 권한을 회수한 뒤 정지해 주세요.";

  /**
   * 잠근 대상의 현재 값으로 직접 정지가 가능한지 확인한다.
   *
   * <p>요청자 인가, 행 잠금, 존재 확인은 호출 서비스가 먼저 끝내야 한다. 이 컴포넌트는 대상마다 같은 정책을 적용할 수 있도록 대상과 원하는 상태만 판단한다 — 일괄
   * 상태 변경(#313)도 잠금 후 이 메서드를 그대로 쓴다.
   *
   * <p>회원 제거·본인 탈퇴가 삭제 전에 수행하는 내부 정지는 이 정책의 대상이 아니다. 그 경로는 세션 폐기 실패에도 접근을 차단하기 위한 안전 절차이므로, 엔티티나 DB
   * 제약으로 이 규칙을 내리지 않는다.
   */
  public void requireDirectSuspensionAllowed(User target, Status desired) {
    if (blocksDirectSuspension(target, desired)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, MESSAGE);
    }
  }

  /** 일괄 처리가 같은 정책을 항목별 실패로 바꿀 수 있게 판정만 노출한다. */
  public boolean blocksDirectSuspension(User target, Status desired) {
    return desired == Status.SUSPENDED && target.getRole() == Role.ADMIN;
  }
}
