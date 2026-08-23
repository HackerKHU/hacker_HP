package org.hackerkhu.hackerhp.domain.photo.repository;

import org.hackerkhu.hackerhp.domain.photo.entity.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

  /**
   * 목록에서 업로더를 함께 가져온다. 안 그러면 각 응답을 만들 때({@code uploader.getName()}) 페이지 크기만큼 추가 조회가 붙는다 (N+1,
   * {@code NoticeRepository.findAll(Pageable)}과 같은 이유).
   *
   * <p><b>to-one이라 페이지네이션과 함께 써도 안전하다.</b> 컬렉션을 fetch join하면 조인으로 늘어난 행 때문에 Hibernate가 페이징을 메모리에서
   * 처리하지만(그쪽은 위험하다), to-one 조인은 행 수를 늘리지 않아 {@code LIMIT}이 그대로 먹는다.
   */
  @Override
  @EntityGraph(attributePaths = "uploader")
  Page<Photo> findAll(Pageable pageable);
}
