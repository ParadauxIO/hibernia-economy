package io.paradaux.treasury;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.paradaux.treasury.api.TreasuryApi;
import org.junit.jupiter.api.Test;

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
    void pluginPackagesNeverOverlapThePublishedApi() {
        // Import each artifact separately: importPackages includes both on the flat test classpath.
        var pluginClasses = new ClassFileImporter()
                .importUrl(Treasury.class.getProtectionDomain().getCodeSource().getLocation());
        var apiClasses = new ClassFileImporter()
                .importUrl(
                        TreasuryApi.class.getProtectionDomain().getCodeSource().getLocation());

        assertThat(pluginClasses).isNotEmpty();
        assertThat(apiClasses).isNotEmpty();
        assertThat(pluginClasses.stream().map(JavaClass::getPackageName).distinct())
                .as("Treasury implementation packages must be separate from treasury-api")
                .doesNotContainAnyElementsOf(apiClasses.stream()
                        .map(JavaClass::getPackageName)
                        .distinct()
                        .toList());
    }

    @Test
    void entrypointsNeverTouchTheMappers() {
        noClasses()
                .that()
                .resideInAnyPackage("..commands..", "..events..", "..tasks..")
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
                .resideInAnyPackage("..services..", "..commands..", "..events..", "..tasks..")
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
                .resideInAnyPackage("..commands..", "..events..", "..tasks..")
                .because("layering is one-directional: entrypoints call services, not the reverse")
                .check(MAIN);
    }
}
