package io.yupiik.tomcat;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Server;
import org.apache.catalina.Service;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Redirect all events to a single callback, easier for debugging tools.
 */
public abstract class BaseListener implements LifecycleListener, Consumer<LifecycleEvent> {
    private final AtomicBoolean serverSeen = new AtomicBoolean(false);

    @Override
    public void lifecycleEvent(final LifecycleEvent lifecycleEvent) {
        if (Server.class.isInstance(lifecycleEvent.getSource()) && serverSeen.compareAndSet(false, true)) {
            Stream.of(Server.class.cast(lifecycleEvent.getSource()).findServices())
                    .peek(service -> service.addLifecycleListener(this))
                    .map(this::toEngine)
                    .peek(engine -> engine.addLifecycleListener(this))
                    .flatMap(engine -> Stream.of(engine.findChildren()))
                    .map(Host.class::cast)
                    .forEach(host -> {
                        host.addLifecycleListener(this);
                        host.addContainerListener(event -> { // unified event listener API
                            final Object potentialContainer = event.getData();
                            accept(new LifecycleEvent(event.getContainer(), event.getType(), potentialContainer));
                            if (Container.ADD_CHILD_EVENT.equals(event.getType()) && Context.class.isInstance(potentialContainer)) {
                                final Context context = Context.class.cast(potentialContainer);
                                context.addLifecycleListener(BaseListener.this);
                                context.addContainerListener(e -> new LifecycleEvent(e.getContainer(), e.getType(), e.getData()));
                            }
                        });
                    });
        }
        accept(lifecycleEvent);
    }

    // changed in tomcat 8 -> 9/10
    private Engine toEngine(final Service service) {
        try {
            return Engine.class.cast(service.getClass().getMethod("getContainer").invoke(service));
        } catch (final IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
