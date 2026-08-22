package org.hackerkhu.hackerhp.domain.photo.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoRegisterRequest;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoResponse;
import org.hackerkhu.hackerhp.domain.photo.dto.PhotoUploadUrlResponse;
import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.UserApplicationService;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.S3StorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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

  private static final String TEMP_PREFIX = "photos/uploads/";

  private final PhotoRepository photoRepository;
  private final UserRepository userRepository;
  private final S3StorageService storageService;
  private final TransactionTemplate transactionTemplate;

  public PhotoService(
      PhotoRepository photoRepository,
      UserRepository userRepository,
      S3StorageService storageService,
      PlatformTransactionManager transactionManager) {
    this.photoRepository = photoRepository;
    this.userRepository = userRepository;
    this.storageService = storageService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** 원본을 임시 키({@code photos/uploads/{uuid}.{ext}})에 쓸 presigned PUT URL을 확장자 개수만큼 발급한다. */
  public List<PhotoUploadUrlResponse> issueUploadUrls(List<String> extensions) {
    return extensions.stream().map(this::issueUploadUrl).toList();
  }

  private PhotoUploadUrlResponse issueUploadUrl(String rawExtension) {
    String extension = normalizeExtension(rawExtension);
    String key = TEMP_PREFIX + UUID.randomUUID() + "." + extension;
    String url = storageService.presignPut(key, contentTypeOf(extension));
    return new PhotoUploadUrlResponse(key, url);
  }

  /**
   * 사진들을 등록한다. <b>항목마다 독립된 트랜잭션으로 처리한다</b> — 하나로 묶으면 뒤 항목의 실패(원본 없음, 손상된 이미지 등)로 예외가 나는 순간 <b>이미
   * 커밋됐어야 할 앞 항목의 DB 행까지 롤백된다.</b> 그런데 앞 항목이 만든 최종 S3 오브젝트·지운 임시 원본은 DB 롤백으로 되돌아가지 않으므로, "성공한 줄 알았던
   * 사진이 통째로 사라지고 고아 오브젝트만 남는" 상태가 된다.
   *
   * <p>한 항목이 실패하면 그 지점에서 요청 전체가 실패한다(부분 성공 응답을 만들지 않는다) — 이미 등록된 앞 항목들은 각자 트랜잭션이 끝나며 커밋됐으므로 안전하게
   * 남는다.
   */
  public List<PhotoResponse> register(Long uploaderId, PhotoRegisterRequest request) {
    return request.photos().stream().map(item -> registerOne(uploaderId, item)).toList();
  }

  private PhotoResponse registerOne(Long uploaderId, PhotoRegisterRequest.Item item) {
    String tempKey = item.key();
    // 이 서비스가 발급한 임시 키만 받는다. 그 밖의 키를 그대로 믿으면 다른 용도의 S3
    // 오브젝트를 읽고 지우게 될 수 있다 — ADMIN 전용 경로라도 실수 방지 차원에서 막는다.
    if (!tempKey.startsWith(TEMP_PREFIX)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "올바르지 않은 업로드 키입니다.");
    }
    String extension = extensionOf(tempKey);

    // 바이트를 전부 내려받기 전에 크기부터 본다 — presigned PUT은 용량을 강제하지 못하므로,
    // 확장자만 맞으면 수백 MB짜리도 올릴 수 있다. 그걸 그대로 메모리에 올리면 태스크가 OOM으로
    // 죽는다.
    long size;
    try {
      size = storageService.size(tempKey);
    } catch (NoSuchKeyException e) {
      // 발급받은 URL로 실제 업로드가 안 됐거나, 이미 등록·삭제되어 없는 키다.
      throw new BusinessException(ErrorCode.NOT_FOUND, "업로드된 원본을 찾을 수 없습니다.");
    }
    if (size > MAX_ORIGINAL_BYTES) {
      storageService.delete(tempKey);
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    }

    byte[] original = storageService.download(tempKey);
    PhotoResizer.Resized resized = PhotoResizer.resize(original, extension);
    // 썸네일은 본 이미지의 포맷과 무관하게 항상 JPEG다 — thumbnailKeyOf()가 확장자를
    // .jpg로 고정하므로 여기서도 그 확장자로만 업로드해야 저장 키와 실제 바이트가 맞는다.
    byte[] thumbnail = PhotoResizer.thumbnail(resized.bytes());
    String caption = normalizeCaption(item.caption());

    return transactionTemplate.execute(
        status -> {
          User uploader = userRepository.getReferenceById(uploaderId);
          Photo photo = Photo.upload(caption, tempKey, uploader);
          photoRepository.saveAndFlush(photo);

          String uuid = UUID.randomUUID().toString();
          String finalKey = "photos/%d/%s.%s".formatted(photo.getId(), uuid, resized.extension());
          storageService.upload(finalKey, resized.bytes(), resized.contentType());
          storageService.upload(thumbnailKeyOf(finalKey), thumbnail, "image/jpeg");
          storageService.delete(tempKey);
          photo.assignStoredPath(finalKey);

          return toResponse(photo);
        });
  }

  /** 최신순 그리드 (spec 2-1 §2-1-7). */
  @Transactional(readOnly = true)
  public Page<PhotoResponse> list(Pageable pageable) {
    Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"));
    return photoRepository
        .findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), newestFirst))
        .map(this::toResponse);
  }

  /**
   * <b>DB 삭제를 먼저 커밋하고, S3 정리는 그 뒤에 한다.</b> 반대 순서로 두면 S3를 지운 다음 DB 삭제나 커밋이 실패했을 때 <b>이미 사라진 오브젝트를 계속
   * 가리키는 행</b>이 남는다 — 목록 조회마다 깨진 이미지 URL을 계속 내려주게 된다. 이 순서면 최악의 경우도 "이미 지워진 행이 가리키던 S3 오브젝트가 고아로
   * 남는" 정도라 사용자에게 보이는 문제가 없다.
   *
   * <p><b>요청자의 현재 권한을 행을 잠근 채 다시 확인한다.</b> {@code @PreAuthorize}는 <b>세션에 담긴</b> 값을 본다 — S3 왕복이 끝나기
   * 전에 관리자가 그 계정을 강등·정지했다면, 세션은 여전히 ADMIN이라 판단하지만 실제 권한은 이미 사라진 뒤다 ({@link
   * UserApplicationService#submit}과 같은 이유로 {@code findByIdForUpdate}를 쓴다).
   */
  public void delete(Long requesterId, Long id) {
    String storedPath = transactionTemplate.execute(status -> deleteRow(requesterId, id));
    storageService.delete(storedPath);
    storageService.delete(thumbnailKeyOf(storedPath));
  }

  private String deleteRow(Long requesterId, Long id) {
    User requester =
        userRepository
            .findByIdForUpdate(requesterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    if (requester.getRole() != Role.ADMIN || requester.getStatus() != Status.ACTIVE) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    Photo photo =
        photoRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    String storedPath = photo.getStoredPath();
    photoRepository.delete(photo);
    return storedPath;
  }

  private PhotoResponse toResponse(Photo photo) {
    User uploader = photo.getUploader();
    String uploaderName = uploader == null ? "탈퇴한 회원" : uploader.getName();
    Long uploaderId = uploader == null ? null : uploader.getId();
    String url = storageService.presignGet(photo.getStoredPath());
    String thumbnailUrl = storageService.presignGet(thumbnailKeyOf(photo.getStoredPath()));
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
   * {@code photos/{id}/{uuid}.{ext}} → {@code photos/{id}/thumb/{uuid}.jpg} (spec 3-2 §3-2-2 저장 키
   * 형식). 썸네일은 항상 JPEG이므로({@link PhotoResizer#thumbnail}) 본 이미지의 확장자와 무관하게 {@code .jpg}로 고정한다.
   */
  private static String thumbnailKeyOf(String storedPath) {
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

  private static String extensionOf(String key) {
    return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
  }

  private static String contentTypeOf(String extension) {
    return "png".equals(extension) ? "image/png" : "image/jpeg";
  }

  private static String normalizeCaption(String caption) {
    return (caption == null || caption.isBlank()) ? null : caption.trim();
  }
}
