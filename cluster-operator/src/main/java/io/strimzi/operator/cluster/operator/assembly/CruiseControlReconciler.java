/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.Storage;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlResources;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.CertUtils;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.model.ImagePullPolicy;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Ca;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.NetworkPolicyOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.ServiceAccountOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.vertx.core.Future;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Class used for reconciliation of Cruise Control. This class contains both the steps of the Cruise Control
 * reconciliation pipeline and is also used to store the state between them.
 */
public class CruiseControlReconciler {
    private static final ReconciliationLogger LOGGER = ReconciliationLogger.create(CruiseControlReconciler.class.getName());

    private final Reconciliation reconciliation;
    private final CruiseControl cruiseControl;
    private final ClusterCa clusterCa;
    private final List<String> maintenanceWindows;
    private final long operationTimeoutMs;
    private final String operatorNamespace;
    private final Labels operatorNamespaceLabels;
    private final boolean isNetworkPolicyGeneration;

    private final DeploymentOperator deploymentOperator;
    private final SecretOperator secretOperator;
    private final ServiceAccountOperator serviceAccountOperator;
    private final ServiceOperator serviceOperator;
    private final NetworkPolicyOperator networkPolicyOperator;
    private final ConfigMapOperator configMapOperator;

    private boolean existingCertsChanged = false;

    private String serverConfigurationHash = "";
    private String capacityConfigurationHash = "";

    /**
     * Constructs the Cruise Control reconciler
     *
     * @param reconciliation            Reconciliation marker
     * @param config                    Cluster Operator Configuration
     * @param supplier                  Supplier with Kubernetes Resource Operators
     * @param kafkaAssembly             The Kafka custom resource
     * @param versions                  The supported Kafka versions
     * @param kafkaBrokerNodes          List of the broker nodes which are part of the Kafka cluster
     * @param kafkaBrokerStorage        A map with storage configuration used by the Kafka cluster and its broker pools
     * @param kafkaBrokerResources      A map with resource configuration used by the Kafka cluster and its broker pools
     * @param clusterCa                 The Cluster CA instance
     */
    @SuppressWarnings({"checkstyle:ParameterNumber"})
    public CruiseControlReconciler(
            Reconciliation reconciliation,
            ClusterOperatorConfig config,
            ResourceOperatorSupplier supplier,
            Kafka kafkaAssembly,
            KafkaVersion.Lookup versions,
            Set<NodeRef> kafkaBrokerNodes,
            Map<String, Storage> kafkaBrokerStorage,
            Map<String, ResourceRequirements> kafkaBrokerResources,
            ClusterCa clusterCa
    ) {
        this.reconciliation = reconciliation;
        this.cruiseControl = CruiseControl.fromCrd(reconciliation, kafkaAssembly, versions, kafkaBrokerNodes, kafkaBrokerStorage, kafkaBrokerResources, supplier.sharedEnvironmentProvider);
        this.clusterCa = clusterCa;
        this.maintenanceWindows = kafkaAssembly.getSpec().getMaintenanceTimeWindows();
        this.operationTimeoutMs = config.getOperationTimeoutMs();
        this.operatorNamespace = config.getOperatorNamespace();
        this.operatorNamespaceLabels = config.getOperatorNamespaceLabels();
        this.isNetworkPolicyGeneration = config.isNetworkPolicyGeneration();

        this.deploymentOperator = supplier.deploymentOperations;
        this.secretOperator = supplier.secretOperations;
        this.serviceAccountOperator = supplier.serviceAccountOperations;
        this.serviceOperator = supplier.serviceOperations;
        this.networkPolicyOperator = supplier.networkPolicyOperator;
        this.configMapOperator = supplier.configMapOperations;
    }

    /**
     * The main reconciliation method which triggers the whole reconciliation pipeline. This is the method which is
     * expected to be called from the outside to trigger the reconciliation.
     *
     * @param isOpenShift       Flag indicating whether we are on OpenShift or not
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  List of Image pull secrets
     * @param clock             The clock for supplying the reconciler with the time instant of each reconciliation cycle.
 *                              That time is used for checking maintenance windows
     *
     * @return                  Future which completes when the reconciliation completes
     */
    public Future<Void> reconcile(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets, Clock clock)    {
        return networkPolicy()
                .compose(i -> serviceAccount())
                .compose(i -> configMap())
                .compose(i -> certificatesSecret(clock))
                .compose(i -> apiSecret())
                .compose(i -> service())
                .compose(i -> deployment(isOpenShift, imagePullPolicy, imagePullSecrets))
                .compose(i -> waitForDeploymentReadiness());
    }

    /**
     * Manages the Cruise Control Network Policies.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> networkPolicy() {
        if (isNetworkPolicyGeneration) {
            return networkPolicyOperator
                    .reconcile(
                            reconciliation,
                            reconciliation.namespace(),
                            CruiseControlResources.networkPolicyName(reconciliation.name()),
                            cruiseControl != null ? cruiseControl.generateNetworkPolicy(operatorNamespace, operatorNamespaceLabels) : null
                    ).map((Void) null);
        } else {
            return Future.succeededFuture();
        }
    }

    /**
     * Manages the Cruise Control Service Account.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> serviceAccount() {
        return serviceAccountOperator
                .reconcile(
                        reconciliation,
                        reconciliation.namespace(),
                        CruiseControlResources.serviceAccountName(reconciliation.name()),
                        cruiseControl != null ? cruiseControl.generateServiceAccount() : null
                ).map((Void) null);
    }

    /**
     * Manages the Cruise Control ConfigMap which contains the following:
     * (1) Cruise Control server configuration
     * (2) Cruise Control broker capacity configuration
     * (3) Cruise Control server logging and metrics configuration
     *
     * @return Future which completes when the reconciliation is done
     */
    protected Future<Void> configMap() {
        if (cruiseControl != null) {
            return MetricsAndLoggingUtils.metricsAndLogging(reconciliation, configMapOperator, cruiseControl.logging(), cruiseControl.metrics())
                    .compose(metricsAndLogging -> {
                        ConfigMap configMap = cruiseControl.generateConfigMap(metricsAndLogging);

                        this.serverConfigurationHash = Util.hashStub(configMap.getData().get(CruiseControl.SERVER_CONFIG_FILENAME));
                        this.capacityConfigurationHash = Util.hashStub(configMap.getData().get(CruiseControl.CAPACITY_CONFIG_FILENAME));

                        return configMapOperator
                                .reconcile(
                                        reconciliation,
                                        reconciliation.namespace(),
                                        CruiseControlResources.configMapName(reconciliation.name()),
                                        configMap
                                ).map((Void) null);
                    });
        } else {
            return configMapOperator.reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.configMapName(reconciliation.name()), null)
                    .map((Void) null);
        }
    }

    /**
     * Manages the Cruise Control certificates Secret.
     *
     * @param clock The clock for supplying the reconciler with the time instant of each reconciliation cycle.
     *              That time is used for checking maintenance windows
     *
     * @return      Future which completes when the reconciliation is done
     */
    protected Future<Void> certificatesSecret(Clock clock) {
        if (cruiseControl != null) {
            return secretOperator.getAsync(reconciliation.namespace(), CruiseControlResources.secretName(reconciliation.name()))
                    .compose(oldSecret -> {
                        return secretOperator
                                .reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.secretName(reconciliation.name()),
                                        cruiseControl.generateCertificatesSecret(reconciliation.namespace(), reconciliation.name(), clusterCa, Util.isMaintenanceTimeWindowsSatisfied(reconciliation, maintenanceWindows, clock.instant())))
                                .compose(patchResult -> {
                                    if (patchResult instanceof ReconcileResult.Patched) {
                                        // The secret is patched and some changes to the existing certificates actually occurred
                                        existingCertsChanged = CertUtils.doExistingCertificatesDiffer(oldSecret, patchResult.resource());
                                    } else {
                                        existingCertsChanged = false;
                                    }

                                    return Future.succeededFuture();
                                });
                    });
        } else {
            return secretOperator.reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.secretName(reconciliation.name()), null)
                    .map((Void) null);
        }
    }

    /**
     * Manages the Cruise Control API keys secret.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> apiSecret() {
        if (cruiseControl != null) {
            return secretOperator.getAsync(reconciliation.namespace(), CruiseControlResources.apiSecretName(reconciliation.name()))
                    .compose(oldSecret -> {
                        Secret newSecret = cruiseControl.generateApiSecret();

                        if (oldSecret != null)  {
                            // The credentials should not change with every release
                            // So if the secret with credentials already exists, we re-use the values
                            // But we use the new secret to update labels etc. if needed
                            newSecret.setData(oldSecret.getData());
                        }

                        return secretOperator.reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.apiSecretName(reconciliation.name()), newSecret)
                                .map((Void) null);
                    });
        } else {
            return secretOperator.reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.apiSecretName(reconciliation.name()), null)
                    .map((Void) null);
        }
    }

    /**
     * Manages the Cruise Control Service.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> service() {
        return serviceOperator
                .reconcile(
                        reconciliation,
                        reconciliation.namespace(),
                        CruiseControlResources.serviceName(reconciliation.name()),
                        cruiseControl != null ? cruiseControl.generateService() : null
                ).map((Void) null);
    }

    /**
     * Manages the Cruise Control Deployment.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> deployment(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        if (cruiseControl != null) {
            Map<String, String> podAnnotations = new LinkedHashMap<>();
            podAnnotations.put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION, String.valueOf(clusterCa.caCertGeneration()));
            podAnnotations.put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_KEY_GENERATION, String.valueOf(clusterCa.caKeyGeneration()));
            podAnnotations.put(CruiseControl.ANNO_STRIMZI_SERVER_CONFIGURATION_HASH, serverConfigurationHash);
            podAnnotations.put(CruiseControl.ANNO_STRIMZI_CAPACITY_CONFIGURATION_HASH, capacityConfigurationHash);

            Deployment deployment = cruiseControl.generateDeployment(podAnnotations, isOpenShift, imagePullPolicy, imagePullSecrets);

            return deploymentOperator
                    .reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.componentName(reconciliation.name()), deployment)
                    .compose(patchResult -> {
                        if (patchResult instanceof ReconcileResult.Noop)   {
                            // Deployment needs ot be rolled because the certificate secret changed or older/expired cluster CA removed
                            if (existingCertsChanged || clusterCa.certsRemoved()) {
                                LOGGER.infoCr(reconciliation, "Rolling Cruise Control to update or remove certificates");
                                return cruiseControlRollingUpdate();
                            }
                        }

                        // No need to roll, we patched the deployment (and it will roll itself) or we created a new one
                        return Future.succeededFuture();
                    });
        } else {
            return deploymentOperator.reconcile(reconciliation, reconciliation.namespace(), CruiseControlResources.componentName(reconciliation.name()), null)
                    .map((Void) null);
        }
    }

    /**
     * Triggers the rolling update of the Cruise Control. This is used to trigger the roll when the certificates change.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> cruiseControlRollingUpdate() {
        return deploymentOperator.rollingUpdate(reconciliation, reconciliation.namespace(), CruiseControlResources.componentName(reconciliation.name()), operationTimeoutMs);
    }

    /**
     * Waits for the Cruise Control deployment to finish any rolling and get ready.
     *
     * @return  Future which completes when the reconciliation is done
     */
    protected Future<Void> waitForDeploymentReadiness() {
        if (cruiseControl != null) {
            return deploymentOperator.waitForObserved(reconciliation, reconciliation.namespace(), CruiseControlResources.componentName(reconciliation.name()), 1_000, operationTimeoutMs)
                    .compose(i -> deploymentOperator.readiness(reconciliation, reconciliation.namespace(), CruiseControlResources.componentName(reconciliation.name()), 1_000, operationTimeoutMs));
        } else {
            return Future.succeededFuture();
        }
    }
}
