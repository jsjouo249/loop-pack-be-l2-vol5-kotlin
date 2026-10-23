package com.loopers.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureTest {
    @Test
    fun respectsLayerDependencies() {
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.loopers")

        listOf("domain", "application", "interfaces", "infrastructure").forEach { layer ->
            assertTrue(
                classes.any { it.packageName.split('.').contains(layer) },
                "ArchUnit 검사 대상에 $layer 계층의 구현 클래스가 없습니다.",
            )
        }

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(classes)

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.http..", "org.springframework.web..", "org.springframework.transaction..", "com.loopers.support.error..")
            .check(classes)

        noClasses().that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..interfaces..")
            .check(classes)

        noClasses().that().resideInAnyPackage(
            "..domain.brand..",
            "..domain.product..",
            "..domain.user..",
            "..domain.point..",
            "..domain.like..",
            "..domain.order..",
            "..domain.commerce..",
            "..domain.payment..",
            "..domain.inventory..",
        ).should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
            .check(classes)

        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(classes)

        listOf(
            "com.loopers.application.transaction.RetryingWriteExecutor",
            "com.loopers.application.transaction.WriteTransactionAttempt",
            "com.loopers.infrastructure.common.JpaWriteOperations",
        ).forEach { name ->
            assertTrue(classes.any { it.name == name }, "새 트랜잭션/저장 경계가 검사에 포함되어야 합니다: $name")
        }
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "org.springframework.http..", "org.springframework.web..")
            .check(classes)

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes)

        val aggregateNames = setOf("brand", "product", "user", "point", "like", "order", "payment", "inventory")
        val repositoryAdapters = classes.filter {
            it.simpleName.endsWith("RepositoryImpl") &&
                it.packageName.substringAfter(".infrastructure.").substringBefore('.') in aggregateNames
        }
        assertTrue(repositoryAdapters.size == aggregateNames.size)
        repositoryAdapters.forEach { adapter ->
            val jpaDependencies = adapter.directDependenciesFromSelf.filter { dependency ->
                dependency.targetClass.simpleName.contains("Jpa") ||
                    dependency.targetClass.packageName.startsWith("org.springframework.data") ||
                    dependency.targetClass.packageName.startsWith("jakarta.persistence")
            }
            assertTrue(jpaDependencies.isEmpty(), "${adapter.simpleName} must not depend on JPA: $jpaDependencies")
        }
    }
}
