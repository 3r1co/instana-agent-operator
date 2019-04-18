package com.instana.operator.agent;

import static com.instana.operator.util.AgentResourcesUtil.createAgentClusterRole;
import static com.instana.operator.util.AgentResourcesUtil.createAgentClusterRoleBinding;
import static com.instana.operator.util.AgentResourcesUtil.createAgentDaemonSet;
import static com.instana.operator.util.AgentResourcesUtil.createAgentKeySecret;
import static com.instana.operator.util.AgentResourcesUtil.createServiceAccount;

import java.nio.charset.Charset;
import java.util.Base64;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.ObservesAsync;
import javax.inject.Inject;

import com.instana.operator.kubernetes.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.instana.operator.GlobalErrorEvent;
import com.instana.operator.config.InstanaConfig;
import com.instana.operator.leaderelection.ElectedLeaderEvent;
import com.instana.operator.service.InstanaConfigService;
import com.instana.operator.service.KubernetesResourceService;
import com.instana.operator.service.OperatorNamespaceService;
import com.instana.operator.service.OperatorOwnerReferenceService;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;

@ApplicationScoped
public class AgentDeployer {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentDeployer.class);

  @Inject
  KubernetesResourceService clientService;
  @Inject
  OperatorNamespaceService namespaceService;
  @Inject
  OperatorOwnerReferenceService ownerReferenceService;
  @Inject
  InstanaConfigService instanaConfigService;
  @Inject
  Event<GlobalErrorEvent> globalErrorEvent;

  void onLeaderElection(@ObservesAsync ElectedLeaderEvent ev) {
    InstanaConfig instanaConfig = instanaConfigService.getConfig();

    String namespace = namespaceService.getNamespace();
    Client client = clientService.getKubernetesClient();

    LOGGER.debug("Finding the operator deployment as owner reference...");
    ownerReferenceService.getOperatorDeploymentAsOwnerReference()
        .thenAccept(ownerRef -> {

          ServiceAccount serviceAccount = createServiceAccount(
              namespace, instanaConfig.getServiceAccountName(), ownerRef);
          maybeCreateResource(serviceAccount);

          if (instanaConfig.isRbacCreate()) {
            ClusterRole clusterRole = createAgentClusterRole(
                instanaConfig.getClusterRoleName(), ownerRef);
            maybeCreateResource(clusterRole);

            ClusterRoleBinding clusterRoleBinding = createAgentClusterRoleBinding(
                namespace, instanaConfig.getClusterRoleBindingName(), serviceAccount, clusterRole, ownerRef);
            maybeCreateResource(clusterRoleBinding);
          }

          Secret secret = createAgentKeySecret(
              namespace, instanaConfig.getSecretName(), base64(instanaConfig.getAgentKey()), ownerRef);
          maybeCreateResource(secret);

          ConfigMap agentConfigMap = clientService.getKubernetesClient()
              .get(namespaceService.getNamespace(), instanaConfig.getConfigMapName(), ConfigMap.class);
          if (null == agentConfigMap) {
            globalErrorEvent.fire(new GlobalErrorEvent(new IllegalStateException(
                "Agent ConfigMap named " + instanaConfig.getConfigMapName() + " not found in namespace "
                    + namespaceService.getNamespace())));
            return;
          }

          DaemonSet daemonSet = createAgentDaemonSet(
              namespace,
              instanaConfig.getDaemonSetName(),
              serviceAccount,
              secret,
              agentConfigMap,
              ownerRef,
              instanaConfig.getAgentDownloadKey(),
              instanaConfig.getZoneName(),
              instanaConfig.getEndpoint(),
              instanaConfig.getEndpointPort(),
              instanaConfig.getAgentMode(),
              instanaConfig.getAgentCpuReq(),
              instanaConfig.getAgentMemReq(),
              instanaConfig.getAgentCpuLimit(),
              instanaConfig.getAgentMemLimit(),
              instanaConfig.getAgentImageName(),
              instanaConfig.getAgentImageTag(),
              instanaConfig.getAgentProxyHost(),
              instanaConfig.getAgentProxyPort(),
              instanaConfig.getAgentProxyProtocol(),
              instanaConfig.getAgentProxyUser(),
              instanaConfig.getAgentProxyPasswd(),
              instanaConfig.getAgentProxyUseDNS(),
              instanaConfig.getAgentHttpListen());
          maybeCreateResource(daemonSet);

          LOGGER.debug("Successfully deployed the Instana agent.");
        });
  }

  @SuppressWarnings("unchecked")
  private <T extends HasMetadata> void maybeCreateResource(T resource) {
    LOGGER.debug("Creating {} at {}/{}...",
        resource.getKind(),
        namespaceService.getNamespace(),
        resource.getMetadata().getName());
    try {
      clientService.getKubernetesClient().create(namespaceService.getNamespace(), resource);
    } catch (KubernetesClientException e) {
      if (e.getCode() != 409) {
        globalErrorEvent.fire(new GlobalErrorEvent(e.getCause()));
      } else {
        LOGGER.info("{} {}/{} already exists.",
            resource.getKind(),
            namespaceService.getNamespace(),
            resource.getMetadata().getName());
      }
    }
  }

  private String base64(String secret) {
    return new String(Base64.getEncoder().encode(secret.getBytes(Charset.forName("ASCII"))), Charset.forName("ASCII"));
  }

}
