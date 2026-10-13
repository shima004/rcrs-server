package rescuecore2.standard.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import kernel.AgentProxy;
import rescuecore2.connection.Connection;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.AmbulanceCentre;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.FireStation;
import rescuecore2.standard.entities.PoliceOffice;
import rescuecore2.standard.messages.AKRest;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

class StandardCommandCollectorTest {
    private static class TestAgent extends AgentProxy {
        final CountDownLatch polled = new CountDownLatch(1);

        TestAgent(Entity entity) {
            super("test", entity, (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> null));
        }

        void receive(Command command) {
            commandReceived(command);
        }

        @Override
        public Collection<Command> getAgentCommands(int timestep) {
            Collection<Command> result = super.getAgentCommands(timestep);
            polled.countDown();
            return result;
        }
    }

    private List<AgentProxy> centres() {
        return List.of(new TestAgent(new FireStation(new EntityID(1))),
                new TestAgent(new PoliceOffice(new EntityID(2))),
                new TestAgent(new AmbulanceCentre(new EntityID(3))));
    }

    @Test
    void silentCentresDoNotBlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertTrue(new StandardCommandCollector().getAgentCommands(centres(), 1).isEmpty()));
    }

    @Test
    void collectsCentreCommunicationAlongsideFieldAction() {
        var agents = new java.util.ArrayList<AgentProxy>(centres());
        var field = new TestAgent(new FireBrigade(new EntityID(4)));
        agents.add(field);
        var speak = new AKSpeak(agents.get(0).getControlledEntity().getID(), 1, 1, new byte[] {42});
        ((TestAgent) agents.get(0)).receive(speak);
        var rest = new AKRest(field.getControlledEntity().getID(), 1);
        field.receive(rest);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(List.of(speak, rest),
                        new StandardCommandCollector().getAgentCommands(agents, 1)));
    }

    @Test
    void fieldCommunicationStillWaitsForAction() throws Exception {
        var field = new TestAgent(new FireBrigade(new EntityID(4)));
        var speak = new AKSpeak(field.getControlledEntity().getID(), 1, 1, new byte[] {42});
        field.receive(speak);
        var agents = new java.util.ArrayList<AgentProxy>(centres());
        agents.add(field);
        var executor = Executors.newSingleThreadExecutor();
        var result = executor.submit(() -> new StandardCommandCollector().getAgentCommands(agents, 1));
        try {
            assertTrue(field.polled.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));
            var rest = new AKRest(field.getControlledEntity().getID(), 1);
            field.receive(rest);
            assertEquals(List.of(speak, rest), result.get(2, TimeUnit.SECONDS));
        } finally {
            result.cancel(true);
            executor.shutdownNow();
        }
    }
}
