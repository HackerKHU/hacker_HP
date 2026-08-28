package org.hackerkhu.hackerhp.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.hackerkhu.hackerhp.domain.user.entity.Department;
import org.hackerkhu.hackerhp.global.auth.PublicApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학과 고정 목록 (#166, spec 3-2 §3-2-2). {@code apps/web}이 지금 목록을 사본으로 갖고 있는데(#165), 이 API가 생기면 그 사본을
 * 지우고 이 응답을 쓴다.
 *
 * <p><b>인증 없이 연다.</b> 신청 폼({@code PENDING})이 쓰는 값이라 로그인 상태에서만 불러도 되지만, 목록 자체가 민감한 정보가 아니라 굳이 가릴 이유가
 * 없다 — 여는 쪽이 더 단순하다.
 */
@Tag(name = "학과", description = "고정 학과 목록. 인증 없이 연다")
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

  @Operation(
      summary = "학과 목록 조회",
      description = "가입 신청 폼이 고르는 학과 고정 목록이다. 자유 입력이 아니라 이 목록에서만 고른다 (spec 3-2 §3-2-2).")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PublicApi(reason = "신청 폼(PENDING)이 쓰는 값이지만, 목록 자체가 민감하지 않아 굳이 로그인으로 가릴 이유가 없다")
  public List<String> departments() {
    return Department.ALL;
  }
}
