package io.paradaux.chestshop.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.chestshop.support.ServerTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Test;

/** Exact member descriptors extracted from the six installed add-on jars. */
class LegacyAddonAbiTest extends ServerTest {
    @Test
    void installedAddonReferencesResolveWithTheirOriginalDescriptors() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/legacy-addon-abi.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String[] ref = line.split("\t");
                ClassLoader loader = getClass().getClassLoader();
                Class<?> owner = Class.forName(ref[1].replace('/', '.'), false, loader);
                if (ref[0].equals("9")) {
                    Class<?> fieldType = MethodType.fromMethodDescriptorString("()" + ref[3], loader)
                            .returnType();
                    assertThat(owner.getField(ref[2]).getType()).as(line).isEqualTo(fieldType);
                } else {
                    MethodType method = MethodType.fromMethodDescriptorString(ref[3], loader);
                    if (ref[2].equals("<init>"))
                        assertThat(owner.getConstructor(method.parameterArray()))
                                .as(line)
                                .isNotNull();
                    else
                        assertThat(owner.getMethod(ref[2], method.parameterArray())
                                        .getReturnType())
                                .as(line)
                                .isEqualTo(method.returnType());
                }
            }
        }
    }
}
