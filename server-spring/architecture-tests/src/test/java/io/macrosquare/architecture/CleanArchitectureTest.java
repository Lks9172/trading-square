package io.macrosquare.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class CleanArchitectureTest {

    private static final String[] BOUNDED_CONTEXTS = {
            "market", "company", "research", "crypto", "execution", "notification", "compatibility",
            "institutional", "policy", "disclosure", "integrity"
    };

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.macrosquare");
    }

    @Test
    void domainMustRemainFrameworkAndIoFree() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "io.minio..",
                        "software.amazon.awssdk..",
                        "org.postgresql..",
                        "io.opentelemetry..",
                        "java.io..",
                        "java.net..",
                        "java.nio.file..",
                        "java.sql..",
                        "java.util.concurrent.."
                )
                .check(productionClasses);
    }

    @Test
    void dependenciesMustPointInward() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..adapter..", "..bootstrap..")
                .check(productionClasses);

        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..", "..bootstrap..")
                .check(productionClasses);

        noClasses()
                .that().resideInAPackage("..adapter..")
                .should().dependOnClassesThat().resideInAPackage("..bootstrap..")
                .check(productionClasses);
    }

    @Test
    void applicationMustRemainFrameworkAndTransportFree() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "io.minio..",
                        "software.amazon.awssdk..",
                        "org.postgresql..",
                        "java.net..",
                        "java.nio.file..",
                        "java.sql.."
                )
                .check(productionClasses);
    }

    @Test
    void springControllersMustLiveOnlyInInboundWebAdapters() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..adapter.in.web..")
                .check(productionClasses);
    }

    @Test
    void scheduledInboundAdaptersMustUseTheClusterExclusiveExecutionPort() {
        classes()
                .that().resideInAPackage("..adapter.in.scheduling..")
                .and().haveSimpleNameEndingWith("Scheduler")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.macrosquare.shared.application.port.out.ExclusiveTaskExecution")
                .because("process-local guards cannot prevent duplicate side effects across replicas")
                .check(productionClasses);
    }

    @Test
    void boundedContextsMustCommunicateThroughPortsOrOuterAdapters() {
        for (var source : BOUNDED_CONTEXTS) {
            var foreignContexts = java.util.Arrays.stream(BOUNDED_CONTEXTS)
                    .filter(target -> !target.equals(source))
                    .map(target -> "io.macrosquare." + target + "..")
                    .toArray(String[]::new);
            noClasses()
                    .that().resideInAnyPackage(
                            "io.macrosquare." + source + ".domain..",
                            "io.macrosquare." + source + ".application.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(foreignContexts)
                    .because("inner layers must not use another bounded context as a shared library")
                    .check(productionClasses);
        }
    }
}
