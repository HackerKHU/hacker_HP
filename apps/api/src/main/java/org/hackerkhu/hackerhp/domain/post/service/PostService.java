package org.hackerkhu.hackerhp.domain.post.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hackerkhu.hackerhp.domain.post.dto.PostAuthor;
import org.hackerkhu.hackerhp.domain.post.dto.PostCreateRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostDetailResponse;
import org.hackerkhu.hackerhp.domain.post.dto.PostSummaryResponse;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자유 게시판 (spec 2-1 §2-1-8, 3-2 §3-2-5, 3-3 결정 16·17).
 *
 * <p><b>본문을 건드리지 않는다.</b> 받은 문자열을 그대로 저장하고 그대로 내보낸다 — 마크다운으로 해석하지도, HTML을 정화하지도 않는다. 이스케이프는 화면이 텍스트
 * 노드로 그리면서 한다. <b>서버가 정화를 시작하면 그 규칙이 어디까지인지 아무도 모르게 된다.</b>
 */
@Service
public class PostService {

  private static final Logger log = LoggerFactory.getLogger(PostService.class);

  /**
   * <b>목록 정렬은 고정이다</b> (MUST, §3-2-5).
   *
   * <p>마지막 기준이 {@code id}인 것이 핵심이다 — 같은 시각에 올라온 글이 있으면 페이지를 넘길 때마다 배치가 달라져 <b>같은 글이 두 번 보이거나 아예
   * 빠진다.</b>
   */
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final PostRepository posts;
  private final UserRepository users;

  public PostService(PostRepository posts, UserRepository users) {
    this.posts = posts;
    this.users = users;
  }

  /**
   * 목록.
   *
   * <p><b>들어온 {@code Pageable}의 정렬을 버린다</b> (MUST). 그대로 흘리면 두 가지가 깨진다 — 없는 속성 이름 하나에 {@code 500}이
   * 나고(자료 목록이 {@code sort=bogus}로 겪었다, #52), 유효한 이름({@code sort=title})이면 <b>고정 정렬 계약이 조용히 깨진다.</b>
   * {@code page}·{@code size}는 {@code spring.data.web.pageable}이 상한까지 이미 처리했다.
   */
  @Transactional(readOnly = true)
  public Page<PostSummaryResponse> list(Pageable pageable) {
    Page<Post> page =
        posts.findAll(
            PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
    Map<Long, User> found = authors(page.getContent());
    return page.map(post -> PostSummaryResponse.of(post, authorOf(post, found)));
  }

  @Transactional(readOnly = true)
  public PostDetailResponse get(Long id) {
    Post post =
        posts
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    return PostDetailResponse.of(post, authorOf(post, authors(List.of(post))));
  }

  /**
   * 등록.
   *
   * <p><b>작성자는 인증 주체다</b> (MUST). 요청 본문에 무엇이 들어 있든 쓰지 않는다.
   */
  @Transactional
  public PostDetailResponse write(Long authorId, PostCreateRequest request) {
    /*
     * 저장 직전에 작성자를 잠그고 다시 본다 (3-1 §3-1-4 MUST, #257 리뷰).
     *
     * 인가는 세션 값으로 이루어지고 필터는 매 요청 users를 읽지 않는다(결정 12). 그래서
     * 요청이 필터를 지난 뒤 관리자가 이 사람을 정지시켜도, 세션의 id만 믿으면 그 글이
     * 그대로 커밋된다 — "정지는 즉시 차단"(§2-2-3 MUST)이 조용히 깨진다.
     *
     * 잠근 채 보므로 정지 트랜잭션과 자연히 줄이 선다.
     */
    User author = users.findByIdForUpdate(authorId).orElse(null);
    RequesterCheck.requireActive(author, authorId);

    Instant now = Instant.now();
    /*
     * 본문은 trim하지 않는다 (§3-2-5 MUST, #257 리뷰).
     *
     * "받은 문자열을 그대로 저장하고 그대로 내보낸다"고 해 놓고 앞뒤를 잘라내면 그 계약이
     * 첫 줄부터 깨진다 — 들여쓴 코드나 마지막 개행이 저장 시점에 이미 사라진다. 공백뿐인
     * 입력은 @NotBlank가 이미 막는다.
     *
     * 제목은 자른다. 계약의 "그대로"는 본문에 대한 것이고, 제목 앞뒤 공백은 목록에서 줄이
     * 어긋나 보이게 할 뿐 담긴 뜻이 없다.
     */
    Post saved = posts.save(Post.write(request.title().trim(), request.content(), authorId, now));

    /*
     * 본문을 남기지 않는다. 개인정보가 섞여 들어올 수 있고, 이 로그는 "누가 언제 썼나"를
     * 알기 위한 것이지 내용을 보기 위한 것이 아니다.
     *
     * 도배 제한은 이번 범위가 아니다 (#255). 그 판단을 나중에 근거를 갖고 하려면
     * 이 줄이 남아 있어야 한다 — 실제 분포를 추측이 아니라 로그에서 본다.
     */
    log.info("게시글 등록: postId={} authorId={}", saved.getId(), authorId);
    return PostDetailResponse.of(saved, authorOf(saved, authors(List.of(saved))));
  }

  /**
   * 삭제 (관리자 전용, #238).
   *
   * <p><b>완전 삭제다.</b> 감춤을 쓰지 않는다 — 감추면 "지웠는데 DB에 남아 있다"를 개인정보처리방침에 고지해야 하는데, 그 값을 하지 않는다 (공지 삭제와 같은
   * 판단).
   *
   * <p><b>이력을 남기지 않는다.</b> {@code admin_actions}는 회원에 대한 조작 테이블이라(#143) 글 id를 넣으면 두 종류의 대상이 한 컬럼에
   * 섞인다 — 자료 삭제(#54)도 같은 이유로 이력을 남기지 않는다.
   */
  @Transactional
  public void delete(Long requesterId, Long id) {
    requireActiveAdmin(requesterId);
    Post post =
        posts
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    posts.delete(post);
    log.info("게시글 삭제: postId={} adminId={}", id, requesterId);
  }

  /**
   * 요청자의 <b>현재</b> 권한을 행을 잠근 채 확인한다 (3-1 §3-1-4 MUST, {@code PhotoService}와 같은 이유).
   * {@code @PreAuthorize}는 세션에 담긴 값을 볼 뿐이라, 인가를 지난 뒤 다른 관리자가 이 계정을 강등·정지해도 그 요청은 여기까지 그대로 온다 — 되돌릴
   * 수 없는 삭제이므로 커밋 직전에 다시 본다.
   */
  private void requireActiveAdmin(Long requesterId) {
    User requester =
        users
            .findByIdForUpdate(requesterId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    if (requester.getRole() != Role.ADMIN || requester.getStatus() != Status.ACTIVE) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  /**
   * 작성자 이름을 <b>한 번에 모아 읽는다.</b> 행마다 읽으면 20건에 질의가 20번 붙는다.
   *
   * <p><b>계정이 사라진 글은 여기 없다.</b> 그래서 {@link PostAuthor#of}가 그 자리를 "탈퇴한 회원"으로 채운다 (2-2 §2-2-4).
   */
  private Map<Long, User> authors(List<Post> found) {
    Set<Long> ids =
        found.stream().map(Post::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(ids).stream()
        .collect(Collectors.toMap(User::getId, user -> user, (first, second) -> first));
  }

  private PostAuthor authorOf(Post post, Map<Long, User> found) {
    Long authorId = post.getAuthorId();
    return PostAuthor.of(authorId == null ? null : found.get(authorId));
  }
}
