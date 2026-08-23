package org.hackerkhu.hackerhp.domain.photo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;

/**
 * 활동사진 리사이즈 (spec 2-1 §2-1-7 MUST) — 가로 최대 1920px, 비율 유지, JPEG 품질 85. 기준 미만 이미지는 폭을 그대로 둔다(업스케일하지
 * 않는다).
 *
 * <p><b>본 이미지·썸네일 모두 항상 JPEG로 다시 인코딩한다.</b> 요청이 준 확장자(업로드-URL 발급 시점에 admin이 고른 값)를 신뢰하지 않는다 — 그
 * 확장자와 실제 바이트의 형식이 다르면(예: JPEG를 {@code .png}로 이름만 바꿔 올림) 원본 형식을 그대로 저장하는 방식은 실제와 다른
 * 포맷·Content-Type으로 저장돼 브라우저가 표시하지 못한다. 디코더가 읽어낸 실제 픽셀로 항상 같은 포맷을 다시 쓰면 이 문제가 원천에 사라지고, 저장 키를 항상
 * {@code .jpg}로 고정하는 계약(spec 3-2 §3-2-2)과도 맞는다.
 *
 * <p>Spring 빈이 아니다 — 외부 상태(설정값·리소스)가 없는 순수 변환이라 굳이 주입받을 이유가 없다.
 */
final class PhotoResizer {

  private static final int MAX_WIDTH = 1920;
  private static final float JPEG_QUALITY = 0.85f;

  /** 그리드용 썸네일 폭. spec은 썸네일 저장 위치(photos/{id}/thumb/{uuid}.jpg)만 정하고 정확한 폭은 정하지 않았다 — 여기서 고른 값이다. */
  private static final int THUMBNAIL_WIDTH = 400;

  private PhotoResizer() {}

  static Resized resize(byte[] original) {
    BufferedImage image = decode(original);
    int targetWidth = Math.min(image.getWidth(), MAX_WIDTH);
    return new Resized(reencode(image, targetWidth));
  }

  /** 그리드용 썸네일을 만든다 (spec 3-2 §3-2-5 4단계). 이미 {@code THUMBNAIL_WIDTH}보다 작으면 업스케일하지 않는다. */
  static byte[] thumbnail(byte[] source) {
    BufferedImage image = decode(source);
    int targetWidth = Math.min(image.getWidth(), THUMBNAIL_WIDTH);
    return reencode(image, targetWidth);
  }

  private static byte[] reencode(BufferedImage image, int targetWidth) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(image)
          .width(targetWidth)
          .outputFormat("jpg")
          .outputQuality(JPEG_QUALITY)
          .toOutputStream(out);
      return out.toByteArray();
    } catch (IOException e) {
      // 디코딩은 이미 성공했으므로(decode) 여기서 실패하는 것은 사용자 입력 문제가 아니다.
      throw new IllegalStateException("이미지 변환에 실패했습니다.", e);
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

  record Resized(byte[] bytes) {}
}
