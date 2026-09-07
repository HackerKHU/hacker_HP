package org.hackerkhu.hackerhp.domain.post.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hackerkhu.hackerhp.domain.post.dto.PostAuthor;
import org.hackerkhu.hackerhp.domain.post.dto.PostCreateRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostDetailResponse;
import org.hackerkhu.hackerhp.domain.post.dto.PostSummaryResponse;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostLikeRepository;
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
 * 자유 게시판 (spec 2-1 §2-1-8, 3-2 §3-2-5, 3-3 결정 16·17·18).
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
  private final PostLikeRepository likes;

  public PostService(PostRepository posts, UserRepository users, PostLikeRepository likes) {
    this.posts = posts;
    this.users = users;
    this.likes = likes;
  }

  /**
   * 목록.
   *
   * <p><b>들어온 {@code Pageable}의 정렬을 버린다</b> (MUST). 그대로 흘리면 두 가지가 깨진다 — 없는 속성 이름 하나에 {@code 500}이
   * 나고(자료 목록이 {@code sort=bogus}로 겪었다, #52), 유효한 이름({@code sort=title})이면 <b>고정 정렬 계약이 조용히 깨진다.</b>
   * {@code page}·{@code size}는 {@code spring.data.web.pageable}이 상한까지 이미 처리했다.
   *
   * <p><b>페이지와 좋아요 요약은 서로 다른 스냅샷에서 읽는다</b> (의도한 것이다). 트랜잭션은 하나지만 격리 수준이 {@code READ COMMITTED}라
   * 문장마다 스냅샷이 새로 잡힌다 — {@code liked=true}로 조회하는 도중에 본인이 다른 탭에서 좋아요를 떼면, 그 글이 목록에는 남은 채 {@code
   * likedByMe=false}·{@code likeCount=0}으로 그려질 수 있다. <b>고치지 않는다:</b> 두 문장을 한 스냅샷으로 묶으려면 목록 요청마다 격리
   * 수준을 올리는 왕복이 한 번 더 붙는데, 되돌아오는 것은 <b>본인이 방금 한 취소가 한 번 늦게 반영되는</b> 화면 하나다. 새로고침이 곧바로 바로잡고, 남에게 잘못된
   * 상태를 보여주지도 않는다. 한 문장으로 합치는 것은 네 도메인의 응답 조립을 모두 프로젝션으로 바꾸는 일이라 값이 더 크다.
   */
  @Transactional(readOnly = true)
  public Page<PostSummaryResponse> list(
      Pageable pageable, Long viewerId, boolean mine, boolean liked) {
    PageRequest request =
        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
    /*
     * 내가 쓴 글·내가 좋아요한 글 (#353·#355, 3-3 결정 28). 정렬은 어느 조합이든 같은
     * NEWEST_FIRST를 쓴다 — 필터를 켜고 끌 때 순서 규칙까지 달라지면 화면이 같은 목록을
     * 두 벌로 다뤄야 한다.
     *
     * 기준은 viewerId다. 작성자 id를 요청으로 받지 않는 이유는 자료 목록과 같다 — 받으면
     * 남의 글을 "내 글" 목록으로 조회할 수 있다.
     */
    Page<Post> page = posts.findFiltered(viewerId, mine, liked, request);
    Map<Long, User> found = AuthorLookup.of(page.getContent(), Post::getAuthorId, users);
    Map<Long, PostLikeSummary> likeSummaries =
        likeSummariesOf(viewerId, page.getContent().stream().map(Post::getId).toList());
    return page.map(
        post -> {
          PostLikeSummary like = likeSummaries.getOrDefault(post.getId(), PostLikeSummary.NONE);
          return PostSummaryResponse.of(
              post,
              AuthorLookup.authorOf(post.getAuthorId(), found),
              like.count(),
              like.likedByMe());
        });
  }

  @Transactional(readOnly = true)
  public PostDetailResponse get(Long id, Long viewerId) {
    Post post =
        posts
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    Map<Long, User> found = AuthorLookup.of(List.of(post), Post::getAuthorId, users);
    return withLikeInfo(post, AuthorLookup.authorOf(post.getAuthorId(), found), viewerId);
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
    // author는 방금 잠근 그 행이다 — 다시 조회하지 않고 그대로 쓴다. 방금 만든 글이라 좋아요는 아직 없다.
    return PostDetailResponse.of(saved, PostAuthor.of(author), 0L, false);
  }

  /**
   * 수정 (#256). <b>작성자 본인만</b> 할 수 있다 — 관리자 역할도 예외를 만들지 않는다(D1, 결정 21). 보낸 것으로 통째로 바꾼다(D2, 자료 수정
   * #54와 같은 판단). 수정 기한은 두지 않는다(D4) — 오타는 나중에 발견된다.
   */
  @Transactional
  public PostDetailResponse edit(Long requesterId, Long id, PostCreateRequest request) {
    /*
     * 저장 직전에 요청자를 잠그고 다시 본다 (3-1 §3-1-7 MUST, write()와 같은 이유).
     * 인가를 지난 뒤 정지되면 그 요청은 여기서 끊겨야 한다.
     */
    User requester = users.findByIdForUpdate(requesterId).orElse(null);
    RequesterCheck.requireActive(requester, requesterId);

    /* 삭제와 같은 계정 → 게시글 순서로 잠가 수정·삭제 경쟁을 한 줄로 세운다. */
    Post post =
        posts
            .findByIdForUpdate(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    if (!requesterId.equals(post.getAuthorId())) {
      /*
       * 작성자 본인만 고칠 수 있다 — 관리자도 예외가 아니다 (#256 D1). 작성자가 나간
       * 글(authorId == null)은 아무도 고칠 수 없다 — "본인"이 성립하지 않는다.
       */
      log.info("남의 글을 고치려 했다: requesterId={} postId={}", requesterId, id);
      throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 쓴 글만 수정할 수 있습니다.");
    }

    // 제목은 자르고 본문은 그대로 둔다 — write()와 같은 규칙이다 (§3-2-5).
    post.edit(request.title().trim(), request.content(), Instant.now());
    log.info("게시글 수정: postId={} authorId={}", id, requesterId);
    // requester는 방금 소유자로 확인한 그 행이다 — 다시 조회하지 않고 그대로 쓴다.
    return withLikeInfo(post, PostAuthor.of(requester), requesterId);
  }

  /**
   * 삭제 (관리자 또는 작성자 본인, #238·#278).
   *
   * <p><b>완전 삭제다.</b> 감춤을 쓰지 않는다 — 감추면 "지웠는데 DB에 남아 있다"를 개인정보처리방침에 고지해야 하는데, 그 값을 하지 않는다 (공지 삭제와 같은
   * 판단).
   *
   * <p><b>이력을 남기지 않는다.</b> {@code admin_actions}는 회원에 대한 조작 테이블이라(#143) 글 id를 넣으면 두 종류의 대상이 한 컬럼에
   * 섞인다 — 자료 삭제(#54)도 같은 이유로 이력을 남기지 않는다.
   */
  @Transactional
  public void delete(Long requesterId, Long id) {
    /*
     * 잠금 순서는 계정 → 게시글로 고정한다. 세션 인가 뒤 권한 회수·정지·탈퇴가 끝났거나
     * 작성자 관계가 ON DELETE SET NULL로 끊긴 요청이 옛 값으로 삭제를 커밋하면 안 된다.
     */
    User requester = users.findByIdForUpdate(requesterId).orElse(null);
    RequesterCheck.requireActive(requester, requesterId);
    Post post =
        posts
            .findByIdForUpdate(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    requireOwnerOrActiveAdmin(requester, post, requesterId);
    posts.delete(post);
    log.info("게시글 삭제: postId={} requesterId={}", id, requesterId);
  }

  /**
   * <b>활성 관리자 또는 작성자 본인</b>이어야 한다. 비활동 부원은 자료 외 기능을 그대로 쓰므로 자기 글을 지울 수 있다.
   *
   * <p>탈퇴·제거된 작성자의 {@code authorId}는 {@code null}이라 본인 관계가 성립하지 않는다. 요청자와 게시글을 모두 잠근 뒤 판정하므로 인가 뒤
   * 권한·상태·작성자 관계가 바뀌어도 옛 세션 값으로 삭제를 커밋하지 않는다 (3-1 §3-1-4 MUST).
   */
  private void requireOwnerOrActiveAdmin(User requester, Post post, Long requesterId) {
    if (requester.getRole() == Role.ADMIN && requester.getStatus() == Status.ACTIVE) {
      return;
    }
    if (requesterId.equals(post.getAuthorId())) {
      return;
    }
    log.info("남의 게시글을 삭제하려 했다: requesterId={} postId={}", requesterId, post.getId());
    throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 쓴 게시글만 삭제할 수 있습니다.");
  }

  /** 게시글 하나에 좋아요 정보를 붙인다 — {@link #get}·{@link #edit}이 공유한다. */
  private PostDetailResponse withLikeInfo(Post post, PostAuthor author, Long viewerId) {
    PostLikeSummary like =
        likeSummariesOf(viewerId, List.of(post.getId()))
            .getOrDefault(post.getId(), PostLikeSummary.NONE);
    return PostDetailResponse.of(post, author, like.count(), like.likedByMe());
  }

  /**
   * 좋아요 개수와 내 상태를 <b>한 번에</b> 모아 읽는다. 행마다 물으면 페이지 크기만큼 질의가 붙고, 개수와 내 상태를 따로 물으면 스냅샷이 갈려 모순된 응답이 나간다
   * (#368 리뷰, {@link PostLikeSummary}).
   */
  private Map<Long, PostLikeSummary> likeSummariesOf(Long viewerId, List<Long> postIds) {
    if (postIds.isEmpty()) {
      return Map.of();
    }
    return PostLikeSummary.byPostId(likes.countWithMineByPostIds(viewerId, postIds));
  }
}
