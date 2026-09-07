package org.hackerkhu.hackerhp.domain.post.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  /** 같은 글의 수정·삭제를 직렬화하고, 권한 판정에 쓰는 작성자 id를 최신 행에서 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Post p where p.id = :id")
  Optional<Post> findByIdForUpdate(@Param("id") Long id);

  /**
   * <b>내가 쓴 글·내가 좋아요한 글 필터</b> (#353·#355, 3-3 결정 28).
   *
   * <p><b>네 조합을 질의 하나로 받는다.</b> 조합마다 메서드를 두면 넷이 되고, 나중에 필터가 하나 더 늘면 여덟이 된다 — 꺼진 필터는 {@code :mine =
   * false} 쪽에서 통째로 참이 되어 조건이 사라진다.
   *
   * <p><b>좋아요는 조인이 아니라 {@code EXISTS}로 본다.</b> 조인하면 좋아요 행 수만큼 글이 중복돼 페이지 크기가 어긋나고, {@code
   * distinct}로 덮으면 카운트 질의까지 함께 손봐야 한다 — 여기서 필요한 것은 "그 조합이 있나"뿐이다.
   *
   * <p>정렬은 부르는 쪽이 {@code Pageable}에 실어 준다 — 전체 목록과 <b>같은 고정 정렬</b>이어야 필터를 켜고 끌 때 순서가 흔들리지 않는다.
   *
   * <p>작성자가 나간 글({@code authorId = null})은 어떤 {@code viewerId}와도 같지 않아 {@code mine}에서 자연히 빠진다 — 인증
   * 주체는 언제나 실재하는 계정이다.
   */
  @Query(
      value =
          """
          select p from Post p
          where (:mine = false or p.authorId = :viewerId)
            and (:liked = false
                 or exists (select 1 from PostLike l
                            where l.postId = p.id and l.userId = :viewerId))
          """,
      countQuery =
          """
          select count(p) from Post p
          where (:mine = false or p.authorId = :viewerId)
            and (:liked = false
                 or exists (select 1 from PostLike l
                            where l.postId = p.id and l.userId = :viewerId))
          """)
  Page<Post> findFiltered(
      @Param("viewerId") Long viewerId,
      @Param("mine") boolean mine,
      @Param("liked") boolean liked,
      Pageable pageable);
}
