-- 자료 좋아요 (spec 2-1 §2-1-1, 3-2 §3-2-4, #344).
-- 즐겨찾기(bookmarks)와 완전히 별개 자원이다 (3-3 결정 25 D1) — 즐겨찾기는
-- "다시 보려고 담아둔다", 좋아요는 "품질에 공감한다"로 뜻이 다르다.
CREATE TABLE note_likes (
    -- 회원이 지워지면 그 좋아요도 함께 사라진다 — bookmarks와 같은 판단이다.
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    note_id    BIGINT    NOT NULL REFERENCES notes (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 자료) 중복을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, note_id)
);

-- note_id로 들어오는 조회를 위한 인덱스 (#367 리뷰). 복합 PK는 선두 열이 user_id라
-- 이 방향을 받쳐주지 못한다 — 목록·상세의 좋아요 개수 집계와 자료 삭제의 FK CASCADE가
-- 모두 note_id로 찾으므로, 없으면 좋아요가 쌓일수록 매 요청이 전체 스캔이 된다.
CREATE INDEX idx_note_likes_note_id ON note_likes (note_id);
