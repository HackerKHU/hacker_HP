package org.hackerkhu.hackerhp.domain.photo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;

/**
 * 활동사진 리사이즈 (spec 2-1 §2-1-7 MUST) — 가로 최대 1920px, 비율 유지, JPEG 품질 85. 기준 미만 이미지는 원본을 그대로 둔다.
 *
 * <p>Spring 빈이 아니다 — 외부 상태(설정값·리소스)가 없는 순수 변환이라 굳이 주입받을 이유가 없다.
 */
final class PhotoResizer {

  private static final int MAX_WIDTH = 1920;
  private static final float JPEG_QUALITY = 0.85f;

  /** 그리드용 썸네일 폭. spec은 썸네일 저장 위치(photos/{id}/thumb/{uuid}.jpg)만 정하고 정확한 폭은 정하지 않았다 — 여기서 고른 값이다. */
  private static final int THUMBNAIL_WIDTH = 400;

  private PhotoResizer() {}

  /**
   * @param originalExtension 업로드 시점에 이미 검증된 확장자(jpg/jpeg/png) — {@code Photo#Extensions} 참고
   */
  static Resized resize(byte[] original, String originalExtension) {
    BufferedImage image = decode(original);

    if (image.getWidth() <= MAX_WIDTH) {
      return new Resized(original, originalExtension, contentTypeOf(originalExtension));
    }

    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(image)
          .width(MAX_WIDTH)
          .outputFormat("jpg")
          .outputQuality(JPEG_QUALITY)
          .toOutputStream(out);
      return new Resized(out.toByteArray(), "jpg", "image/jpeg");
    } catch (IOException e) {
      // 디코딩은 이미 성공했으므로(위 decode) 여기서 실패하는 것은 사용자 입력 문제가 아니다.
      throw new IllegalStateException("이미지 리사이즈에 실패했습니다.", e);
    }
  }

  /**
   * 그리드용 썸네일을 만든다 (spec 3-2 §3-2-5 4단계). 이미 {@code THUMBNAIL_WIDTH}보다 작으면 폭은 그대로 두고 업스케일하지 않는다 — 작은
   * 원본을 억지로 키우면 화질만 나빠지고 얻는 것이 없다.
   *
   * <p><b>항상 JPEG로 만든다.</b> 본 이미지({@code resize})는 기준(1920px) 미만이면 원본 포맷(PNG 등)을 그대로 두지만, 썸네일까지 그
   * 규칙을 따르면 "본 이미지는 PNG인데 썸네일만 리사이즈되어 JPEG"인 조합이 생긴다. 저장 키는 확장자 하나로 고정해 두므로(파일명 자체엔 포맷을 담지 않는다) 포맷이
   * 요청마다 달라지면 그 키의 실제 바이트와 어긋난다 — 항상 JPEG면 이 문제가 애초에 생기지 않는다.
   */
  static byte[] thumbnail(byte[] source) {
    BufferedImage image = decode(source);
    int targetWidth = Math.min(image.getWidth(), THUMBNAIL_WIDTH);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(image)
          .width(targetWidth)
          .outputFormat("jpg")
          .outputQuality(JPEG_QUALITY)
          .toOutputStream(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("썸네일 생성에 실패했습니다.", e);
    }
  }

  /**
   * 확장자만으로는 실제 바이트가 유효한 이미지인지 알 수 없다 — 여기서 확인한다. 실패하면 {@code UNSUPPORTED_FILE_TYPE}이다 (§3-2-7 표에 이미
   * 있는 코드를 재사용한다).
   */
  private static BufferedImage decode(byte[] original) {
    BufferedImage image;
    try {
      image = ImageIO.read(new ByteArrayInputStream(original));
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    if (image == null) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    return image;
  }

  private static String contentTypeOf(String extension) {
    return "png".equals(extension.toLowerCase(Locale.ROOT)) ? "image/png" : "image/jpeg";
  }

  record Resized(byte[] bytes, String extension, String contentType) {}
}
