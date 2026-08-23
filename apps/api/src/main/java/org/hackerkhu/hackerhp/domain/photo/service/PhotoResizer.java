package org.hackerkhu.hackerhp.domain.photo.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
 * <p><b>바이트를 Thumbnailator에 직접 건넨다 — 미리 {@code BufferedImage}로 디코딩해 넘기지 않는다.</b> 스마트폰·카메라 JPEG는 픽셀을
 * 가로로 저장한 채 EXIF Orientation 태그로 회전을 지시하는 경우가 흔하다. {@code ImageIO.read()}로 먼저 디코딩하면 그 결과인 {@code
 * BufferedImage}에는 EXIF가 남지 않아, 이후 어떤 라이브러리를 쓰든 회전 정보를 잃는다. Thumbnailator는 스트림을 직접 받으면 EXIF
 * Orientation을 스스로 적용하므로({@code useExifOrientation}, 기본값 활성) 원본 바이트를 그대로 넘긴다.
 *
 * <p>Spring 빈이 아니다 — 외부 상태(설정값·리소스)가 없는 순수 변환이라 굳이 주입받을 이유가 없다.
 */
final class PhotoResizer {

  private static final int MAX_WIDTH = 1920;
  private static final float JPEG_QUALITY = 0.85f;

  /** 그리드용 썸네일 폭. spec은 썸네일 저장 위치(photos/{id}/thumb/{uuid}.jpg)만 정하고 정확한 폭은 정하지 않았다 — 여기서 고른 값이다. */
  private static final int THUMBNAIL_WIDTH = 400;

  /**
   * 디코딩 후 픽셀 수 상한. 20MB 바이트 상한({@code PhotoService#MAX_ORIGINAL_BYTES})은 <b>압축된 크기</b>만 막는다 — 단색으로
   * 채운 초대형 PNG처럼 파일은 작아도 압축 해제하면 수 GB가 필요한 이미지(디코딩 폭탄)를 막지 못한다. {@code ImageIO.read()}는 실제로 픽셀 버퍼를
   * 전부 할당하므로, 그 전에 헤더만 읽어 픽셀 수를 확인한다. 1억 픽셀은 요즘 스마트폰 카메라(수십 MP)보다 훨씬 넉넉하면서도, ARGB 기준 디코딩 버퍼를 약
   * 400MB로 묶어 둔다.
   */
  private static final long MAX_PIXELS = 100_000_000L;

  private PhotoResizer() {}

  static Resized resize(byte[] original) {
    int width = probeWidth(original);
    int targetWidth = Math.min(width, MAX_WIDTH);
    return new Resized(reencode(original, targetWidth));
  }

  /** 그리드용 썸네일을 만든다 (spec 3-2 §3-2-5 4단계). 이미 {@code THUMBNAIL_WIDTH}보다 작으면 업스케일하지 않는다. */
  static byte[] thumbnail(byte[] source) {
    int width = probeWidth(source);
    int targetWidth = Math.min(width, THUMBNAIL_WIDTH);
    return reencode(source, targetWidth);
  }

  private static byte[] reencode(byte[] source, int targetWidth) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(new ByteArrayInputStream(source))
          .width(targetWidth)
          .outputFormat("jpg")
          .outputQuality(JPEG_QUALITY)
          .toOutputStream(out);
      return out.toByteArray();
    } catch (IOException e) {
      // 폭 조사(probeWidth)를 이미 통과했으므로 여기서 실패하는 것은 사용자 입력 문제가 아니다.
      throw new IllegalStateException("이미지 변환에 실패했습니다.", e);
    }
  }

  /**
   * 헤더만 읽어 가로 폭을 얻는다 — 전체 픽셀을 디코딩하지 않는다({@link #MAX_PIXELS} 참고). 확장자만으로는 실제 바이트가 유효한 이미지인지 알 수 없다 —
   * 여기서 확인한다. 실패하면 {@code UNSUPPORTED_FILE_TYPE}이다 (§3-2-7 표에 이미 있는 코드를 재사용한다 — 해상도 상한 초과도 같은 코드로
   * 묶는다. 별도 사유를 두면 {@code PhotoService}의 항목별 실패 매핑에 새 갈래가 필요해지는데, 사용자 입장에서는 "이 파일을 쓸 수 없다"는 같은
   * 결론이다).
   */
  private static int probeWidth(byte[] bytes) {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (iis == null) {
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis);
        int width = reader.getWidth(0);
        long pixels = (long) width * reader.getHeight(0);
        if (pixels > MAX_PIXELS) {
          throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "이미지 해상도가 너무 큽니다.");
        }
        return width;
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
  }

  record Resized(byte[] bytes) {}
}
