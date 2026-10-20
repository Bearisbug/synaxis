package io.github.bearisbug.synaxis.utils;

import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MathUtils {

    private MathUtils() {
    }

    public static int square(int num) {
        return num * num;
    }

    public static float square(float num) {
        return num * num;
    }

    public static double square(double num) {
        return num * num;
    }

    public static boolean isBetween(byte number, byte min, byte max) {
        return number >= min && number <= max;
    }

    public static boolean isBetween(int number, int min, int max) {
        return number >= min && number <= max;
    }

    public static boolean isBetween(double number, double min, double max) {
        return number >= min && number <= max;
    }

    public static boolean isBetween(float number, float min, float max) {
        return number >= min && number <= max;
    }

    public static boolean isBetweenUnordered(double number, double compare1, double compare2) {
        if (compare1 > compare2) {
            return isBetween(number, compare2, compare1);
        } else {
            return isBetween(number, compare1, compare2);
        }
    }

    public static boolean isBetweenUnordered(float number, float compare1, float compare2) {
        if (compare1 > compare2) {
            return isBetween(number, compare2, compare1);
        } else {
            return isBetween(number, compare1, compare2);
        }
    }

    public static int bitsToRepresent(int n) {
        Check.argCondition(n < 1, "n must be greater than 0");
        return Integer.SIZE - Integer.numberOfLeadingZeros(n);
    }

    public static long ceilLong(double value) {
        long i = (long) value;
        return value > i ? i + 1L : i;
    }

    public static double absMax(double d0, double d1) {
        return Math.max(Math.abs(d0), Math.abs(d1));
    }

}
