package fr.iban.lands.integration.bluemap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BlueMapFailureCircuitBreaker {

    private final Logger logger;
    private final AtomicBoolean open = new AtomicBoolean();

    public BlueMapFailureCircuitBreaker(Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public synchronized void execute(String operation, Runnable action) {
        if (!open.get()) {
            run(operation, action);
        }
    }

    public synchronized void cleanup(String operation, Runnable action) {
        run(operation, action);
    }

    public boolean isOpen() {
        return open.get();
    }

    private void run(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError exception) {
            if (open.compareAndSet(false, true)) {
                logger.log(
                        Level.SEVERE,
                        "BlueMap integration failed during " + operation
                                + " and is disabled until restart.",
                        exception
                );
            }
        }
    }
}
