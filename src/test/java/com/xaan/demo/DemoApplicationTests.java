package com.xaan.demo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 전체 컨텍스트 기동 테스트.
 *
 * <p>실제 OpenBao(Transit KEK)와 PostgreSQL이 있어야 빈 생성이 끝난다(VaultTemplate, DataSource).
 * CI/로컬 단위 테스트에서는 이 두 의존이 없으므로 비활성화하고, 배포 검증은
 * {@code /actuator/health}와 스모크 테스트로 수행한다.
 * 실환경이 있는 곳에서만 @Disabled를 임시로 제거해 실행한다.
 */
@Disabled("requires live OpenBao (Transit KEK) + PostgreSQL - verified by /actuator/health in deployment")
@SpringBootTest
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
