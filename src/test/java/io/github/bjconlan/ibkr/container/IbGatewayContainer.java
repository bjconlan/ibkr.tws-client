package io.github.bjconlan.ibkr.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * A paper-trading IB Gateway in a container, using the same image as
 * {@code ~/Workspace/lo.fi/compose.yaml}.
 *
 * <p>The image runs the gateway under Xvfb and logs in with IBC. It exposes the paper API on
 * container port {@value #API_PORT}; callers should use {@link #apiPort()} which resolves the
 * random host mapping Testcontainers assigned.
 *
 * <p>The gateway is read-only: orders are rejected by the server.
 */
public final class IbGatewayContainer extends GenericContainer<IbGatewayContainer> {

    public static final DockerImageName IMAGE = DockerImageName.parse("ghcr.io/gnzsnz/ib-gateway:latest");

    /** Container port the paper API listens on. */
    public static final int API_PORT = 4004;

    /** IBC logs this once the gateway session is up. */
    private static final String READY_LOG = ".*Login has completed.*";

    public IbGatewayContainer(String userId, String password) {
        super(IMAGE);
        withEnv("TWS_USERID", userId);
        withEnv("TWS_PASSWORD", password);
        withEnv("TRADING_MODE", "paper");
        withEnv("READ_ONLY_API", "yes");
        withEnv("BYPASS_WARNING", "yes");
        withExposedPorts(API_PORT);
        waitingFor(new WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(READY_LOG, 1))
                .withStrategy(Wait.forListeningPorts(API_PORT))
                .withStartupTimeout(Duration.ofMinutes(5)));
    }

    /** Host the gateway is reachable on. */
    public String gatewayHost() {
        return getHost();
    }

    /** Mapped host port for the paper API. */
    public int gatewayPort() {
        return getMappedPort(API_PORT);
    }
}
