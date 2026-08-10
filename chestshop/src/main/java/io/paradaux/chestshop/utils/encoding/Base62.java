package io.paradaux.chestshop.utils.encoding;

/**
 * Base62 encoding class
 *
 * @author Acrobot
 */
public class Base62 {
    private static String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static int BASE = ALPHABET.length();

    /**
     * Encodes a number to Base62 string
     *
     * @param number Number to encode
     * @return Encoded number
     */
    public static String encode(int number) {
        if (number < 0) {
            // ADT-137: previously the while-loop below silently produced an empty
            // string for negatives (only ==0 was special-cased). Fail loudly instead.
            throw new IllegalArgumentException("Cannot Base62-encode a negative number: " + number);
        }
        if (number == 0) {
            return ALPHABET.substring(0, 1);
        }

        StringBuilder code = new StringBuilder(16);

        while (number > 0) {
            int remainder = number % BASE;
            number /= BASE;

            code.append(ALPHABET.charAt(remainder));
        }

        return code.reverse().toString();
    }


    /**
     * Decodes a Base62 string
     *
     * @param code Code to decode
     * @return Decoded code
     */
    public static int decode(String code) {
        // ADT-137: integer Horner's method instead of accumulating
        // indexOf * Math.pow(BASE, power) — Math.pow returns a double, so for codes
        // of ~6+ chars the term exceeded 2^53/int range and the result was silently
        // wrong (precision loss then narrowing), mis-resolving account/item ids.
        int number = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character '" + code.charAt(i) + "' in: " + code);
            }
            number = number * BASE + digit;
        }
        return number;
    }


}
