package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * <b>권한이 적히지 않은 엔드포인트가 없는지</b> 지킨다 (spec 3-1 §3-1-3, T-146).
 *
 * <p>권한 매트릭스는 표가 원본이고 코드가 그것을 따른다. 그런데 <b>표를 옮겨 적는 것을 잊어도 코드는 그냥 동작한다</b> — 인증만 되면 통과하는 엔드포인트가 조용히
 * 생긴다. 리뷰로 잡는 데 기대면 언젠가 놓친다.
 *
 * <p>클래스를 훑지 않고 <b>실제로 매핑된 핸들러</b>를 훑는다. 열려 있는 것은 매핑이지 클래스가 아니다.
 */
@SpringBootTest
class EndpointAuthorizationGuardTest extends AbstractIntegrationTest {

  /** 우리 코드만 본다. springdoc·actuator가 등록한 핸들러는 우리가 권한을 적을 대상이 아니다. */
  private static final String OUR_PACKAGE = "org.hackerkhu.hackerhp";

  /*
   * 이름으로 지정한다. actuator가 같은 타입의 빈(controllerEndpointHandlerMapping)을 하나 더 등록하므로
   * 타입만으로는 어느 쪽인지 정해지지 않는다. 우리가 볼 것은 애플리케이션 컨트롤러 쪽이다.
   */
  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  /* T-146 — 통과하는 길은 둘뿐이다. 권한을 적거나, 일부러 열었다고 말하거나. */
  @Test
  void everyEndpointDeclaresItsAccessRule() {
    List<String> undeclared =
        handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> isOurs(entry.getValue()))
            .filter(entry -> !declaresAccess(entry.getValue()))
            .map(entry -> entry.getValue().getMethod().getName() + " ← " + entry.getKey())
            .toList();

    assertThat(undeclared)
        .as(
            """
            권한이 적히지 않은 엔드포인트가 있다.

            권한 매트릭스(spec/3-1-DESIGN-ARCHITECTURE.md §3-1-3)의 어느 행에 해당하는지 보고
            @PreAuthorize를 붙인다. 인증 없이 열어야 하는 경로라면 @PublicApi(reason = "...")로
            그렇게 선언한다 — 그것도 결정이므로 근거를 남긴다.
            """)
        .isEmpty();
  }

  /* 지금 있는 엔드포인트가 실제로 검사를 받고 있는지 — 가드가 빈 목록을 훑고 있으면 의미가 없다. */
  @Test
  void theGuardActuallySeesOurEndpoints() {
    long ours = handlerMapping.getHandlerMethods().values().stream().filter(this::isOurs).count();

    assertThat(ours).isGreaterThanOrEqualTo(4);
  }

  private boolean isOurs(HandlerMethod handlerMethod) {
    return handlerMethod.getBeanType().getName().startsWith(OUR_PACKAGE);
  }

  private static boolean declaresAccess(HandlerMethod handlerMethod) {
    return handlerMethod.hasMethodAnnotation(PreAuthorize.class)
        || handlerMethod.hasMethodAnnotation(PublicApi.class);
  }
}
