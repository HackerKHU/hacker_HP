package org.hackerkhu.hackerhp.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Department;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /departments} (#166, spec 3-2 §3-2-3).
 *
 * <p>신청 폼(비로그인도 화면은 볼 수 있다)이 쓰는 값이라 <b>인증 없이 열려야 한다</b>는 것이 이 테스트의 핵심이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DepartmentApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  /* 로그인하지 않아도 200이다 — 신청 폼은 로그인 여부와 무관하게 이 값을 그려야 한다. */
  @Test
  void anonymousCanListDepartments() throws Exception {
    mockMvc
        .perform(get("/api/v1/departments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(Department.ALL.size()))
        .andExpect(jsonPath("$[0]").value(Department.ALL.get(0)));
  }
}
