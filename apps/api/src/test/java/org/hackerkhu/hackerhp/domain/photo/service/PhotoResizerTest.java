package org.hackerkhu.hackerhp.domain.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** spec 2-1 §2-1-7 MUST — 가로 최대 1920px, 비율 유지, JPEG 품질 85, 기준 미만은 폭만 유지(업스케일하지 않는다). */
class PhotoResizerTest {

  private static byte[] image(int width, int height, String format) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, format, out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int widthOf(byte[] bytes) throws IOException {
    return ImageIO.read(new ByteArrayInputStream(bytes)).getWidth();
  }

  private static String formatOf(byte[] bytes) throws IOException {
    var readers =
        ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
    return readers.next().getFormatName();
  }

  @Test
  void imageNarrowerThanMaxWidthKeepsWidthButReencodesToJpeg() throws IOException {
    byte[] original = image(800, 600, "png");

    PhotoResizer.Resized resized = PhotoResizer.resize(original);

    assertThat(widthOf(resized.bytes())).isEqualTo(800);
    assertThat(formatOf(resized.bytes())).isEqualToIgnoringCase("jpeg");
  }

  @Test
  void imageWiderThanMaxWidthIsResizedToJpeg() throws IOException {
    byte[] original = image(3840, 2160, "png");

    PhotoResizer.Resized resized = PhotoResizer.resize(original);

    assertThat(widthOf(resized.bytes())).isEqualTo(1920);
    assertThat(formatOf(resized.bytes())).isEqualToIgnoringCase("jpeg");
  }

  /* 정확히 경계값이면 리사이즈하지 않는다 — "가로 최대 1920px"는 1920을 포함한다. */
  @Test
  void imageExactlyAtMaxWidthIsNotResized() throws IOException {
    byte[] original = image(1920, 1080, "jpg");

    PhotoResizer.Resized resized = PhotoResizer.resize(original);

    assertThat(widthOf(resized.bytes())).isEqualTo(1920);
  }

  /**
   * 요청 키의 확장자(admin이 올릴 때 고른 값)는 여기 오지 않는다 — 실제 바이트만 보고 판단한다. JPEG 바이트를 {@code .png}로 이름만 바꿔
   * 올려도(스푸핑) 항상 JPEG로 다시 인코딩되므로 저장된 포맷과 Content-Type이 실제 바이트와 어긋나지 않는다.
   */
  @Test
  void outputIsAlwaysJpegRegardlessOfActualSourceFormat() throws IOException {
    byte[] png = image(800, 600, "png");

    PhotoResizer.Resized resized = PhotoResizer.resize(png);

    assertThat(formatOf(resized.bytes())).isEqualToIgnoringCase("jpeg");
  }

  @Test
  void undecodableBytesAreRejectedAsUnsupportedFileType() {
    byte[] garbage = "이건 이미지가 아니다".getBytes();

    assertThatThrownBy(() -> PhotoResizer.resize(garbage))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));
  }

  @Test
  void thumbnailDoesNotUpscaleImagesSmallerThanThumbnailWidth() throws IOException {
    byte[] small = image(200, 150, "png");

    byte[] thumbnail = PhotoResizer.thumbnail(small);

    // 폭은 그대로 두되(업스케일하지 않는다), 본 이미지 포맷과 무관하게 항상 JPEG로 다시 인코딩한다
    // — 원본과 바이트가 같을 수는 없다.
    assertThat(widthOf(thumbnail)).isEqualTo(200);
  }

  @Test
  void thumbnailShrinksLargeImages() throws IOException {
    byte[] large = image(1920, 1080, "jpg");

    byte[] thumbnail = PhotoResizer.thumbnail(large);

    assertThat(widthOf(thumbnail)).isLessThan(1920);
  }

  @Test
  void thumbnailIsAlwaysJpegRegardlessOfSourceFormat() throws IOException {
    byte[] png = image(3840, 2160, "png");

    byte[] thumbnail = PhotoResizer.thumbnail(png);

    assertThat(formatOf(thumbnail)).isEqualToIgnoringCase("jpeg");
  }
}
