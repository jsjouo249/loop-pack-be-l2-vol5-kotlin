package com.loopers.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class ArchitectureTest {
    @Test fun `streamer preserves layered dependencies`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.loopers")
        noClasses().that().resideInAnyPackage("..domain.purchase..", "..domain.order.event..").should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..interfaces..", "..infrastructure..", "org.springframework..", "jakarta.persistence..")
            .check(classes)
        noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..", "jakarta.persistence..", "org.hibernate..")
            .check(classes)
        noClasses().that().resideInAPackage("..interfaces..").should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..").check(classes)
        noClasses().that().resideInAPackage("..infrastructure..").should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..interfaces..").check(classes)
    }
}
