package io.github.bearisbug.synaxis.utils;

public record MajorMinorVersion(int major, int minor) {

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
