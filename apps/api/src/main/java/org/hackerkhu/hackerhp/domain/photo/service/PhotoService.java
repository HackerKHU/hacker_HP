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
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.storage.S3StorageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 활동사진 업로드·조회·삭제 (#57, spec 2-1 §2-1-7).
 *
 * <p>업로드는 세 단계다 — presigned URL 발급({@link #issueUploadUrls}) → 브라우저가 S3에 직접 원본을 올림(이 서비스가 관여하지 않음)
 * → 등록({@link #register}, 서버가 원본을 읽어 리사이즈 후 최종 위치로 옮기고 행을 만든다). 원본을 multipart로 그대로 받지 않는 이유는 Vercel
 * 프록시 본문 제한(4.5MB)이다 (1-BACKGROUND §1-5 #5).
 */
@Service
@Transactional(readOnly = true)
public class PhotoService {

  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

  /** 원본 크기 상한. 계약이 정한 값이 아니라 방어적 상한이다 — 자료(§2-1-2)의 파일당 20MB와 같은 자릿수로 맞췄다. */
  private static final long MAX_ORIGINAL_BYTES = 20L * 1024 * 1024;

  private static final String TEMP_PREFIX = "photos/uploads/";

  private final PhotoRepository photoRepository;
  private final UserRepository userRepository;
  private final S3StorageService storageService;

  public PhotoService(
      PhotoRepository photoRepository,
      UserRepository userRepository,
      S3StorageService storageService) {
    this.photoRepository = photoRepository;
    this.userRepository = userRepository;
    this.storageService = storageService;
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
   * 원본을 리사이즈해 최종 위치에 저장하고 행을 만든다.
   *
   * <p><b>행을 먼저 만들어 id를 확보한다.</b> 최종 키({@code photos/{photoId}/{uuid}.jpg})에 그 id가 들어가야 하는데, INSERT
   * 전에는 id가 없다. {@code stored_path}는 NOT NULL이라 임시로 원본 키를 넣어 두고, 리사이즈가 끝나면 {@link
   * Photo#assignStoredPath}로 실제 값으로 바꾼다.
   */
  @Transactional
  public List<PhotoResponse> register(Long uploaderId, PhotoRegisterRequest request) {
    User uploader = userRepository.getReferenceById(uploaderId);
    return request.photos().stream().map(item -> registerOne(uploader, item)).toList();
  }

  private PhotoResponse registerOne(User uploader, PhotoRegisterRequest.Item item) {
    String tempKey = item.key();
    // 이 서비스가 발급한 임시 키만 받는다. 그 밖의 키를 그대로 믿으면 다른 용도의 S3
    // 오브젝트를 읽고 지우게 될 수 있다 — ADMIN 전용 경로라도 실수 방지 차원에서 막는다.
    if (!tempKey.startsWith(TEMP_PREFIX)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "올바르지 않은 업로드 키입니다.");
    }
    String extension = extensionOf(tempKey);

    byte[] original;
    try {
      original = storageService.download(tempKey);
    } catch (NoSuchKeyException e) {
      // 발급받은 URL로 실제 업로드가 안 됐거나, 이미 등록·삭제되어 없는 키다.
      throw new BusinessException(ErrorCode.NOT_FOUND, "업로드된 원본을 찾을 수 없습니다.");
    }
    if (original.length > MAX_ORIGINAL_BYTES) {
      storageService.delete(tempKey);
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    }

    PhotoResizer.Resized resized = PhotoResizer.resize(original, extension);
    byte[] thumbnail = PhotoResizer.thumbnail(resized.bytes());

    Photo photo = Photo.upload(normalizeCaption(item.caption()), tempKey, uploader);
    photoRepository.saveAndFlush(photo);

    String uuid = UUID.randomUUID().toString();
    String finalKey = "photos/%d/%s.%s".formatted(photo.getId(), uuid, resized.extension());
    storageService.upload(finalKey, resized.bytes(), resized.contentType());
    storageService.upload(thumbnailKeyOf(finalKey), thumbnail, resized.contentType());
    storageService.delete(tempKey);
    photo.assignStoredPath(finalKey);

    return toResponse(photo);
  }

  /** 최신순 그리드 (spec 2-1 §2-1-7). */
  public Page<PhotoResponse> list(Pageable pageable) {
    Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"));
    return photoRepository
        .findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), newestFirst))
        .map(this::toResponse);
  }

  @Transactional
  public void delete(Long id) {
    Photo photo =
        photoRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    storageService.delete(photo.getStoredPath());
    storageService.delete(thumbnailKeyOf(photo.getStoredPath()));
    photoRepository.delete(photo);
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
   * {@code photos/{id}/{uuid}.jpg} → {@code photos/{id}/thumb/{uuid}.jpg} (spec 3-2 §3-2-2 저장 키
   * 형식).
   */
  private static String thumbnailKeyOf(String storedPath) {
    int lastSlash = storedPath.lastIndexOf('/');
    return storedPath.substring(0, lastSlash + 1) + "thumb/" + storedPath.substring(lastSlash + 1);
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
