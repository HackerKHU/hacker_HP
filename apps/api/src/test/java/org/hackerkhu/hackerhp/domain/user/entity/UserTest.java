package org.hackerkhu.hackerhp.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

  @Test
  void reactivateFromSuspendedSetsActive() {
    User user = User.applyForMembership("member@hackerkhu.org", "20240003", "테스트", "hashed");
    user.approve();
    user.suspend();

    user.reactivate();

    assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
  }

  @Test
  void reactivateFromPendingThrows() {
    User user = User.applyForMembership("member@hackerkhu.org", "20240004", "테스트", "hashed");

    assertThatThrownBy(user::reactivate).isInstanceOf(IllegalStateException.class);
  }
}
