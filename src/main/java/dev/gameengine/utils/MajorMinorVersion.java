package dev.gameengine.utils;

public record MajorMinorVersion(int major, int minor) {

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
