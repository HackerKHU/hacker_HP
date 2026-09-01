-- 공지 좋아요 (spec 2-1 §2-1-6, 3-2 §3-2-5, #343).
CREATE TABLE notice_likes (
    -- 회원이 지워지면 그 좋아요도 함께 사라진다 — bookmarks와 같은 판단이다.
    -- 좋아요는 "그 사람이 지금 눌렀다"는 사실 자체가 값이라, 즐겨찾기와 달리
    -- 탈퇴 회원을 위해 남겨 둘 표시("탈퇴한 회원")가 없다.
    user_id    BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    notice_id  BIGINT    NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    -- 복합 PK로 (회원, 공지) 중복을 막는다 — bookmarks와 같은 판단(spec 3-2 §3-2-2).
    PRIMARY KEY (user_id, notice_id)
);
