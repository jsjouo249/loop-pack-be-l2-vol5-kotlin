package com.loopers.purchase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PurchaseConfigurationTest {
    @Test fun `local startup preserves durable consumer tables`() {
        ApplicationContextRunner().withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=local").run { context ->
                assertThat(context.environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate")
                assertThat(context.environment.getProperty("spring.application.name")).isEqualTo("commerce-streamer")
            }
    }

    @Test fun `isolated integration tests can create their own schema`() {
        ApplicationContextRunner().withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=test").run { context ->
                assertThat(context.environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create")
                assertThat(context.environment.getProperty("spring.kafka.admin.auto-create")).isEqualTo("false")
            }
    }
}
