package io.paradaux.chestshop.utils.encoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class Base62Test {

    @ParameterizedTest
    @CsvSource({
        // number, expected encoded string (alphabet: 0-9 a-z A-Z)
        "0,   '0'",
        "1,   '1'",
        "9,   '9'",
        "10,  'a'",
        "35,  'z'",
        "36,  'A'",
        "61,  'Z'",
        "62,  '10'",
        "100, '1C'",
    })
    void encode_returnsCorrectBase62String(int input, String expected) {
        assertThat(Base62.encode(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 62, 100, 12345, 999_999, Integer.MAX_VALUE - 1})
    void encodeThenDecode_isIdentity(int n) {
        assertThat(Base62.decode(Base62.encode(n))).isEqualTo(n);
    }

    @ParameterizedTest
    @CsvSource({
        "'0',   0",
        "'1',   1",
        "'a',   10",
        "'A',   36",
        "'Z',   61",
        "'10',  62",
    })
    void decode_returnsCorrectNumber(String code, int expected) {
        assertThat(Base62.decode(code)).isEqualTo(expected);
    }
}
