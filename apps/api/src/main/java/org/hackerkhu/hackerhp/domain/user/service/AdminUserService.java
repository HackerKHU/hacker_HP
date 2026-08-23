package org.hackerkhu.hackerhp.domain.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserSearch;
import org.hackerkhu.hackerhp.domain.user.dto.ContentSummaryResponse;
import org.hackerkhu.hackerhp.domain.user.repository.AdminUserSpecifications;
import org.hackerkhu.hackerhp.domain.user.repository.AuthoredContentRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 목록 조회 (spec 2-2 §2-2-1). 가입 승인 화면(#30)이 이 결과 위에 선다. */
@Service
@Transactional(readOnly = true)
public class AdminUserService {

  /**
   * 정렬할 수 있는 필드. <b>화면이 쓰는 셋뿐이다</b> (2-2 §2-2-1 — 이름, 학번, 가입 신청일).
   *
   * <p>{@code Pageable}은 요청이 보낸 필드명을 그대로 받는다. 걸러내지 않으면 {@code sort=googleSub}로 구글 계정 식별자 순서를 알아낼 수
   * 있고, 존재하지 않는 필드는 질의 단계에서 터져 {@code 500}이 된다.
   */
  private static final Set<String> SORTABLE = Set.of("name", "studentNo", "appliedAt");

  /**
   * 기본 정렬 — 가입 신청일 최신순. 승인 대기자를 먼저 처리하는 화면이라 그렇다 (2-2 §2-2-1).
   *
   * <p>"가입 신청일"은 {@code appliedAt}이다 (MUST). 계정 생성 시각인 {@code createdAt}과는 며칠 차이가 날 수 있다.
   */
  private static final List<Sort.Order> DEFAULT_ORDER = List.of(Sort.Order.desc("appliedAt"));

  private final UserRepository userRepository;
  private final AuthoredContentRepository authoredContent;

  public AdminUserService(
      UserRepository userRepository, AuthoredContentRepository authoredContent) {
    this.authoredContent = authoredContent;
    this.userRepository = userRepository;
  }

  public Page<AdminUserResponse> search(AdminUserSearch search, Pageable pageable) {
    List<Sort.Order> orders = checkedOrder(pageable.getSort());
    return userRepository
        .findAll(
            AdminUserSpecifications.matching(search, orders),
            // 순서는 명세가 직접 만든다. 널의 위치를 Pageable로는 정할 수 없다.
            PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
        .map(AdminUserResponse::from);
  }

  /**
   * 요청한 정렬을 검사하고 비어 있으면 기본값을 채운다.
   *
   * <p>허용되지 않은 필드는 <b>무시하지 않고 거절한다.</b> 조용히 버리면 관리자는 정렬이 적용된 줄 알고 그 순서를 신뢰한다 — 잘못된 순서의 명단으로 승인·정지를
   * 누르는 것이 {@code 400}을 받는 것보다 나쁘다.
   */
  private static List<Sort.Order> checkedOrder(Sort requested) {
    List<Sort.Order> orders = new ArrayList<>();
    for (Sort.Order order : requested) {
      if (!SORTABLE.contains(order.getProperty())) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "정렬은 이름·학번·가입 신청일로만 할 수 있습니다.");
      }
      orders.add(order);
    }
    return orders.isEmpty() ? DEFAULT_ORDER : orders;
  }

  /**
   * 제거하면 <b>무엇이 남는지</b> (spec 2-2 §2-2-4 MUST).
   *
   * <p>제거는 관리자가 혼자 시작할 수 있고 사전 통지도 없다. 작성자 관계가 끊기고 나면 <b>운영자도 그 회원의 콘텐츠를 찾을 수 없으므로</b>, 관계를 끊기 전에
   * 무엇이 남는지 보여준다.
   */
  public ContentSummaryResponse contentSummary(Long userId) {
    if (!userRepository.existsById(userId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }
    AuthoredContentRepository.Counts counts = authoredContent.countBy(userId);
    return new ContentSummaryResponse(
        counts.notes(), counts.notices(), counts.photos(), counts.posts());
  }
}
