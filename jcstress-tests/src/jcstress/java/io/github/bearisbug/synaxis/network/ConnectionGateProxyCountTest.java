package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.network.ConnectionGate.Verdict;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.LL_Result;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * L1 with the PROXY protocol counts on the threads of the handshakes: of two handshakes of one
 * address at once under a limit of one, exactly one is turned away.
 */
@JCStressTest
@Outcome(id = {"PASS, TOO_MANY", "TOO_MANY, PASS"}, expect = ACCEPTABLE)
@Outcome(id = "TOO_MANY, TOO_MANY", expect = FORBIDDEN, desc = "both turned away under a limit of one")
@Outcome(expect = FORBIDDEN)
@State
public class ConnectionGateProxyCountTest {
    private static final ServerConfig CONFIG = ServerConfig.builder().proxyProtocol(true).proxyProtocolRequired(true)
            .proxyProtocolTrustedNetworks("10.0.0.0/8").maxConnectionsPerIp(1).build();
    private static final SocketAddress PEER = new InetSocketAddress("203.0.113.7", 25565);

    private final ConnectionGate gate = new ConnectionGate(CONFIG);

    @Actor
    public void actor1(LL_Result r) {
        r.r1 = verdict();
    }

    @Actor
    public void actor2(LL_Result r) {
        r.r2 = verdict();
    }

    private Verdict verdict() {
        return gate.handshake(gate.untracked(), PEER, Intent.LOGIN);
    }
}
