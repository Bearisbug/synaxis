package io.github.bearisbug.synaxis.protocol;

/**
 * The two messages of the host reserved {@code synaxis:handshake} channel ({@code API-M2-002}).
 */
public sealed interface HandshakeMessage extends Message permits C2SHello, S2CHello {
}
