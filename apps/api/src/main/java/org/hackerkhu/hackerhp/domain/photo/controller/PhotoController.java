package org.hackerkhu.hackerhp.domain.photo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterRequest;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoResponse;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoUploadUrlRequest;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoUploadUrlResponse;
import org.hackerkhu.hackerhp.domain.photo.service.PhotoService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 활동사진 조회·업로드·삭제 (#57, spec 3-2 §3-2-5).
 *
 * <p><b>조회는 {@code isAuthenticated()}, 쓰기는 {@code hasRole('ADMIN')}만 적는다.</b> {@code ACTIVE} 조건은
 * {@code AccountStatusFilter}가 인가보다 먼저 보장하므로 여기서 다시 적지 않는다 ({@code NoticeController}와 같은 관례).
 */
@Tag(name = "활동사진", description = "조회는 ACTIVE, 업로드·삭제는 ADMIN 전용. 원본은 presigned URL로 직접 올린다")
@RestController
@RequestMapping("/api/v1/photos")
public class PhotoController {

  private final PhotoService photoService;

  public PhotoController(PhotoService photoService) {
    this.photoService = photoService;
  }

  @Operation(
      summary = "원본 업로드용 presigned URL 발급",
      description =
          """
          올릴 사진 개수만큼 확장자(`jpg`/`jpeg`/`png`)를 담아 보내면, 파일마다 하나씩
          presigned PUT URL을 내려준다. 브라우저가 그 URL로 S3에 원본을 직접 올린 뒤,
          받은 `key` 목록을 `POST /photos`에 그대로 실어 보낸다.
          """)
  @ApiResponse(responseCode = "200", description = "발급됨")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 확장자가 비었거나 20개를 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "`UNSUPPORTED_FILE_TYPE` — jpg, jpeg, png가 아니다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/upload-url")
  @PreAuthorize("hasRole('ADMIN')")
  public List<PhotoUploadUrlResponse> issueUploadUrls(
      @Valid @RequestBody PhotoUploadUrlRequest request) {
    return photoService.issueUploadUrls(request.extensions());
  }

  @Operation(
      summary = "활동사진 등록",
      description =
          """
          `POST /photos/upload-url`로 올린 원본 키 목록을 등록한다. 서버가 각 원본을 읽어
          가로 최대 1920px·JPEG 품질 85로 리사이즈하고(기준 미만 이미지는 원본 유지),
          최종 위치에 저장한 뒤 사진마다 행을 만든다. 업로더는 요청 본문이 아니라 인증
          주체로 정한다.
          """)
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 사진 목록")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — key가 비었거나 올바르지 않다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 업로드된 원본을 찾을 수 없다 (아직 안 올라왔거나 이미 처리됨)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`FILE_TOO_LARGE` — 원본이 너무 크다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "415",
      description = "`UNSUPPORTED_FILE_TYPE` — 유효한 이미지가 아니다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public List<PhotoResponse> register(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody PhotoRegisterRequest request) {
    return photoService.register(userId, request);
  }

  @Operation(summary = "활동사진 목록 조회", description = "최신순 그리드. 앨범 그룹은 없다 — 각 이미지가 개별 레코드다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<PhotoResponse> list(@ParameterObject Pageable pageable) {
    return new PagedModel<>(photoService.list(pageable));
  }

  @Operation(summary = "활동사진 삭제")
  @ApiResponse(responseCode = "204", description = "삭제됨")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 사진",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
    photoService.delete(userId, id);
  }
}
