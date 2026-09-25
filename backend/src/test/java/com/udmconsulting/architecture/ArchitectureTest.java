package com.udmconsulting.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.udmconsulting", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_JPA_OR_SPRING_DATA = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "org.hibernate..");

    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_PERSISTENCE_ADAPTERS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..");

    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_JPA_OR_SPRING_DATA = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "org.hibernate..");

    @ArchTest
    static final ArchRule PLATFORM_DOES_NOT_DEPEND_ON_INTEGRATIONS = noClasses()
            .that().resideInAPackage("..platform..")
            .should().dependOnClassesThat().resideInAPackage("..integrations..");

    @ArchTest
    static final ArchRule MODULES_DO_NOT_DEPEND_ON_INTEGRATIONS = noClasses()
            .that().resideInAPackage("..modules..")
            .should().dependOnClassesThat().resideInAPackage("..integrations..");

    @ArchTest
    static final ArchRule PERSISTENCE_TYPES_STAY_IN_PERSISTENCE_PACKAGES = classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAPackage("..infrastructure.persistence..");

    @ArchTest
    static final ArchRule PLATFORM_CAPABILITIES_ARE_FREE_OF_CYCLES = slices()
            .matching("com.udmconsulting.platform.(*)..")
            .should().beFreeOfCycles();
}
