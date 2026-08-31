package org.hackerkhu.hackerhp.domain.post.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.post.dto.PostCreateRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostDetailResponse;
import org.hackerkhu.hackerhp.domain.post.dto.PostSummaryResponse;
import org.hackerkhu.hackerhp.domain.post.service.PostService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자유 게시판 (spec 2-1 §2-1-8, 3-2 §3-2-5).
 *
 * <p><b>조회·등록·수정·삭제는 {@code isAuthenticated()}를 적는다.</b> 수정·삭제의 작성자 판정과 삭제의 관리자 판정은 저장된 글을 읽어야 하므로
 * 서비스가 잠근 최신 계정·게시글 행으로 한다. 승인 대기·정지는 {@code AccountStatusFilter}가 먼저 막는다.
 *
 * <p>수정은 작성자 본인만 가능하고, 삭제는 활성 관리자 또는 작성자 본인에게 열린다 (결정 20·21, #238·#256).
 */
@Tag(name = "자유 게시판", description = "승인된 부원끼리 글을 올리고 읽는다. SUSPENDED는 제외한다")
@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

  private final PostService postService;

  public PostController(PostService postService) {
    this.postService = postService;
  }

  @Operation(
      summary = "게시글 목록",
      description =
          """
          최신순 고정이다. **정렬 파라미터를 받지 않는다** — `sort`를 보내도 무시된다.

          정렬 기준은 `createdAt DESC, id DESC`이며 **마지막 기준이 `id`인 것이 핵심이다.**
          같은 시각에 올라온 글이 있으면 그것이 없을 때 페이지를 넘길 때마다 배치가 달라져
          **같은 글이 두 번 보이거나 아예 빠진다.**

          **본문은 담지 않는다.** 상세에서만 준다 — 본문 상한이 10,000자라 20건이면
          그것만으로 응답이 200KB가 된다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<PostSummaryResponse> list(@ParameterObject Pageable pageable) {
    return new PagedModel<>(postService.list(pageable));
  }

  @Operation(
      summary = "게시글 상세",
      description =
          """
          목록에 `content`와 `updatedAt`이 더해진다.

          **본문은 받은 그대로 나간다.** 서버가 정화하거나 마크다운으로 해석하지 않는다 —
          화면이 텍스트 노드로 그리는 것까지가 이 계약의 일부다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public PostDetailResponse get(@PathVariable Long id) {
    return postService.get(id);
  }

  /**
   * 글 등록.
   *
   * <p><b>작성자는 인증 주체로만 정한다</b> (MUST). 본문으로 받으면 다른 사람 이름으로 글을 올릴 수 있다.
   */
  @Operation(
      summary = "게시글 등록",
      description =
          """
          제목과 본문을 받는다. **작성자는 로그인한 사람이다** — 본문으로 받지 않는다.

          **본문은 평문으로 저장된다.** HTML이나 마크다운을 넣어도 해석되지 않고 글자
          그대로 남는다 — 전 부원이 쓰는 자리라 서식을 허용하면 그 입력이 다른 부원의
          브라우저에서 실행될 수 있는 표면이 된다 (3-3 결정 16).

          **수정은 작성자 본인만 할 수 있다.** 삭제는 관리자 또는 작성자 본인이 할 수 있다.
          """)
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 글이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 제목·본문이 비었거나(공백뿐인 경우 포함) 상한(200자 / 10,000자)을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public PostDetailResponse write(
      @AuthenticationPrincipal Long authorId, @Valid @RequestBody PostCreateRequest request) {
    return postService.write(authorId, request);
  }

  /**
   * 글 수정 (#256).
   *
   * <p><b>작성자 본인만</b> 할 수 있다. 관리자 역할도 남의 글을 수정하는 예외를 만들지 않는다. 제목·본문을 <b>보낸 것으로 통째로 바꾼다.</b>
   */
  @Operation(
      summary = "게시글 수정",
      description =
          """
          제목과 본문을 보낸 것으로 통째로 바꾼다. **작성자 본인만** 할 수 있다 —
          관리자도 예외가 아니다.

          **수정 기한은 없다.** 등록 뒤 언제든 고칠 수 있다. `updatedAt`이
          `createdAt`과 달라지므로 화면은 이 값으로 "수정됨"을 표시할 수 있다.
          """)
  @ApiResponse(responseCode = "200", description = "수정됨. 본문은 저장된 글이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 제목·본문이 비었거나(공백뿐인 경우 포함) 상한(200자 / 10,000자)을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — 본인이 쓴 글이 아니다 · CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public PostDetailResponse edit(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long id,
      @Valid @RequestBody PostCreateRequest request) {
    return postService.edit(requesterId, id, request);
  }

  /**
   * 글 삭제 (#238).
   *
   * <p><b>활성 관리자 또는 작성자 본인</b>이 할 수 있다. <b>완전 삭제</b>이며 되돌릴 수 없다.
   */
  @Operation(
      summary = "게시글 삭제",
      description =
          """
          **활성 관리자 또는 작성자 본인**이 지울 수 있다. 비활동 부원도 자기 글은
          지울 수 있지만, 승인 대기·정지 계정과 탈퇴·제거되어 작성자 관계가 끊긴 계정은
          지울 수 없다.

          **완전 삭제다.** 감추는 것이 아니라 행 자체가 사라지며, 되돌릴 수 없다.
          """)
  @ApiResponse(responseCode = "204", description = "삭제됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 하며, 요청 중 계정이 제거된 경우도 포함한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — 활성 관리자가 아니고 본인이 쓴 글도 아니다 · CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void delete(@AuthenticationPrincipal Long requesterId, @PathVariable Long id) {
    postService.delete(requesterId, id);
  }
}
