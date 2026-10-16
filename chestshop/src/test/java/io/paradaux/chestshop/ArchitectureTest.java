package io.paradaux.chestshop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The one-directional layering from wiki/conventions.md ("Architectural layering"),
 * checked against the compiled main classes: entrypoints → services → mappers.
 * Entrypoints here are commands, listeners and the {@code dialogs} screens. Services
 * may still see {@code dialogs}: {@code ShopFinderService} takes the dialog's
 * {@code FindState} as its query object.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.paradaux.chestshop");

    @Test
    void entrypointsNeverTouchTheMappers() {
        noClasses()
                .that()
                .resideInAnyPackage("..commands..", "..listeners..", "..dialogs..")
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
                .resideInAnyPackage("..services..", "..commands..", "..listeners..", "..dialogs..")
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
                .resideInAnyPackage("..commands..", "..listeners..")
                .because("layering is one-directional: entrypoints call services, not the reverse")
                .check(MAIN);
    }
}
