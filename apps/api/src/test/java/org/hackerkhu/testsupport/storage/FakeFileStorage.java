package org.hackerkhu.testsupport.storage;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.hackerkhu.hackerhp.global.storage.FileStorage;

/**
 * 자격증명 없이 도는 {@link FileStorage} (#53 D6).
 *
 * <p><b>presigned 서명을 흉내내지 않는다.</b> 서명은 SDK가 하는 일이고, 우리가 지켜야 할 것은 <b>발급 조건·크기 검증·정리 순서</b>다. 그래서
 * 여기서 재현하는 것은 "무엇이 올라와 있고 크기가 얼마인가"뿐이다.
 *
 * <p>실제 S3 연동은 배포 리허설(#48)의 수동 점검이 맡는다.
 */
public class FakeFileStorage implements FileStorage {

  /** 키 → 크기. 순서를 유지해 "무엇이 어떤 차례로 남았나"를 그대로 볼 수 있게 한다. */
  private final Map<String, Long> objects = new LinkedHashMap<>();

  /** 브라우저가 S3에 올린 셈 친다. */
  public void put(String key, long sizeBytes) {
    objects.put(key, sizeBytes);
  }

  public Set<String> keys() {
    return Set.copyOf(objects.keySet());
  }

  public boolean has(String key) {
    return objects.containsKey(key);
  }

  public void clear() {
    objects.clear();
  }

  @Override
  public URI presignPut(String key) {
    return URI.create("https://fake-bucket.s3.test/" + key + "?signed=1");
  }

  @Override
  public OptionalLong sizeOf(String key) {
    Long size = objects.get(key);
    return size == null ? OptionalLong.empty() : OptionalLong.of(size);
  }

  @Override
  public void copy(String fromKey, String toKey) {
    Long size = objects.get(fromKey);
    if (size == null) {
      throw new IllegalStateException("없는 키를 복사하려 했다: " + fromKey);
    }
    objects.put(toKey, size);
  }

  @Override
  public void delete(String key) {
    objects.remove(key);
  }
}
