package org.hackerkhu.hackerhp.domain.user.dto;

import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;

/**
 * {@code GET /admin/users}의 필터. 값이 {@code null}이면 그 조건으로 거르지 않는다.
 *
 * @param status 상태 필터 (2-2 §2-2-1)
 * @param role 권한 필터 (2-2 §2-2-1)
 * @param q 이름·학번·이메일 통합 검색어. 대소문자를 가리지 않는 부분 일치다
 * @param applied <b>신청서 제출 여부</b>. {@code PENDING}은 신청한 계정과 구글 로그인만 해본 계정이 섞여 있어, 상태만으로는 "승인 대기"를
 *     고를 수 없다 (3-2 §3-2-6). {@code status=PENDING&applied=true}가 승인 대상 집합이다
 */
public record AdminUserSearch(Status status, Role role, String q, Boolean applied) {

  /** 공백뿐인 검색어는 없는 것으로 본다. 그대로 두면 전체 목록에 무의미한 {@code LIKE '%%'}가 걸린다. */
  public AdminUserSearch {
    q = (q == null || q.isBlank()) ? null : q.trim();
  }
}
