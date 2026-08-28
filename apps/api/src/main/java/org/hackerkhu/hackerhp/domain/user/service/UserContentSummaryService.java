package org.hackerkhu.hackerhp.domain.user.service;

import org.hackerkhu.hackerhp.domain.user.dto.ContentSummaryResponse;
import org.hackerkhu.hackerhp.domain.user.repository.AuthoredContentRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정을 지우면 <b>무엇이 남는지</b> (spec 2-2 §2-2-4 MUST).
 *
 * <p>같은 값을 두 확인 창이 쓴다 — 관리자의 제거 창({@code GET /admin/users/{id}/content-summary})과 본인의 탈퇴 창({@code
 * GET /auth/me/content-summary}, #223). <b>세는 방법이 같으므로 한 곳에 둔다.</b>
 *
 * <p><b>본인용 경로에 관리자용을 열어 쓰지 않는다</b> (MUST). 그쪽은 {@code {id}}를 받으므로 열면 <b>남의 콘텐츠 건수를 세어 볼 수 있다.</b>
 * 대상이 언제나 요청자 자신인 경로는 id를 받지 않는다 — 계약이 둘로 갈린 이유가 그것이고, 세는 코드까지 둘일 이유는 없다.
 *
 * <p>이 값은 <b>확인 창을 여는 시점의 참고치</b>이지 삭제의 조건이 아니다.
 */
@Service
@Transactional(readOnly = true)
public class UserContentSummaryService {

  private final UserRepository userRepository;
  private final AuthoredContentRepository authoredContent;

  public UserContentSummaryService(
      UserRepository userRepository, AuthoredContentRepository authoredContent) {
    this.userRepository = userRepository;
    this.authoredContent = authoredContent;
  }

  /**
   * 그 회원이 남길 자료·공지·활동사진·게시글 건수.
   *
   * <p><b>네 값을 항상 담는다</b> (MUST). {@code 0}을 빼면 화면이 "없음"과 "모름"을 가르지 못한다.
   */
  public ContentSummaryResponse of(Long userId) {
    if (!userRepository.existsById(userId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }
    AuthoredContentRepository.Counts counts = authoredContent.countBy(userId);
    return new ContentSummaryResponse(
        counts.notes(), counts.notices(), counts.photos(), counts.posts());
  }
}
