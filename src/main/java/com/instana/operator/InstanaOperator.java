package com.instana.operator;

import io.quarkus.runtime.StartupEvent;
import io.reactivex.plugins.RxJavaPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
public class InstanaOperator {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstanaOperator.class);

  @Produces
  @Singleton
  public ScheduledExecutorService executor() {
    return Executors.newScheduledThreadPool(4);
  }

  void onStartup(@Observes StartupEvent _ev) {
    LOGGER.debug("Starting the Instana Agent Operator...");
    RxJavaPlugins.setErrorHandler(t -> onError(new GlobalErrorEvent(t)));
  }

  // This is a synchronous event handler, i.e. calls to globalErrorEvent.fire()
  // will never return but trigger System.exit() immediately.
  void onError(@Observes GlobalErrorEvent ev) {
    LOGGER.error(ev.getMessage(), ev.getCause());
    Runtime.getRuntime().halt(1);
  }
}
