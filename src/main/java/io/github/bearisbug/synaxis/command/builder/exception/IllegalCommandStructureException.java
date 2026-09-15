package io.github.bearisbug.synaxis.command.builder.exception;

@SuppressWarnings("serial") // never serialized
public class IllegalCommandStructureException extends RuntimeException {
    public IllegalCommandStructureException() {
    }

    public IllegalCommandStructureException(String message) {
        super(message);
    }
}
