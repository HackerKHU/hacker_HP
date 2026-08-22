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

/** spec 2-1 §2-1-7 MUST — 가로 최대 1920px, 비율 유지, JPEG 품질 85, 기준 미만은 원본 유지. */
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

  @Test
  void imageNarrowerThanMaxWidthKeepsOriginalBytes() {
    byte[] original = image(800, 600, "png");

    PhotoResizer.Resized resized = PhotoResizer.resize(original, "png");

    assertThat(resized.bytes()).isEqualTo(original);
    assertThat(resized.extension()).isEqualTo("png");
    assertThat(resized.contentType()).isEqualTo("image/png");
  }

  @Test
  void imageWiderThanMaxWidthIsResizedToJpeg() throws IOException {
    byte[] original = image(3840, 2160, "png");

    PhotoResizer.Resized resized = PhotoResizer.resize(original, "png");

    assertThat(widthOf(resized.bytes())).isEqualTo(1920);
    assertThat(resized.extension()).isEqualTo("jpg");
    assertThat(resized.contentType()).isEqualTo("image/jpeg");
  }

  /* 정확히 경계값이면 리사이즈하지 않는다 — "가로 최대 1920px"는 1920을 포함한다. */
  @Test
  void imageExactlyAtMaxWidthIsNotResized() {
    byte[] original = image(1920, 1080, "jpg");

    PhotoResizer.Resized resized = PhotoResizer.resize(original, "jpg");

    assertThat(resized.bytes()).isEqualTo(original);
  }

  @Test
  void undecodableBytesAreRejectedAsUnsupportedFileType() {
    byte[] garbage = "이건 이미지가 아니다".getBytes();

    assertThatThrownBy(() -> PhotoResizer.resize(garbage, "jpg"))
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

    var readers =
        ImageIO.getImageReaders(
            ImageIO.createImageInputStream(new ByteArrayInputStream(thumbnail)));
    assertThat(readers.next().getFormatName()).isEqualToIgnoringCase("jpeg");
  }
}
