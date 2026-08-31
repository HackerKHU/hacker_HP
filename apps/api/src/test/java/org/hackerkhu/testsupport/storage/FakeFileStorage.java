package org.hackerkhu.testsupport.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.hackerkhu.hackerhp.global.storage.FileStorage;

/**
 * 자격증명 없이 도는 {@link FileStorage} (#53 D6).
 *
 * <p><b>presigned 서명을 흉내내지 않는다.</b> 서명은 SDK가 하는 일이고, 우리가 지켜야 할 것은 <b>발급 조건·크기 검증·정리 순서</b>다. 그래서
 * 여기서 재현하는 것은 "무엇이 올라와 있고, 크기가 얼마이고, 그 사이에 바뀌었는가"뿐이다.
 *
 * <p><b>{@code etag}는 올릴 때마다 새로 준다.</b> 같은 키에 다시 올리면 값이 달라지므로, "잰 뒤에 갈아치웠다"가 그대로 드러난다 (#207 리뷰).
 *
 * <p>실제 S3 연동은 배포 리허설(#48)의 수동 점검이 맡는다.
 */
public class FakeFileStorage implements FileStorage {

  /** 키 → 올라온 것. 순서를 유지해 "무엇이 어떤 차례로 남았나"를 그대로 볼 수 있게 한다. */
  private final Map<String, StoredObject> objects = new LinkedHashMap<>();

  private final AtomicLong etags = new AtomicLong();

  /**
   * 키 → 마지막 내려받기 발급이 담은 {@code Content-Disposition}. inline 발급({@link #presignGet(String)})은 {@code
   * "inline"}을 담는다.
   */
  private final Map<String, String> dispositions = new LinkedHashMap<>();

  /** 키 → {@link #upload}로 실제로 쓴 바이트. {@link #put}은 브라우저가 올린 셈만 치므로 여기 들어오지 않는다 (#213 통합). */
  private final Map<String, byte[]> bytes = new LinkedHashMap<>();

  private Duration presignDelay = Duration.ZERO;
  private Instant presignedAt;

  /** 삭제가 터지는 상황을 만든다. 정리 실패를 삼키는지 보려면 필요하다. */
  private boolean deleteFails;

  /**
   * <b>재고 나서 옮기는 사이</b>에 내용이 갈리는 상황 (#207 리뷰).
   *
   * <p>발급한 presigned URL은 만료까지 살아 있어 그 틈에 다시 올릴 수 있다. 미리 갈아 두면 등록이 갈린 뒤의 값을 재게 되어 <b>이 사례가 재현되지
   * 않는다</b> — 갈리는 시점이 {@code describe} 다음이어야 한다.
   */
  private final Map<String, Long> swapAfterDescribe = new LinkedHashMap<>();

  /** 브라우저가 S3에 올린 셈 친다. 같은 키에 다시 올리면 {@code etag}가 달라진다. */
  public void put(String key, long sizeBytes) {
    objects.put(key, new StoredObject(sizeBytes, "etag-" + etags.incrementAndGet()));
  }

  public Set<String> keys() {
    return Set.copyOf(objects.keySet());
  }

  public boolean has(String key) {
    return objects.containsKey(key);
  }

  public long sizeOf(String key) {
    return objects.get(key).sizeBytes();
  }

  public void failDeletes(boolean fails) {
    this.deleteFails = fails;
  }

  /** 다음 {@code describe} 직후에 그 키의 내용을 갈아치운다. */
  public void swapAfterDescribe(String key, long newSizeBytes) {
    swapAfterDescribe.put(key, newSizeBytes);
  }

  public void clear() {
    objects.clear();
    dispositions.clear();
    bytes.clear();
    presignDelay = Duration.ZERO;
    presignedAt = null;
    swapAfterDescribe.clear();
    deleteFails = false;
  }

  @Override
  public URI presignPut(String key, String contentType) {
    return URI.create("https://fake-bucket.s3.test/" + key + "?signed=1");
  }

  /**
   * 발급된 내려받기 URL.
   *
   * <p>서명을 흉내내지 않는 대신 <b>무엇을 담으라고 했는지 따로 적어 둔다</b> ({@link #dispositionOf}). 확인해야 할 것은 서명 자체가 아니라
   * 담긴 값이고, 그것을 URL 문자열에서 다시 파내면 인코딩 규칙에 얽매인 검사가 된다 — 실제 형식은 {@code S3FileStorageTest}가 본다.
   */
  @Override
  public URI presignGet(String key, String originalName) {
    // 서명 시각을 먼저 잡고 나서 시간을 태운다 — S3의 만료도 서명이 시작된 시점부터 센다.
    presignedAt = Instant.now();
    sleepQuietly(presignDelay);
    dispositions.put(key, "attachment; filename*=UTF-8''" + originalName);
    return URI.create("https://fake-bucket.s3.test/" + key + "?signed=1&download=1");
  }

  /** 활동사진(#57)의 {@code <img src>}가 쓰는 inline 발급 (#213 통합). {@code attachment}를 담지 않는다. */
  @Override
  public URI presignGet(String key) {
    presignedAt = Instant.now();
    sleepQuietly(presignDelay);
    dispositions.put(key, "inline");
    return URI.create("https://fake-bucket.s3.test/" + key + "?signed=1");
  }

  /**
   * <b>서명이 오래 걸리는 상황</b>을 만든다 (#208 리뷰).
   *
   * <p>만료 기준 시각을 서명 앞에 잡았는지 뒤에 잡았는지는 <b>둘 사이에 시간이 흘러야</b> 갈린다. 실제로는 GC 정지나 네트워크 지연이 그 자리에 온다.
   */
  public void delayPresign(Duration delay) {
    this.presignDelay = delay;
  }

  /** 마지막 서명이 만들어진 순간. S3의 만료는 이 시점부터 센다. */
  public Instant presignedAt() {
    return presignedAt;
  }

  private static void sleepQuietly(Duration delay) {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 그 키의 마지막 발급이 무엇을 담으라고 했는가. */
  public String dispositionOf(String key) {
    return dispositions.get(key);
  }

  @Override
  public Optional<StoredObject> describe(String key) {
    Optional<StoredObject> found = Optional.ofNullable(objects.get(key));
    Long swapped = swapAfterDescribe.remove(key);
    if (swapped != null) {
      put(key, swapped);
    }
    return found;
  }

  @Override
  public boolean copyIfUnchanged(String fromKey, String toKey, String expectedEtag) {
    StoredObject source = objects.get(fromKey);
    if (source == null || !source.etag().equals(expectedEtag)) {
      return false;
    }
    objects.put(toKey, source);
    return true;
  }

  @Override
  public void delete(String key) {
    if (deleteFails) {
      throw new IllegalStateException("삭제 실패를 흉내낸다: " + key);
    }
    objects.remove(key);
    bytes.remove(key);
  }

  /**
   * 활동사진(#57)의 리사이즈가 원본을 읽을 때 쓴다 (#213 통합). {@link #put}으로 "올라온 셈" 친 키는 실제 바이트가 없으므로 여기서 못 받는다 —
   * {@link #upload}로 실제로 쓴 것만 돌려받을 수 있다.
   */
  @Override
  public byte[] download(String key) {
    byte[] found = bytes.get(key);
    if (found == null) {
      throw new IllegalStateException("올라오지 않은 키를 내려받으려 했다: " + key);
    }
    return found;
  }

  /**
   * 활동사진(#57)의 리사이즈 결과·썸네일을 최종 키에 쓸 때 쓴다 (#213 통합). {@link #describe}로 잴 수 있도록 {@code objects}에도
   * 반영한다.
   */
  @Override
  public void upload(String key, byte[] content, String contentType) {
    bytes.put(key, content);
    objects.put(key, new StoredObject(content.length, "etag-" + etags.incrementAndGet()));
  }
}
