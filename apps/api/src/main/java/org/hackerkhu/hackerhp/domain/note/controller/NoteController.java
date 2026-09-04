package org.hackerkhu.hackerhp.domain.note.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.note.dto.DownloadUrlResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteCreateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteFilterOptions;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSearch;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSort;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSummaryResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteUpdateRequest;
import org.hackerkhu.hackerhp.domain.note.dto.UploadUrlRequest;
import org.hackerkhu.hackerhp.domain.note.dto.UploadUrlResponse;
import org.hackerkhu.hackerhp.domain.note.service.BookmarkService;
import org.hackerkhu.hackerhp.domain.note.service.NoteCreateService;
import org.hackerkhu.hackerhp.domain.note.service.NoteDownloadService;
import org.hackerkhu.hackerhp.domain.note.service.NoteEditService;
import org.hackerkhu.hackerhp.domain.note.service.NoteLikeService;
import org.hackerkhu.hackerhp.domain.note.service.NoteQueryService;
import org.hackerkhu.hackerhp.domain.note.service.NoteUploadUrlService;
import org.hackerkhu.hackerhp.domain.note.service.NoteViewService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자료 목록·검색·필터·상세 (spec 2-1 §2-1-1, 3-2 §3-2-4).
 *
 * <p><b>{@code isAuthenticated()}만 적는다.</b> 매트릭스의 {@code ACTIVE} 조건은 {@code AccountStatusFilter}가
 * 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다 ({@code NoticeController}와 같은 관례).
 *
 * <p>자료 기능이 여기 다 있다 — 목록·상세·업로드·등록·수정·삭제·내려받기.
 */
@Tag(name = "자료", description = "쌓인 정리본을 찾는다. 조회는 ACTIVE 전용")
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

  private final NoteQueryService noteQueryService;
  private final BookmarkService bookmarkService;
  private final NoteLikeService noteLikeService;
  private final NoteUploadUrlService noteUploadUrlService;
  private final NoteCreateService noteCreateService;
  private final NoteDownloadService noteDownloadService;
  private final NoteEditService noteEditService;
  private final NoteViewService noteViewService;

  public NoteController(
      NoteQueryService noteQueryService,
      BookmarkService bookmarkService,
      NoteLikeService noteLikeService,
      NoteUploadUrlService noteUploadUrlService,
      NoteCreateService noteCreateService,
      NoteDownloadService noteDownloadService,
      NoteEditService noteEditService,
      NoteViewService noteViewService) {
    this.noteViewService = noteViewService;
    this.noteQueryService = noteQueryService;
    this.bookmarkService = bookmarkService;
    this.noteLikeService = noteLikeService;
    this.noteUploadUrlService = noteUploadUrlService;
    this.noteCreateService = noteCreateService;
    this.noteDownloadService = noteDownloadService;
    this.noteEditService = noteEditService;
  }

  /**
   * 업로드용 presigned URL 발급 — 흐름의 ① (spec 2-1 §2-1-2 MUST).
   *
   * <p><b>서버는 파일 바이트를 받지 않는다.</b> 브라우저가 받은 URL로 S3에 직접 올린다.
   */
  @Operation(
      summary = "업로드 URL 발급",
      description =
          """
          올릴 파일들의 이름과 크기를 주면 **파일마다 presigned PUT URL**을 돌려준다.
          브라우저는 각 `url`에 파일을 `PUT`으로 올리고, 끝나면 `key`를 모아
          `POST /notes`에 낸다 — **파일 바이트는 서버를 거치지 않는다.**

          **여기서 보는 크기는 브라우저가 말한 값이다.** 올리기 전에 알려 주는 것이 목적이고,
          실제 강제는 등록 단계가 S3에 올라온 오브젝트를 직접 재서 한다.

          **받은 `key`는 임시 자리다.** 등록하지 않으면 하루 뒤에 사라진다.
          """)
  @ApiResponse(responseCode = "200", description = "발급됨")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 파일이 없거나 개수 상한을 넘었다",
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
      description =
          "CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정 · `INACTIVE` — **이번 학기 비활동 부원**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`FILE_TOO_LARGE` — 파일 하나가 상한을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "`UNSUPPORTED_FILE_TYPE` — 허용되지 않는 확장자. **크기보다 먼저 본다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/upload-url")
  @PreAuthorize("isAuthenticated()")
  public UploadUrlResponse uploadUrl(
      @AuthenticationPrincipal Long uploaderId, @Valid @RequestBody UploadUrlRequest request) {
    return noteUploadUrlService.issue(uploaderId, request);
  }

  /**
   * 자료 등록 — 흐름의 ③ (spec 2-1 §2-1-2 MUST).
   *
   * <p><b>업로더는 인증 주체로만 정한다.</b> 본문으로 받으면 다른 사람 이름으로 올릴 수 있다.
   */
  @Operation(
      summary = "자료 등록",
      description =
          """
          메타데이터와 **업로드를 마친 파일 키 목록**을 받아 자료를 만든다.

          **여기가 용량의 진짜 방어선이다.** presigned PUT은 용량을 강제하지 못하므로,
          S3에 올라온 오브젝트를 직접 재서 상한을 넘으면 **지우고 거절한다.**

          **남이 올린 키는 등록할 수 없다** — 키에 업로더가 박혀 있어 대조한다.

          제목은 Java `String.trim()`과 같이 **양끝의 U+0000~U+0020 문자만 제거한 뒤
          코드포인트 기준 50자까지**다. NBSP(U+00A0)는 제목 문자로 보존한다.

          **업로더는 로그인한 사람이다.** 본문으로 받지 않는다.
          """)
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 자료다")
  @ApiResponse(
      responseCode = "400",
      description =
          "`VALIDATION_ERROR` — 필수값 누락, 정규화한 제목 50자 초과, `category`와 `examType`의 짝이 어긋남, **아직 올라오지 않은 파일**",
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
      description =
          "`FORBIDDEN` — **남이 올린 파일 키다** 또는 CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL` · `INACTIVE`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`FILE_TOO_LARGE` — **실제로 올라온** 파일이 상한을 넘었다. 그 오브젝트는 지워진다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description =
          "`UNSUPPORTED_FILE_TYPE` — **등록 요청의 `originalName`이 허용되지 않는 확장자다.** 발급 때 통과한 이름과 다른 이름을 붙이는 길을 막는다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public NoteDetailResponse create(
      @AuthenticationPrincipal Long uploaderId, @Valid @RequestBody NoteCreateRequest request) {
    return noteCreateService.create(uploaderId, request);
  }

  @Operation(
      summary = "자료 목록·검색·필터",
      description =
          """
          **검색어와 필터는 AND로 함께 걸린다.** `q`는 제목·과목명·교수명을 한 번에 찾는
          부분 일치이며 대소문자를 가리지 않는다.

          `sort`는 `latest`(기본)·`title`·`views`만 받는다. 그 밖의 값은 기본값으로 본다 —
          화면이 조합해 보내는 값이라 `400`으로 막지 않는다.

          `views`는 조회수 많은 순이다. **조회수는 같은 값이 흔해서**(새 자료는 전부 0)
          마지막 기준으로 `id`가 붙는다 — 없으면 페이지마다 배치가 달라진다.

          **목록을 여는 것은 조회수를 올리지 않는다** (#245). 세는 것은 상세뿐이다.

          `category=SUBJECT&examType=MIDTERM`처럼 있을 수 없는 조합은 **오류가 아니라
          결과 0건**이다. 필터를 조합하는 순간마다 `400`을 받게 하지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<NoteSummaryResponse> list(
      @AuthenticationPrincipal Long viewerId,
      @ParameterObject NoteSearch search,
      @RequestParam(required = false) String sort,
      @ParameterObject Pageable pageable) {
    return new PagedModel<>(noteQueryService.list(viewerId, search, NoteSort.from(sort), pageable));
  }

  @Operation(
      summary = "필터 옵션",
      description =
          """
          **실제 등록된 값에서 만든다.** 목록에 없는 과목을 고를 수 있으면 결과가 늘 0건이고,
          등록된 과목이 빠지면 찾을 방법이 사라진다.

          학기·시험 구분은 값이 고정이라 담지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping("/filters")
  @PreAuthorize("isAuthenticated()")
  public NoteFilterOptions filters() {
    return noteQueryService.filters();
  }

  @Operation(
      summary = "자료 상세",
      description =
          """
          목록과 달리 **딸린 파일 목록**을 함께 준다. 각 파일의 `id`로 다운로드 URL을 요청한다 (#55).

          **S3 키는 담지 않는다** — 버킷이 비공개라 키를 알아도 열 수 없다.

          **이 요청이 조회수를 1 올린다** (#245). 목록을 여는 것은 세지 않고, 같은 사람이
          다시 열면 다시 센다. 응답의 `viewCount`는 **이 조회를 반영한 값**이다.

          **세지 못해도 자료는 나온다.** 조회수 증가가 실패해도 `200`이고, 그때
          `viewCount`는 증가 전 값이다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료. **조회수는 오르지 않는다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public NoteDetailResponse get(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    return noteViewService.read(viewerId, id);
  }

  /**
   * 자료 수정 (spec 2-1 §2-1-3 MUST, 3-1 §3-1-7).
   *
   * <p><b>본인 것만. {@code ADMIN}은 전체.</b> 화면이 버튼을 숨기는 것과 별개로 서버가 소유자를 확인한다.
   */
  @Operation(
      summary = "자료 수정",
      description =
          """
          메타데이터와 **수정 뒤에 남을 첨부 전부**를 받는다.

          **보낸 것으로 통째로 바꾼다.** 경로는 `PATCH`지만 동작은 전체 교체다 — 부분 수정이면
          `professor`를 지우려는 의도와 건드리지 않는 의도를 구별할 수 없다.

          **`files`에 없는 기존 파일은 삭제된다.** 각 항목은 그대로 둘 기존 파일의 `fileId`이거나,
          새로 올린 파일의 `key`+`originalName`이다 — **둘 중 하나만** 채운다.

          **업로더는 바뀌지 않는다.** 관리자가 남의 자료를 고쳐도 그렇다.

          제목은 Java `String.trim()`과 같이 **양끝의 U+0000~U+0020 문자만 제거한 저장값**으로 센다.
          NBSP(U+00A0)는 제목 문자로 보존한다. 새로 쓰거나 바꾸는 제목은 **50자까지**다. 기존에 저장된 51~200자 제목은
          그대로 둔 채 다른 메타데이터·첨부만 고칠 수 있지만, 다른 50자 초과 제목으로는
          바꿀 수 없다.
          """)
  @ApiResponse(responseCode = "200", description = "수정됨. 본문은 갱신된 자료다")
  @ApiResponse(
      responseCode = "400",
      description =
          "`VALIDATION_ERROR` — 필수값 누락 · 정규화한 제목이 저장 상한 200자 초과 · 변경 제목이 50자 초과 · `category`와 `examType`의 짝이 어긋남 · **`fileId`와 `key`를 둘 다 보내거나 둘 다 비움** · 이 자료의 파일이 아닌 `fileId` · 아직 올라오지 않은 `key`",
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
      description =
          "`FORBIDDEN` — **남의 자료다** 또는 남이 올린 파일 키다 또는 CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL` · `INACTIVE`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`FILE_TOO_LARGE` — 새로 붙이는 파일이 상한을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "`UNSUPPORTED_FILE_TYPE` — 새로 붙이는 파일의 확장자가 허용되지 않는다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public NoteDetailResponse update(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long id,
      @Valid @RequestBody NoteUpdateRequest request) {
    return noteEditService.update(requesterId, id, request);
  }

  /**
   * 자료 삭제 (spec 2-1 §2-1-3 MUST).
   *
   * <p><b>첨부와 즐겨찾기는 DB가 함께 지운다</b> — {@code ON DELETE CASCADE}다.
   */
  @Operation(
      summary = "자료 삭제",
      description =
          """
          자료와 **딸린 첨부·즐겨찾기 레코드**를 함께 지운다. 그 보장은 DB 제약이 한다.

          **S3 오브젝트 정리는 응답의 조건이 아니다.** 정리에 실패해도 `204`다 — 사용자에게
          삭제는 이미 끝난 일이고, 여기서 실패로 답하면 재요청해도 자료가 없어 영원히 실패한다.
          실패한 키는 로그에 남는다.

          **본인 것만. `ADMIN`은 전체.**
          """)
  @ApiResponse(responseCode = "204", description = "삭제됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — **남의 자료다** 또는 CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL` · `INACTIVE`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void delete(@AuthenticationPrincipal Long requesterId, @PathVariable Long id) {
    noteEditService.delete(requesterId, id);
  }

  /**
   * 내려받기 URL 발급 (spec 2-1 §2-1-4 MUST).
   *
   * <p><b>영구적인 공개 URL은 존재하지 않는다.</b> 버킷은 완전 비공개이고, 파일에 닿는 길은 여기서 주는 짧은 수명의 서명된 주소뿐이다.
   */
  @Operation(
      summary = "내려받기 URL 발급",
      description =
          """
          그 파일을 받을 수 있는 **짧은 수명의 presigned GET URL**을 준다. 브라우저가 그 주소로
          S3에서 직접 받는다 — **파일 바이트는 서버를 거치지 않는다.**

          **저장될 이름이 URL에 서명돼 있다.** S3 키는 `uuid`라 그냥 받으면 알아볼 수 없는
          이름으로 저장되는데, 프론트의 `<a download="…">`로는 고칠 수 없다(다른 오리진
          링크에서 무시된다). 서명에 들어 있으므로 **받는 쪽이 바꿀 수도 없다.**

          **전송이 만료보다 오래 걸려도 끊기지 않는다.** S3는 요청이 시작될 때 서명을 본다.

          **`fileId`는 그 자료의 것이어야 한다.** 다른 자료의 파일 번호를 끼워 넣으면 `404`다.
          """)
  @ApiResponse(responseCode = "200", description = "발급됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정 · `INACTIVE` — **이번 학기 비활동 부원**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료이거나, **그 자료의 파일이 아니다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}/files/{fileId}")
  @PreAuthorize("isAuthenticated()")
  public DownloadUrlResponse downloadUrl(
      @AuthenticationPrincipal Long viewerId, @PathVariable Long id, @PathVariable Long fileId) {
    return noteDownloadService.issue(viewerId, id, fileId);
  }

  @Operation(
      summary = "즐겨찾기 추가",
      description =
          """
          **이미 담겨 있어도 성공이다.** 목록과 상세에서 각각 누르거나 두 번 누르는 일은 흔한데,
          그때 오류를 주면 화면은 사용자에게 아무 의미 없는 안내를 띄워야 한다.

          **토글이 아니다.** 같은 요청이 상태를 뒤집으면 재시도가 방금 담은 것을 조용히 뺀다 —
          화면은 응답의 `bookmarked`를 보고 담을지 뺄지 고른다.
          """)
  @ApiResponse(responseCode = "204", description = "담겼다 (이미 담겨 있던 경우 포함)")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{id}/bookmark")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void addBookmark(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    bookmarkService.add(viewerId, id);
  }

  @Operation(
      summary = "즐겨찾기 해제",
      description =
          """
          **담겨 있지 않아도 성공이다.** 없는 자료여도 `404`를 주지 않는다 — 자료가 지워지면
          즐겨찾기도 함께 사라지므로 뺄 것이 이미 없고, 오류를 주면 화면이 지울 수 없는 별표를
          들고 있게 된다.
          """)
  @ApiResponse(responseCode = "204", description = "빠졌다 (담겨 있지 않던 경우 포함)")
  @DeleteMapping("/{id}/bookmark")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void removeBookmark(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    bookmarkService.remove(viewerId, id);
  }

  /**
   * 좋아요 (#344). <b>즐겨찾기와 완전히 별개 자원이다</b> (3-3 결정 25 D1) — 즐겨찾기는 "다시 보려고 담아둔다", 좋아요는 "품질에 공감한다"로 뜻이
   * 다르다.
   */
  @Operation(
      summary = "자료 좋아요",
      description =
          """
          **이미 눌렀어도 성공이다.** 즐겨찾기와 같은 계약이다 — 두 번 눌러도 빠지지 않는다.

          **토글이 아니다.** 화면은 응답의 `likedByMe`를 보고 누를지 뗄지 고른다.
          """)
  @ApiResponse(responseCode = "204", description = "눌렸다 (이미 눌러져 있던 경우 포함)")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정 · `INACTIVE` — **이번 학기 비활동 부원**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{id}/like")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void like(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    noteLikeService.add(viewerId, id);
  }

  @Operation(
      summary = "자료 좋아요 취소",
      description = "**눌러져 있지 않아도 성공이다.** 없는 자료여도 `404`를 주지 않는다 — 즐겨찾기 해제와 같은 이유다.")
  @ApiResponse(responseCode = "204", description = "떼졌다 (눌러져 있지 않던 경우 포함)")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정 · `INACTIVE` — **이번 학기 비활동 부원**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}/like")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void unlike(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    noteLikeService.remove(viewerId, id);
  }
}
