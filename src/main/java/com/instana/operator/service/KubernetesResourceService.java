package com.instana.operator.service;

import static com.instana.operator.util.ConfigUtils.createClientConfig;
import static com.instana.operator.util.DateTimeUtils.nowUTC;
import static com.instana.operator.util.OkHttpClientUtils.createHttpClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.instana.operator.config.InstanaConfig;
import com.instana.operator.kubernetes.Client;
import com.instana.operator.kubernetes.Watchable;
import com.instana.operator.kubernetes.impl.ClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instana.operator.GlobalErrorEvent;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.quarkus.runtime.ShutdownEvent;

@ApplicationScoped
public class KubernetesResourceService {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesResourceService.class);

  @Inject
  javax.enterprise.event.Event<GlobalErrorEvent> errorEvent;

  private final Client kubernetesClient;

  private final Map<String, AtomicInteger> countsByType = new ConcurrentHashMap<>();
  private final Map<String, String> firstTimestampByType = new ConcurrentHashMap<>();

  public KubernetesResourceService() throws Exception {
    Config config = createClientConfig();
    this.kubernetesClient = new ClientImpl(new DefaultKubernetesClient(createHttpClient(config), config));
  }

  @Produces
  @Singleton
  public Client getKubernetesClient() {
    return kubernetesClient;
  }

  public Optional<Event> sendEvent(String eventName,
                                   String namespace,
                                   String reason,
                                   String message,
                                   String ownerApiVersion,
                                   String ownerKind,
                                   String ownerNamespace,
                                   String ownerName,
                                   String ownerUid) {
    AtomicInteger count = countsByType.computeIfAbsent(eventName, _k -> new AtomicInteger());
    String firstTimestamp = firstTimestampByType.computeIfAbsent(eventName, _k -> nowUTC());

    EventBuilder eb = new EventBuilder()
        .withApiVersion("v1")
        .withNewMetadata()
        .withNamespace(namespace)
        .withGenerateName(eventName + "-")
        .endMetadata()
        .withCount(count.incrementAndGet())
        .withFirstTimestamp(firstTimestamp)
        .withLastTimestamp(nowUTC())
        .withReason(reason)
        .withMessage(message)
        .withType("Normal")
        .withInvolvedObject(new ObjectReferenceBuilder()
            .withApiVersion(ownerApiVersion)
            .withKind(ownerKind)
            .withNamespace(ownerNamespace)
            .withName(ownerName)
            .withUid(ownerUid)
            .build());

    try {
      return Optional.ofNullable(kubernetesClient.create(ownerNamespace, eb.build()));
    } catch (KubernetesClientException e) {
      LOGGER.error("Could not create {} event: {}", eventName, e.getMessage(), e);
      return Optional.empty();
    }
  }

  public <T extends HasMetadata, L extends KubernetesResourceList<T>> ResourceCache<T> createResourceCache(
      String name,
      Function<Client, Watchable> fn) {
    Watchable watchable;
    try {
      watchable = fn.apply(kubernetesClient);
    } catch (Throwable t) {
      LOGGER.debug(t.getMessage(), t);
      errorEvent.fire(new GlobalErrorEvent(t));
      return null; // This line will never be executed, because the error event handler will call System.exit();
    }

    return new ResourceCache<>(name, watchable, errorEvent);
  }

  void onShutdown(@Observes ShutdownEvent ev) {
    kubernetesClient.close();
  }

}
