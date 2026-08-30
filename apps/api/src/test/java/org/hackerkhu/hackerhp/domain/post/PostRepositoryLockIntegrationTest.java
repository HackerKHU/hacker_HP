package org.hackerkhu.hackerhp.domain.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T-492 — Spring Data repository 선언 자체가 실제 PostgreSQL 행 잠금을 얻는지 검증한다. */
@SpringBootTest
class PostRepositoryLockIntegrationTest extends AbstractIntegrationTest {

  @Autowired private PostRepository posts;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TransactionTemplate transaction;

  @Autowired
  void transactionTemplate(PlatformTransactionManager transactionManager) {
    transaction = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void clearBefore() {
    clearAll();
  }

  @AfterEach
  void clearAfter() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM posts");
    users.deleteAll();
  }

  @Test
  void findByIdForUpdateBlocksAnotherTransaction() throws Exception {
    User author =
        users.saveAndFlush(
            Accounts.approved("post-repository-lock", "post-lock@khu.ac.kr", "20250001"));
    Post post = posts.saveAndFlush(Post.write("잠글 글", "본문", author.getId(), Instant.now()));
    CountDownLatch firstHasLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);

    try {
      Future<?> holder =
          pool.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        assertThat(posts.findByIdForUpdate(post.getId())).isPresent();
                        firstHasLock.countDown();
                        await(releaseFirst);
                      }));
      assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).as("첫 트랜잭션이 행 잠금을 얻는다").isTrue();

      Future<Throwable> waiter =
          pool.submit(
              () -> {
                try {
                  transaction.executeWithoutResult(
                      ignored -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '250ms'");
                        posts.findByIdForUpdate(post.getId());
                      });
                  return null;
                } catch (Throwable failure) {
                  return failure;
                }
              });

      Throwable failure = waiter.get(5, TimeUnit.SECONDS);
      assertThat(failure).as("두 번째 잠금은 PostgreSQL lock_timeout으로 실패한다").isNotNull();
      Throwable root = rootCause(failure);
      assertThat(root.getClass().getName()).isEqualTo("org.postgresql.util.PSQLException");
      assertThat(root.getMessage()).contains("lock timeout");

      releaseFirst.countDown();
      holder.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("잠금 해제 신호를 받지 못했다");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("잠금 대기 중 중단됐다", interrupted);
    }
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root;
  }
}
