package org.hackerkhu.hackerhp.domain.photo.repository;

import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

  /**
   * 목록에서 업로더를 함께 가져오고, <b>등록이 끝나지 않은 자리표시자 행은 뺀다.</b>
   *
   * <p>등록은 최종 키를 아직 모르는 채로 행부터 커밋한 뒤({@code storedPath}가 임시 키를 그대로 담은 상태) S3에 최종 이미지를 올린다 ({@code
   * PhotoService#registerOne}). 그 사이 이 메서드가 그 행을 돌려주면 본 이미지 URL이 아직 리사이즈되지 않은 원본을, 썸네일 URL은 존재하지도
   * 않는 오브젝트를 가리킨다. 두 번째 트랜잭션이 끝내 실패해도 이 조건 하나로 영원히 걸러진다 — 별도 상태 컬럼 없이 저장 경로 접두사로 "아직 완결되지 않았다"를
   * 판단한다.
   *
   * <p><b>to-one이라 페이지네이션과 함께 써도 안전하다.</b> 컬렉션을 fetch join하면 조인으로 늘어난 행 때문에 Hibernate가 페이징을 메모리에서
   * 처리하지만(그쪽은 위험하다), to-one 조인은 행 수를 늘리지 않아 {@code LIMIT}이 그대로 먹는다.
   */
  @EntityGraph(attributePaths = "uploader")
  @Query(
      value = "select p from Photo p where p.storedPath not like concat(:prefix, '%')",
      countQuery = "select count(p) from Photo p where p.storedPath not like concat(:prefix, '%')")
  Page<Photo> findCompleted(@Param("prefix") String prefix, Pageable pageable);
}
