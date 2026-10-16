package io.paradaux.treasury;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The one-directional layering from wiki/conventions.md ("Architectural layering"),
 * checked against the compiled main classes: entrypoints → services → mappers.
 * Entrypoints here are commands, the {@code events} listeners and scheduled {@code tasks}.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.paradaux.treasury");

    @Test
    void entrypointsNeverTouchTheMappers() {
        noClasses().that().resideInAnyPackage("..commands..", "..events..", "..tasks..")
                .should().dependOnClassesThat().resideInAPackage("..mappers..")
                .because("entrypoints go through a service; only services own persistence")
                .check(MAIN);
    }

    @Test
    void mappersHoldNoBusinessLogic() {
        noClasses().that().resideInAPackage("..mappers..")
                .should().dependOnClassesThat().resideInAnyPackage("..services..", "..commands..", "..events..", "..tasks..")
                .because("the persistence layer is pure SQL mapping and never calls back up")
                .check(MAIN);
    }

    @Test
    void servicesNeverDependOnEntrypoints() {
        noClasses().that().resideInAPackage("..services..")
                .should().dependOnClassesThat().resideInAnyPackage("..commands..", "..events..", "..tasks..")
                .because("layering is one-directional: entrypoints call services, not the reverse")
                .check(MAIN);
    }
}
