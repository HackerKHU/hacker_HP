-- 회원을 지워도 공지는 남긴다 (#58, spec 2-2 §2-2-4).
--
-- V1은 ON DELETE 절 없이 만들어져 기본값 NO ACTION이다 — 공지를 한 번이라도 쓴 관리자는
-- 삭제 자체가 FK 위반으로 실패한다. notes.uploader_id·photos.uploader_id는 V4에서 이미
-- SET NULL로 만들어졌고, 여기만 남아 있었다.
--
-- 공지는 동아리의 기록이지 개인의 것이 아니다. 작성자 자리가 비면 응답이 "탈퇴한 회원"을
-- 채운다 (3-2 §3-2-2).
ALTER TABLE notices DROP CONSTRAINT IF EXISTS notices_author_id_fkey;

ALTER TABLE notices
    ADD CONSTRAINT notices_author_id_fkey
    FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL;
