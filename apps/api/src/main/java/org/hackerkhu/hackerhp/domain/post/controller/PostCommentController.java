package org.hackerkhu.hackerhp.domain.post.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.hackerkhu.hackerhp.domain.post.dto.PostCommentRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostCommentResponse;
import org.hackerkhu.hackerhp.domain.post.service.PostCommentService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
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
 * 자유 게시판 댓글 (spec 2-1 §2-1-8, 3-2 §3-2-6, #347).
 *
 * <p><b>조회·등록·수정·삭제는 {@code isAuthenticated()}를 적는다</b> — 게시글과 같은 판단이다({@link PostController}). 수정은
 * 작성자 본인만, 삭제는 활성 관리자 또는 작성자 본인이 할 수 있다 (3-3 결정 23 D2).
 *
 * <p>댓글 id가 경로의 게시글에 속하지 않으면(다른 글의 댓글 id를 넣으면) 있어도 {@code NOT_FOUND}로 다룬다.
 */
@Tag(name = "자유 게시판 댓글", description = "게시글에 댓글을 남기고 읽는다. 게시글과 같은 부원·상태 제한을 받는다")
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
public class PostCommentController {

  private final PostCommentService postCommentService;

  public PostCommentController(PostCommentService postCommentService) {
    this.postCommentService = postCommentService;
  }

  @Operation(summary = "댓글 목록", description = "오래된 순으로 고정이다 — 대화 순서로 읽는다. 게시글 목록(최신순)과 반대다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public List<PostCommentResponse> list(@PathVariable Long postId) {
    return postCommentService.list(postId);
  }

  /**
   * 댓글 등록.
   *
   * <p><b>작성자는 인증 주체로만 정한다</b> (MUST). 본문으로 받으면 다른 사람 이름으로 댓글을 남길 수 있다.
   */
  @Operation(
      summary = "댓글 등록",
      description =
          """
          본문을 받는다. **작성자는 로그인한 사람이다** — 본문으로 받지 않는다.

          **본문은 평문으로 저장된다.** 게시글과 같은 이유다 (3-3 결정 16·23).
          """)
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 댓글이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 내용이 비었거나(공백뿐인 경우 포함) 상한(2,000자)을 넘었다",
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
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public PostCommentResponse write(
      @AuthenticationPrincipal Long authorId,
      @PathVariable Long postId,
      @Valid @RequestBody PostCommentRequest request) {
    return postCommentService.write(authorId, postId, request);
  }

  /**
   * 댓글 수정.
   *
   * <p><b>작성자 본인만</b> 할 수 있다. 관리자 역할도 남의 댓글을 수정하는 예외를 만들지 않는다 — 게시글 수정과 같은 판단이다.
   */
  @Operation(
      summary = "댓글 수정",
      description = "내용을 보낸 것으로 통째로 바꾼다. **작성자 본인만** 할 수 있다 — 관리자도 예외가 아니다.")
  @ApiResponse(responseCode = "200", description = "수정됨. 본문은 저장된 댓글이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 내용이 비었거나(공백뿐인 경우 포함) 상한(2,000자)을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — 본인이 쓴 댓글이 아니다 · CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글이거나 없는 댓글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{commentId}")
  @PreAuthorize("isAuthenticated()")
  public PostCommentResponse edit(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long postId,
      @PathVariable Long commentId,
      @Valid @RequestBody PostCommentRequest request) {
    return postCommentService.edit(requesterId, postId, commentId, request);
  }

  /**
   * 댓글 삭제.
   *
   * <p><b>활성 관리자 또는 작성자 본인</b>이 할 수 있다. <b>완전 삭제</b>이며 되돌릴 수 없다.
   */
  @Operation(
      summary = "댓글 삭제",
      description =
          """
          **활성 관리자 또는 작성자 본인**이 지울 수 있다. 게시글 삭제와 같은 판단이다
          (3-3 결정 20·23).

          **완전 삭제다.** 감추는 것이 아니라 행 자체가 사라지며, 되돌릴 수 없다.
          """)
  @ApiResponse(responseCode = "204", description = "삭제됨")
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — 활성 관리자가 아니고 본인이 쓴 댓글도 아니다 · CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글이거나 없는 댓글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void delete(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long postId,
      @PathVariable Long commentId) {
    postCommentService.delete(requesterId, postId, commentId);
  }
}
