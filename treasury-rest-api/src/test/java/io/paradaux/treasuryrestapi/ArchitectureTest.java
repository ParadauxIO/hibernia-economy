package io.paradaux.treasuryrestapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The one-directional layering from wiki/conventions.md ("Architectural layering"),
 * checked against the compiled main classes: entrypoints → service → mapper.
 * In the Spring app the entrypoint is the {@code controller} package.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.paradaux.treasuryrestapi");

    @Test
    void entrypointsNeverTouchTheMappers() {
        noClasses()
                .that()
                .resideInAnyPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..mapper..")
                .because("entrypoints go through a service; only services own persistence")
                .check(MAIN);
    }

    @Test
    void mappersHoldNoBusinessLogic() {
        noClasses()
                .that()
                .resideInAPackage("..mapper..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..service..", "..controller..")
                .because("the persistence layer is pure SQL mapping and never calls back up")
                .check(MAIN);
    }

    @Test
    void servicesNeverDependOnEntrypoints() {
        noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..")
                .because("layering is one-directional: entrypoints call services, not the reverse")
                .check(MAIN);
    }
}
