package io.paradaux.business;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The one-directional layering from wiki/conventions.md ("Architectural layering"),
 * checked against the compiled main classes: entrypoints → services → mappers.
 * Entrypoints here are commands, listeners and scheduled {@code jobs}.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.paradaux.business");

    @Test
    void entrypointsNeverTouchTheMappers() {
        noClasses()
                .that()
                .resideInAnyPackage("..commands..", "..listeners..", "..jobs..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..mappers..")
                .because("entrypoints go through a service; only services own persistence")
                .check(MAIN);
    }

    @Test
    void mappersHoldNoBusinessLogic() {
        noClasses()
                .that()
                .resideInAPackage("..mappers..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..services..", "..commands..", "..listeners..", "..jobs..")
                .because("the persistence layer is pure SQL mapping and never calls back up")
                .check(MAIN);
    }

    @Test
    void servicesNeverDependOnEntrypoints() {
        noClasses()
                .that()
                .resideInAPackage("..services..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..commands..", "..listeners..", "..jobs..")
                .because("layering is one-directional: entrypoints call services, not the reverse")
                .check(MAIN);
    }
}
