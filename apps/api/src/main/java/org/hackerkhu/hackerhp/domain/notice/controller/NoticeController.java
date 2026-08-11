package org.hackerkhu.hackerhp.domain.notice.controller;

import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.service.NoticeService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지 조회. 계약은 spec/3-2 §3-2-5 — 권한은 {@code ACTIVE}다.
 *
 * <p><b>지금은 로그인 여부까지만 걸려 있다.</b> {@code SecurityConfig}의 {@code anyRequest().authenticated()}가
 * 비로그인을 401로 막지만, {@code PENDING}·{@code SUSPENDED}를 가리는 상태 검사(#27)와 이를 명시하는
 * {@code @PreAuthorize}(#28)는 아직 없다. 두 이슈가 끝나면 이 컨트롤러에 권한을 명시한다.
 */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

  private final NoticeService noticeService;

  public NoticeController(NoticeService noticeService) {
    this.noticeService = noticeService;
  }

  @GetMapping
  public Page<NoticeResponse> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return noticeService.list(page, size);
  }

  @GetMapping("/{id}")
  public NoticeResponse get(@PathVariable Long id) {
    return noticeService.get(id);
  }
}
