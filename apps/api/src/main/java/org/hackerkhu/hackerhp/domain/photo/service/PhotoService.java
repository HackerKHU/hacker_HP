package org.hackerkhu.hackerhp.domain.photo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterRequest;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterResponse;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterResponse.Failure;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterResponse.Reason;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoResponse;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoUploadUrlResponse;
import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.user.dto.DisplayName;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.FileStorage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 활동사진 업로드·조회·삭제 (#57, spec 2-1 §2-1-7).
 *
 * <p>업로드는 세 단계다 — presigned URL 발급({@link #issueUploadUrls}) → 브라우저가 S3에 직접 원본을 올림(이 서비스가 관여하지 않음)
 * → 등록({@link #register}, 서버가 원본을 읽어 리사이즈 후 최종 위치로 옮기고 행을 만든다). 원본을 multipart로 그대로 받지 않는 이유는 Vercel
 * 프록시 본문 제한(4.5MB)이다 (1-BACKGROUND §1-5 #5).
 *
 * <p><b>이 클래스 자체에는 {@code @Transactional}을 붙이지 않는다.</b> S3 호출은 DB 트랜잭션이 되돌릴 수 없으므로, DB와 S3 양쪽에 걸친
 * 여러 단계를 하나의 트랜잭션으로 묶으면 한쪽만 살아남는 상태가 생긴다({@link #registerOne}·{@link #delete} 참고). 대신 {@link
 * #transactionTemplate}으로 DB 작업만 좁게 트랜잭션을 걸고, S3 호출은 그 트랜잭션이 커밋된 뒤(또는 커밋 전)에 명시적으로 순서를 정한다.
 */
@Service
public class PhotoService {

  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

  /** 원본 크기 상한. 계약이 정한 값이 아니라 방어적 상한이다 — 자료(§2-1-2)의 파일당 20MB와 같은 자릿수로 맞췄다. */
  private static final long MAX_ORIGINAL_BYTES = 20L * 1024 * 1024;

  /** 임시 원본이 놓이는 접두사. 최종 자리로 옮겨지지 않은 것은 이 접두사 아래 남는다 (#339의 고아 정리도 이 값으로 최종 위치를 가른다). */
  public static final String TEMP_PREFIX = "photos/uploads/";

  /** HeadObject·GetObject가 없는 키에 응답 본문 없이 상태 코드만으로 답할 때의 값. */
  private static final int S3_NOT_FOUND = 404;

  private final PhotoRepository photoRepository;
  private final UserRepository userRepository;
  private final FileStorage storage;
  private final TransactionTemplate transactionTemplate;

  public PhotoService(
      PhotoRepository photoRepository,
      UserRepository userRepository,
      FileStorage storage,
      PlatformTransactionManager transactionManager) {
    this.photoRepository = photoRepository;
    this.userRepository = userRepository;
    this.storage = storage;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** 원본을 임시 키({@code photos/uploads/{uuid}.{ext}})에 쓸 presigned PUT URL을 확장자 개수만큼 발급한다. */
  public List<PhotoUploadUrlResponse> issueUploadUrls(List<String> extensions) {
    return extensions.stream().map(this::issueUploadUrl).toList();
  }

  private PhotoUploadUrlResponse issueUploadUrl(String rawExtension) {
    String extension = normalizeExtension(rawExtension);
    String key = TEMP_PREFIX + UUID.randomUUID() + "." + extension;
    String url = storage.presignPut(key, contentTypeOf(extension)).toString();
    return new PhotoUploadUrlResponse(key, url);
  }

  /**
   * 사진들을 등록한다. <b>항목 하나의 실패가 나머지를 되돌리지 않는다</b> — 예외로 던지지 않고 사유와 함께 결과로 돌려주며 전체는 항상 {@code 200}이다
   * (apps/api/AGENTS.md, spec 3-2 §3-2-6 일괄 승인과 같은 원칙). 항목마다 독립된 트랜잭션으로 처리하므로 뒤 항목의 실패가 이미 커밋된 앞
   * 항목을 되돌리지도 않는다.
   *
   * <p>요청자 권한이 통째로 사라진 경우({@link #requireActiveAdmin})는 예외다 — 그건 개별 항목의 문제가 아니라 요청 자체가 더 이상 유효하지
   * 않다는 뜻이므로 {@link BusinessException}을 그대로 던져 나머지 항목의 처리를 멈춘다.
   */
  public PhotoRegisterResponse register(Long uploaderId, PhotoRegisterRequest request) {
    List<PhotoResponse> registered = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();

    for (PhotoRegisterRequest.Item item : request.photos()) {
      try {
        registered.add(registerOne(uploaderId, item));
      } catch (PhotoRegistrationException e) {
        failed.add(new Failure(item.key(), e.reason()));
      }
    }
    return new PhotoRegisterResponse(registered, failed);
  }

  /**
   * 사진 한 장을 등록한다. 되돌릴 수 없는 순서로 세 단계를 밟는다.
   *
   * <ol>
   *   <li>요청자 권한을 잠근 채 다시 확인하고({@link #requireActiveAdmin}), 자리표시자 경로(임시 키)로 행을 만들어 커밋한다 — 최종
   *       키({@code photos/{photoId}/{uuid}.jpg})에 이 행의 id가 들어가야 하는데, INSERT 전에는 id가 없다. 이 행은 아직
   *       "완결"되지 않았으므로 {@link PhotoRepository#findByStoredPathNotStartingWith}가 목록에서 뺀다.
   *   <li>그 id로 최종 키를 만들어 리사이즈본·썸네일을 올린 뒤, 요청자 권한을 <b>다시 한번</b> 확인하고 행에 최종 키를 반영해 커밋한다 — 1번 트랜잭션이
   *       끝나며 잠금은 이미 풀렸고 S3 업로드는 트랜잭션 밖이라, 그 사이 다른 관리자가 요청자를 정지·강등했을 수 있다. <b>이 중 무엇이든 실패하면 1번이 만든
   *       행을 지운다</b> — 임시 원본은 아직 그대로라 같은 키로 다시 등록을 시도할 수 있다. S3 업로드 실패는 항목 하나의 문제가 아니라 S3 자체의 문제이므로
   *       예외를 그대로 올려보낸다(사유로 변환하지 않는다).
   *   <li>행에 최종 키를 반영하는 트랜잭션이 <b>커밋된 뒤에만</b> 임시 원본을 지운다. 반대로 지운 뒤 이 커밋이 실패하면 원본도 행도 없는 상태가 되어 복구할 수
   *       없다.
   * </ol>
   */
  private PhotoResponse registerOne(Long uploaderId, PhotoRegisterRequest.Item item) {
    String tempKey = item.key();
    // 이 서비스가 발급한 임시 키만 받는다. 그 밖의 키를 그대로 믿으면 다른 용도의 S3
    // 오브젝트를 읽고 지우게 될 수 있다 — ADMIN 전용 경로라도 실수 방지 차원에서 막는다.
    if (!tempKey.startsWith(TEMP_PREFIX)) {
      throw new PhotoRegistrationException(Reason.VALIDATION_ERROR);
    }

    // 바이트를 전부 내려받기 전에 크기부터 본다 — presigned PUT은 용량을 강제하지 못하므로,
    // 확장자만 맞으면 수백 MB짜리도 올릴 수 있다. 그걸 그대로 메모리에 올리면 태스크가 OOM으로
    // 죽는다.
    Optional<FileStorage.StoredObject> described = storage.describe(tempKey);
    if (described.isEmpty()) {
      throw new PhotoRegistrationException(Reason.NOT_FOUND);
    }
    long size = described.get().sizeBytes();
    if (size > MAX_ORIGINAL_BYTES) {
      storage.delete(tempKey);
      throw new PhotoRegistrationException(Reason.FILE_TOO_LARGE);
    }

    byte[] original;
    try {
      original = storage.download(tempKey);
    } catch (S3Exception e) {
      // 크기 확인과 다운로드 사이에 같은 키를 겨냥한 다른 요청이 먼저 등록을 마쳐 지웠을 수 있다.
      throw missingOrRethrow(e);
    }

    byte[] resized;
    byte[] thumbnail;
    try {
      resized = PhotoResizer.resize(original).bytes();
      // 본 이미지·썸네일 모두 항상 JPEG로 다시 인코딩한다 — 요청 키의 확장자(admin이 upload-url을
      // 요청할 때 고른 값)를 그대로 믿지 않는다. 그 확장자와 실제 바이트의 형식이 다르면(예: JPEG를
      // .png로 이름만 바꿔 올림) 디코딩은 성공하되 저장은 실제와 다른 포맷·Content-Type으로 남아
      // 브라우저가 표시하지 못한다 — 디코더가 읽어낸 실제 픽셀로 항상 같은 포맷을 다시 써서 이 문제를
      // 원천에서 없앤다. 저장 키를 항상 {@code .jpg}로 고정하는 계약(spec 3-2 §3-2-2)과도 맞는다.
      thumbnail = PhotoResizer.thumbnail(resized);
    } catch (BusinessException e) {
      throw new PhotoRegistrationException(Reason.UNSUPPORTED_FILE_TYPE);
    }
    String caption = normalizeCaption(item.caption());

    Photo photo =
        transactionTemplate.execute(
            status -> {
              requireActiveAdmin(uploaderId);
              Photo created =
                  Photo.upload(caption, tempKey, userRepository.getReferenceById(uploaderId));
              photoRepository.saveAndFlush(created);
              return created;
            });

    String finalKey = "photos/%d/%s.jpg".formatted(photo.getId(), UUID.randomUUID());
    Long photoId = photo.getId();
    PhotoResponse response;
    try {
      storage.upload(finalKey, resized, "image/jpeg");
      storage.upload(thumbnailKeyOf(finalKey), thumbnail, "image/jpeg");
      /*
       * 최종 커밋 직전에도 요청자 권한을 다시 확인한다. 첫 트랜잭션이 끝나며 행 잠금은 이미
       * 풀렸고, S3 업로드는 트랜잭션 밖이라 그동안 다른 관리자가 요청자를 정지·강등했을 수
       * 있다 — 그 창을 없앨 수는 없지만(그러려면 잠금을 S3 왕복 내내 들고 있어야 하는데, 그건
       * 커넥션을 오래 쥐는 것과 같은 문제다), 되돌릴 수 없게 만드는 마지막 지점(행을 완결 상태로
       * 커밋하는 것)만큼은 다시 확인한 뒤에 하도록 좁혀 둔다.
       */
      response =
          transactionTemplate.execute(
              status -> {
                requireActiveAdmin(uploaderId);
                Photo managed = photoRepository.getReferenceById(photoId);
                managed.assignStoredPath(finalKey);
                return toResponse(managed);
              });
    } catch (RuntimeException e) {
      // 업로드 실패든 권한 재확인 실패든, 완결되지 못한 행은 남기지 않는다. 임시 원본은 그대로
      // 두므로(아직 안 지웠다) 같은 키로 다시 시도할 수 있다.
      transactionTemplate.executeWithoutResult(status -> photoRepository.deleteById(photoId));
      throw e;
    }
    storage.delete(tempKey);
    return response;
  }

  /** {@code S3Exception}이 "없는 키"를 뜻하면 항목 실패로 바꾸고, 그 밖의 이유면 그대로 올려보낸다. */
  private static PhotoRegistrationException missingOrRethrow(S3Exception e) {
    /*
     * HeadObject는 응답 본문이 없어 SDK가 오류 코드를 읽을 수 없다 — 그래서 키가 없을 때도
     * NoSuchKeyException이 아니라 상태 코드 404를 단 밋밋한 S3Exception이 올라올 수 있다
     * (같은 저장소의 S3FileStorage#describe가 같은 이유로 statusCode()를 본다). 그것까지
     * "없음"으로 보지 않으면, 업로드가 끝나기 전에 등록을 부른 흔한 실수가 500이 된다.
     */
    if (e.statusCode() == S3_NOT_FOUND) {
      return new PhotoRegistrationException(Reason.NOT_FOUND);
    }
    throw e;
  }

  /**
   * 최신순 그리드 (spec 2-1 §2-1-7). 등록이 끝나지 않은 행은 뺀다 — {@link
   * PhotoRepository#findByStoredPathNotStartingWith} 참고.
   */
  @Transactional(readOnly = true)
  public Page<PhotoResponse> list(Pageable pageable) {
    Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"));
    return photoRepository
        .findCompleted(
            TEMP_PREFIX,
            PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), newestFirst))
        .map(this::toResponse);
  }

  /**
   * <b>DB 삭제를 먼저 커밋하고, S3 정리는 그 뒤에 한다.</b> 반대 순서로 두면 S3를 지운 다음 DB 삭제나 커밋이 실패했을 때 <b>이미 사라진 오브젝트를 계속
   * 가리키는 행</b>이 남는다 — 목록 조회마다 깨진 이미지 URL을 계속 내려주게 된다. 이 순서면 최악의 경우도 "이미 지워진 행이 가리키던 S3 오브젝트가 고아로
   * 남는" 정도라 사용자에게 보이는 문제가 없다.
   */
  public void delete(Long requesterId, Long id) {
    String storedPath = transactionTemplate.execute(status -> deleteRow(requesterId, id));
    storage.delete(storedPath);
    storage.delete(thumbnailKeyOf(storedPath));
  }

  private String deleteRow(Long requesterId, Long id) {
    requireActiveAdmin(requesterId);
    Photo photo =
        photoRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    String storedPath = photo.getStoredPath();
    photoRepository.delete(photo);
    return storedPath;
  }

  /**
   * 요청자의 <b>현재</b> 권한을 행을 잠근 채 확인한다. {@code @PreAuthorize}는 <b>세션에 담긴</b> 값을 본다 — 등록·삭제가 S3와 왕복하는
   * 동안 다른 관리자가 이 계정을 강등·정지했다면, 세션은 여전히 ADMIN이라 판단하지만 실제 권한은 이미 사라진 뒤다. 되돌릴 수 없는 S3 쓰기를 시작하기 전에 반드시
   * 부른다 ({@link UserApplicationService#submit}과 같은 이유로 {@code findByIdForUpdate}를 쓴다).
   */
  private void requireActiveAdmin(Long requesterId) {
    User requester =
        userRepository
            .findByIdForUpdate(requesterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    if (requester.getRole() != Role.ADMIN || requester.getStatus() != Status.ACTIVE) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private PhotoResponse toResponse(Photo photo) {
    User uploader = photo.getUploader();
    /*
     * 표시 이름은 DisplayName 한 곳에서만 만든다 (3-2 §3-2-2 MUST, #301). 예전에는 여기서
     * 문구를 직접 적었는데, 그래서 다른 셋에 학번 뒷자리를 붙이면 갤러리만 어긋날 뻔했다 —
     * 같은 사람이 자료에서는 "권승원66", 갤러리에서는 "권승원"으로 나온다 (T-431).
     */
    String uploaderName = DisplayName.of(uploader);
    Long uploaderId = uploader == null ? null : uploader.getId();
    String url = storage.presignGet(photo.getStoredPath()).toString();
    String thumbnailUrl = storage.presignGet(thumbnailKeyOf(photo.getStoredPath())).toString();
    return new PhotoResponse(
        photo.getId(),
        photo.getCaption(),
        url,
        thumbnailUrl,
        uploaderId,
        uploaderName,
        photo.getCreatedAt());
  }

  /**
   * {@code photos/{id}/{uuid}.jpg} → {@code photos/{id}/thumb/{uuid}.jpg} (spec 3-2 §3-2-2 저장 키
   * 형식). 본 이미지·썸네일 모두 항상 JPEG이므로({@link PhotoResizer}) 확장자를 조사하지 않고 {@code .jpg}로 고정한다.
   *
   * <p><b>{@code public}인 이유</b> — #339의 고아 정리 작업도 같은 규칙으로 썸네일 키를 유도해야, DB에 남은 본 이미지 경로 하나로 본
   * 이미지·썸네일 둘 다를 "참조 중"으로 표시할 수 있다. 규칙을 두 곳에 따로 적으면 한쪽만 고쳐질 위험이 생긴다.
   */
  public static String thumbnailKeyOf(String storedPath) {
    int lastSlash = storedPath.lastIndexOf('/');
    String dir = storedPath.substring(0, lastSlash + 1);
    String filename = storedPath.substring(lastSlash + 1);
    String uuid = filename.substring(0, filename.lastIndexOf('.'));
    return dir + "thumb/" + uuid + ".jpg";
  }

  private static String normalizeExtension(String extension) {
    String normalized = extension.toLowerCase(Locale.ROOT);
    if (!ALLOWED_EXTENSIONS.contains(normalized)) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    return normalized;
  }

  private static String contentTypeOf(String extension) {
    return "png".equals(extension) ? "image/png" : "image/jpeg";
  }

  private static String normalizeCaption(String caption) {
    return (caption == null || caption.isBlank()) ? null : caption.trim();
  }

  /** {@link #registerOne} 안에서만 쓰는 항목별 실패 신호. 스택트레이스는 필요 없다 — 흐름 제어일 뿐 버그가 아니다. */
  private static final class PhotoRegistrationException extends RuntimeException {

    private final Reason reason;

    PhotoRegistrationException(Reason reason) {
      super(null, null, false, false);
      this.reason = reason;
    }

    Reason reason() {
      return reason;
    }
  }
}
