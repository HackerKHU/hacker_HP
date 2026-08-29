package org.hackerkhu.hackerhp.domain.note.service;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.note.repository.BookmarkRepository;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 즐겨찾기 추가·해제 (spec 2-1 §2-1-5, 3-2 §3-2-4).
 *
 * <p><b>둘 다 멱등이다.</b> 이미 있는 것을 또 담아도, 없는 것을 또 지워도 성공이다.
 *
 * <p>화면에서 별표를 두 번 누르거나 목록과 상세에서 각각 누르는 일은 흔한데, 그때 오류를 주면 화면은 <b>사용자에게 아무 의미 없는 안내</b>("이미
 * 즐겨찾기했습니다")를 띄워야 한다. 상태 변경이 "이미 그 상태면 아무것도 하지 않고 성공"인 것과 같은 결론이다 (3-2 §3-2-6).
 *
 * <p><b>토글이 아니다.</b> 같은 요청이 상태를 뒤집으면 재시도가 위험해진다 — 응답이 오는 길에 끊겨 클라이언트가 다시 보내면 <b>방금 담은 것이 조용히
 * 빠진다.</b> 화면은 {@code bookmarked}를 보고 담을지 뺄지 고르므로(#56 D1) 서버가 뒤집을 이유도 없다.
 */
@Service
public class BookmarkService {

  private final BookmarkRepository bookmarks;
  private final NoteRepository notes;
  private final UserRepository users;

  public BookmarkService(BookmarkRepository bookmarks, NoteRepository notes, UserRepository users) {
    this.bookmarks = bookmarks;
    this.notes = notes;
    this.users = users;
  }

  /**
   * <b>저장 직전에 요청자를 다시 확인한다</b> (spec 3-1 §3-1-4 MUST, #229 리뷰).
   *
   * <p>필터는 세션 값을 본다. {@code ACTIVE}로 통과한 요청이 잠금을 기다리는 사이에 관리자가 학기 전환을 누르거나 그 사람을 정지시킬 수 있고, 그러면
   * <b>대기 중이던 담기·빼기가 그대로 커밋된다.</b> 즐겨찾기도 자료 갈래라 같은 규칙을 받는다 (2-1 §2-1-5).
   *
   * <p>{@link RequesterCheck#requireNoteAccess}는 {@code SUSPENDED}·{@code PENDING}까지 함께 본다 — 이 경로에는
   * 그 확인도 없었다.
   *
   * <p>잠그는 것은 요청자 자신뿐이라 잠금 순서를 고민할 자리가 아니다. 자료 행은 잠그지 않는다 — 담기는 자료를 바꾸지 않으므로 잠금은 삭제를 기다리게 할 뿐이다.
   */
  private void lockAndCheck(Long userId) {
    RequesterCheck.requireNoteAccess(users.findByIdForUpdate(userId).orElse(null), userId);
  }

  /**
   * 담는다. 이미 담겨 있으면 아무것도 하지 않는다.
   *
   * <p><b>자료가 있는지 먼저 본다.</b> 없는 id를 그대로 넣으면 FK 위반이 {@code 500}으로 나간다 — 화면은 없는 자료에 별표를 채운 채로 남는다.
   */
  @Transactional
  public void add(Long userId, Long noteId) {
    lockAndCheck(userId);
    if (!notes.existsById(noteId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다.");
    }
    /*
     * 확인하고 저장하지 않는다. 동시에 도착한 둘이 모두 "없다"를 읽고 지나가고, 키를 직접
     * 배정하는 엔티티라 INSERT가 커밋까지 미뤄져 PK 위반이 여기서 잡히지도 않는다.
     * 있으면 아무것도 하지 않는 한 문장을 DB에게 맡긴다 (#189 리뷰).
     */
    try {
      bookmarks.insertIgnoringDuplicate(userId, noteId, Instant.now());
    } catch (DataIntegrityViolationException e) {
      /*
       * 위 확인과 이 삽입 사이에 그 자료가 지워졌다. ON CONFLICT는 중복만 넘기고 FK 위반은
       * 그대로 올린다 — 그냥 두면 500이 나가지만, 사용자에게 일어난 일은 "그 자료가 없다"이다.
       *
       * 자료 행을 잠그지 않는 이유는 잠글 값이 없기 때문이다. 담기는 자료를 바꾸지 않으므로
       * 잠금은 삭제를 기다리게 할 뿐이고, 결과는 어차피 404다 (#189 리뷰).
       */
      throw new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다.");
    }
  }

  /**
   * 뺀다. 담겨 있지 않아도 성공이다.
   *
   * <p><b>자료가 있는지 보지 않는다.</b> 자료가 지워지면 즐겨찾기도 함께 사라지므로({@code ON DELETE CASCADE}) 뺄 것이 이미 없고, 없는 자료에
   * {@code 404}를 주면 <b>화면이 지울 수 없는 별표를 들고 있게 된다.</b>
   */
  @Transactional
  public void remove(Long userId, Long noteId) {
    lockAndCheck(userId);
    // 읽지 않고 지운다. 읽고 지우면 겹친 두 요청 중 뒤의 것이 지울 것을 잃고 터진다.
    bookmarks.deleteBookmark(userId, noteId);
  }
}
