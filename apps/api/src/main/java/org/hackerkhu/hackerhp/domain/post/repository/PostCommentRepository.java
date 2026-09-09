package org.hackerkhu.hackerhp.domain.post.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.entity.PostComment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

  /** 같은 댓글의 수정·삭제를 직렬화하고, 권한 판정에 쓰는 작성자 id를 최신 행에서 읽는다 — {@code PostRepository}와 같은 판단이다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from PostComment c where c.id = :id")
  Optional<PostComment> findByIdForUpdate(@Param("id") Long id);

  /** 한 게시글의 댓글 전부. 대화 순서로 읽으므로 오래된 것이 먼저다 — 게시글 목록(최신순)과 반대다. */
  List<PostComment> findByPostId(Long postId, Sort sort);

  /**
   * 그 페이지에 실린 게시글들의 댓글 수를 <b>한 번에</b> 센다 (#374, 3-3 결정 30).
   *
   * <p><b>셀 뿐 저장하지 않는다.</b> {@code posts}에 카운터 열을 두면 댓글 등록·삭제와 게시글 삭제 CASCADE마다 그 열을 따로 맞춰야 하고, 한 번
   * 어긋나면 되돌릴 근거가 없다 — 좋아요가 같은 갈림길에서 카운터를 버리고 행을 남긴 것과 같은 판단이다(결정 24 D1).
   *
   * <p>행마다 물으면 20건에 질의가 20번 붙으므로 페이지 전체를 한 번에 모은다 — 작성자·좋아요를 모아 읽는 것과 같은 이유(#52).
   *
   * <p>{@code Object[]}의 각 원소는 {@code [postId, count]}다. <b>댓글이 하나도 없는 게시글은 결과에 없으므로</b> 부르는 쪽이
   * {@code 0}으로 채운다 — 여기서 {@code 0}인 행까지 만들려면 바깥 조인이 필요한데, 없는 것을 없다고 읽는 편이 싸다.
   */
  @Query(
      "select c.postId, count(c) from PostComment c where c.postId in :postIds group by c.postId")
  List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);
}
