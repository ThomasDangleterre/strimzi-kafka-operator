/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.KafkaList;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.api.kafka.model.kafka.KafkaStatus;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.kafka.UsedNodePoolStatus;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePoolList;
import io.strimzi.api.kafka.model.nodepool.ProcessRoles;
import io.strimzi.api.kafka.model.podset.StrimziPodSet;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaPool;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.KafkaVersionChange;
import io.strimzi.operator.cluster.model.MetricsAndLogging;
import io.strimzi.operator.cluster.model.MockSharedEnvironmentProvider;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.model.PodSetUtils;
import io.strimzi.operator.cluster.model.RestartReason;
import io.strimzi.operator.cluster.model.RestartReasons;
import io.strimzi.operator.cluster.model.SharedEnvironmentProvider;
import io.strimzi.operator.cluster.model.nodepools.NodePoolUtils;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.StatefulSetOperator;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.InvalidConfigurationException;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.ClientsCa;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.PasswordGenerator;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.common.operator.resource.StrimziPodSetOperator;
import io.strimzi.platform.KubernetesVersion;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

/**
 * Tests in this class mostly mirror the tests in KafkaAssemblyOperatorPodSetTest but using KafkaNodePools instead of just the
 * virtual node pool. In addition, they add some node pool only tests such as adding and removing pools or updating their statuses.
 */
@ExtendWith(VertxExtension.class)
@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling", "checkstyle:ClassFanOutComplexity"})
public class KafkaAssemblyOperatorWithKRaftTest {
    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    private static final SharedEnvironmentProvider SHARED_ENV_PROVIDER = new MockSharedEnvironmentProvider();
    private static final ClusterOperatorConfig CONFIG = ResourceUtils.dummyClusterOperatorConfig();
    private static final KubernetesVersion KUBERNETES_VERSION = KubernetesVersion.MINIMAL_SUPPORTED_VERSION;
    private static final MockCertManager CERT_MANAGER = new MockCertManager();
    private static final PasswordGenerator PASSWORD_GENERATOR = new PasswordGenerator(10, "a", "a");
    private final static KafkaVersionChange VERSION_CHANGE = new KafkaVersionChange(
            VERSIONS.defaultVersion(),
            VERSIONS.defaultVersion(),
            VERSIONS.defaultVersion().protocolVersion(),
            VERSIONS.defaultVersion().messageVersion(),
            VERSIONS.defaultVersion().metadataVersion()
    );
    private static final String NAMESPACE = "my-ns";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final Kafka KAFKA = new KafkaBuilder()
                .withNewMetadata()
                    .withName(CLUSTER_NAME)
                    .withNamespace(NAMESPACE)
                    .withAnnotations(Map.of(Annotations.ANNO_STRIMZI_IO_NODE_POOLS, "enabled", Annotations.ANNO_STRIMZI_IO_KRAFT, "enabled"))
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withListeners(new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build())
                    .endKafka()
                .endSpec()
                .build();

    private final static KafkaNodePool CONTROLLERS = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName("controllers")
                .withNamespace(NAMESPACE)
                .withGeneration(1L)
                .withLabels(Map.of(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withNewJbodStorage()
                    .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("100Gi").build())
                .endJbodStorage()
                .withRoles(ProcessRoles.CONTROLLER)
                .withResources(new ResourceRequirementsBuilder().withRequests(Map.of("cpu", new Quantity("4"))).build())
            .endSpec()
            .build();
    private final static KafkaNodePool BROKERS = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName("brokers")
                .withNamespace(NAMESPACE)
                .withGeneration(1L)
                .withLabels(Map.of(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withNewJbodStorage()
                    .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("200Gi").build())
                .endJbodStorage()
                .withRoles(ProcessRoles.BROKER)
                .withResources(new ResourceRequirementsBuilder().withRequests(Map.of("cpu", new Quantity("6"))).build())
            .endSpec()
            .build();
    private static final List<KafkaPool> POOLS = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, KAFKA, List.of(CONTROLLERS, BROKERS), Map.of(), Map.of(), true, SHARED_ENV_PROVIDER);
    private static final KafkaCluster KAFKA_CLUSTER = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, POOLS, VERSIONS, KafkaVersionTestUtils.DEFAULT_KRAFT_VERSION_CHANGE, true, null, SHARED_ENV_PROVIDER);

    private static final Map<Integer, Map<String, String>> ADVERTISED_HOSTNAMES = Map.of(
            0, Map.of("PLAIN_9092", "broker-0"),
            1, Map.of("PLAIN_9092", "broker-1"),
            2, Map.of("PLAIN_9092", "broker-2"),
            3, Map.of("PLAIN_9092", "broker-3"),
            4, Map.of("PLAIN_9092", "broker-4"),
            5, Map.of("PLAIN_9092", "broker-5"),
            6, Map.of("PLAIN_9092", "broker-6"),
            7, Map.of("PLAIN_9092", "broker-7")
    );

    private static final Map<Integer, Map<String, String>> ADVERTISED_PORTS = Map.of(
            0, Map.of("PLAIN_9092", "10000"),
            1, Map.of("PLAIN_9092", "10001"),
            2, Map.of("PLAIN_9092", "10002"),
            3, Map.of("PLAIN_9092", "10003"),
            4, Map.of("PLAIN_9092", "10004"),
            5, Map.of("PLAIN_9092", "10005"),
            6, Map.of("PLAIN_9092", "10006"),
            7, Map.of("PLAIN_9092", "10007")
    );

    private final static ClusterCa CLUSTER_CA = new ClusterCa(
            Reconciliation.DUMMY_RECONCILIATION,
            CERT_MANAGER,
            PASSWORD_GENERATOR,
            CLUSTER_NAME,
            ResourceUtils.createInitialCaCertSecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaCertSecretName(CLUSTER_NAME), MockCertManager.clusterCaCert(), MockCertManager.clusterCaCertStore(), "123456"),
            ResourceUtils.createInitialCaKeySecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaKeySecretName(CLUSTER_NAME), MockCertManager.clusterCaKey())
    );

    private final static ClientsCa CLIENTS_CA = new ClientsCa(
            Reconciliation.DUMMY_RECONCILIATION,
            new OpenSslCertManager(),
            new PasswordGenerator(10, "a", "a"),
            KafkaResources.clientsCaCertificateSecretName(CLUSTER_NAME),
            ResourceUtils.createInitialCaCertSecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaCertSecretName(CLUSTER_NAME), MockCertManager.clusterCaCert(), MockCertManager.clusterCaCertStore(), "123456"),
            KafkaResources.clientsCaKeySecretName(CLUSTER_NAME),
            ResourceUtils.createInitialCaKeySecret(NAMESPACE, CLUSTER_NAME, AbstractModel.clusterCaKeySecretName(CLUSTER_NAME), MockCertManager.clusterCaKey()),
            365,
            30,
            true,
            null
    );

    protected static Vertx vertx;
    private static WorkerExecutor sharedWorkerExecutor;

    @BeforeAll
    public static void beforeAll() {
        vertx = Vertx.vertx();
        sharedWorkerExecutor = vertx.createSharedWorkerExecutor("kubernetes-ops-pool");
    }

    @AfterAll
    public static void afterAll() {
        sharedWorkerExecutor.close();
        vertx.close();
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster when the UseStrimziPodsSet is already enabled for some time
     *
     * @param context   Test context
     */
    @Test
    public void testRegularReconciliation(VertxTestContext context)  {
        List<StrimziPodSet> kafkaPodSets = KAFKA_CLUSTER.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of()));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(kafkaPodSets));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                StrimziPodSet patched = kafkaPodSets.stream().filter(sps -> podSet.getMetadata().getName().equals(sps.getMetadata().getName())).findFirst().orElse(null);
                result.put(podSet.getMetadata().getName(), patched == null ? ReconcileResult.created(podSet) : ReconcileResult.noop(patched));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        ArgumentCaptor<KafkaNodePool> kafkaNodePoolStatusCaptor = ArgumentCaptor.forClass(KafkaNodePool.class);
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), kafkaNodePoolStatusCaptor.capture())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-0")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-1")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-2")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-3")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-4")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-5")), is(RestartReasons.empty()));

                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(7));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-kafka-config")));

                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    // Check statuses
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().size(), is(2));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getNodeIds(), is(List.of(0, 1, 2)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles(), hasItems(ProcessRoles.CONTROLLER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getNodeIds(), is(List.of(3, 4, 5)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kao.state.kafkaStatus.getKafkaNodePools().stream().map(UsedNodePoolStatus::getName).toList(), is(List.of("brokers", "controllers")));

                    async.flag();
                })));
    }

    /**
     * Tests the first reconciliation of the Kafka cluster after the UseStrimziPodsSet is enabled for the first time
     *
     * @param context   Test context
     */
    @Test
    public void testFirstReconciliation(VertxTestContext context)  {
        List<StrimziPodSet> kafkaPodSets = KAFKA_CLUSTER.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(KAFKA_CLUSTER.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(List.of()));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                StrimziPodSet patched = kafkaPodSets.stream().filter(sps -> podSet.getMetadata().getName().equals(sps.getMetadata().getName())).findFirst().orElse(null);
                result.put(podSet.getMetadata().getName(), ReconcileResult.created(patched));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        // Kafka
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(new StatefulSetBuilder().withNewMetadata().withName(KAFKA_CLUSTER.getComponentName()).withNamespace(NAMESPACE).endMetadata().build()));
        when(mockStsOps.deleteAsync(any(), any(), eq(KAFKA_CLUSTER.getComponentName()), eq(false))).thenReturn(Future.succeededFuture()); // The Kafka STS will be deleted during the reconciliation

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {

                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-0")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-1")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(0), "my-cluster-controllers-2")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-3")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-4")), is(RestartReasons.empty()));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(kafkaPodSets.get(1), "my-cluster-brokers-5")), is(RestartReasons.empty()));

                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(7));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-kafka-config")));

                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    async.flag();
                })));
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster which results in some rolling updates
     *
     * @param context   Test context
     */
    @Test
    public void testReconciliationWithRollDueToImageChange(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editKafka()
                        .withImage("old-image:latest")
                    .endKafka()
                .endSpec()
                .build();

        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, POOLS, VERSIONS, KafkaVersionTestUtils.DEFAULT_KRAFT_VERSION_CHANGE, true, null, SHARED_ENV_PROVIDER);
        List<StrimziPodSet> oldKafkaPodSets = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        when(mockCmOps.reconcile(any(), any(), startsWith("my-cluster-"), any())).thenReturn(Future.succeededFuture());
        when(mockCmOps.deleteAsync(any(), any(), eq("my-cluster-kafka-config"), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaPodSets));
        when(mockPodSetOps.batchReconcile(any(), any(), any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(kr.maybeRollKafkaInvocations, is(1));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(0), "my-cluster-controllers-0")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(0), "my-cluster-controllers-1")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(0), "my-cluster-controllers-2")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(1), "my-cluster-brokers-3")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(1), "my-cluster-brokers-4")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));
                    assertThat(kr.kafkaPodNeedsRestart.apply(podFromPodSet(oldKafkaPodSets.get(1), "my-cluster-brokers-5")), is(RestartReasons.of(RestartReason.POD_HAS_OLD_REVISION)));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-up from 2 to 3 Kafka brokers
     *
     * @param context   Test context
     */
    @Test
    @SuppressWarnings({"checkstyle:MethodLength"})
    public void testScaleUp(VertxTestContext context)  {
        KafkaNodePool oldBrokersPool = new KafkaNodePoolBuilder(BROKERS)
                .editSpec()
                    .withReplicas(2)
                .endSpec()
                .build();

        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, KAFKA, List.of(CONTROLLERS, oldBrokersPool), Map.of(), Map.of(), true, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, oldPools, VERSIONS, KafkaVersionTestUtils.DEFAULT_KRAFT_VERSION_CHANGE, true, null, SHARED_ENV_PROVIDER);
        List<StrimziPodSet> oldKafkaPodSets = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaPodSets));
        @SuppressWarnings({ "unchecked" })
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        ArgumentCaptor<KafkaNodePool> kafkaNodePoolStatusCaptor = ArgumentCaptor.forClass(KafkaNodePool.class);
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), kafkaNodePoolStatusCaptor.capture())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-up of Kafka is done in one go => we should see two invocations from regular patching
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(1).getSpec().getPods().size(), is(3));

                    // Still one maybe-roll invocation
                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all pods are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(7));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-kafka-config")));

                    // Only the shared CM is deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    // Check statuses
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().size(), is(2));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getNodeIds(), is(List.of(0, 1, 2)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles(), hasItems(ProcessRoles.CONTROLLER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getNodeIds(), is(List.of(3, 4, 5)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kao.state.kafkaStatus.getKafkaNodePools().stream().map(UsedNodePoolStatus::getName).toList(), is(List.of("brokers", "controllers")));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-down from 5 to 3 Kafka brokers
     *
     * @param context   Test context
     */
    @Test
    @SuppressWarnings({"checkstyle:MethodLength"})
    public void testScaleDown(VertxTestContext context)  {
        KafkaNodePool oldBrokersPool = new KafkaNodePoolBuilder(BROKERS)
                .editSpec()
                    .withReplicas(5)
                .endSpec()
                .build();

        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, KAFKA, List.of(CONTROLLERS, oldBrokersPool), Map.of(), Map.of(), true, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, oldPools, VERSIONS, KafkaVersionTestUtils.DEFAULT_KRAFT_VERSION_CHANGE, true, null, SHARED_ENV_PROVIDER);
        List<StrimziPodSet> oldKafkaPodSets = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaPodSets));
        @SuppressWarnings({ "unchecked" })
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });
        ArgumentCaptor<StrimziPodSet> kafkaPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), startsWith("my-cluster-brokers"), kafkaPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockPodOps.waitFor(any(), any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        ArgumentCaptor<KafkaNodePool> kafkaNodePoolStatusCaptor = ArgumentCaptor.forClass(KafkaNodePool.class);
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), kafkaNodePoolStatusCaptor.capture())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-down of Kafka is done in one go => we should see two invocations (first from scale-down and second from regular patching)
                    assertThat(kafkaPodSetCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(3)); // => first capture is from kafkaScaleDown() with new replica count
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3)); // => The unchanged controllers pool
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(1).getSpec().getPods().size(), is(3)); // => second capture is from kafkaPodSet() again with new replica count

                    // Still one maybe-roll invocation
                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all remaining pods + the old shared config CM are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(7));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-kafka-config")));

                    // The  CMs for scaled down pods are deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(2));
                    assertThat(cmDeletionCaptor.getAllValues(), is(List.of("my-cluster-brokers-6", "my-cluster-brokers-7")));

                    // Check statuses
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().size(), is(2));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getNodeIds(), is(List.of(0, 1, 2)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles(), hasItems(ProcessRoles.CONTROLLER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getNodeIds(), is(List.of(3, 4, 5)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kao.state.kafkaStatus.getKafkaNodePools().stream().map(UsedNodePoolStatus::getName).toList(), is(List.of("brokers", "controllers")));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with newly added Kafka pool
     *
     * @param context   Test context
     */
    @Test
    @SuppressWarnings({"checkstyle:MethodLength"})
    public void testNewPool(VertxTestContext context)  {
        KafkaNodePool newPool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName("new")
                .withNamespace(NAMESPACE)
                .withGeneration(1L)
                .withLabels(Map.of(Labels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME))
            .endMetadata()
            .withNewSpec()
                .withReplicas(2)
                .withNewJbodStorage()
                    .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("300Gi").build())
                .endJbodStorage()
                .withRoles(ProcessRoles.BROKER)
            .endSpec()
            .build();

        List<StrimziPodSet> kafkaPodSets = KAFKA_CLUSTER.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(KAFKA_CLUSTER.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(kafkaPodSets));
        @SuppressWarnings({ "unchecked" })
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                StrimziPodSet patched = kafkaPodSets.stream().filter(sps -> podSet.getMetadata().getName().equals(sps.getMetadata().getName())).findFirst().orElse(null);
                result.put(podSet.getMetadata().getName(), patched == null ? ReconcileResult.created(podSet) : ReconcileResult.noop(patched));
            }

            return Future.succeededFuture(result);
        });

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        ArgumentCaptor<KafkaNodePool> kafkaNodePoolStatusCaptor = ArgumentCaptor.forClass(KafkaNodePool.class);
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), kafkaNodePoolStatusCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaCluster kafkaCluster = KafkaClusterCreator.createKafkaCluster(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                KAFKA,
                List.of(CONTROLLERS, BROKERS, newPool),
                Map.of(),
                Map.of(),
                VERSION_CHANGE,
                true,
                VERSIONS,
                supplier.sharedEnvironmentProvider);
        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS, newPool),
                kafkaCluster,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).size(), is(3)); // Number of PodSets
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(1).getSpec().getPods().size(), is(3));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(2).getSpec().getPods().size(), is(2));

                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all pods are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(9));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-new-6", "my-cluster-new-7", "my-cluster-kafka-config")));

                    // Only the shared CM is deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(0));

                    // Check statuses
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().size(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getNodeIds(), is(List.of(0, 1, 2)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles(), hasItems(ProcessRoles.CONTROLLER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getNodeIds(), is(List.of(3, 4, 5)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(2).getStatus().getReplicas(), is(2));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(2).getStatus().getNodeIds(), is(List.of(6, 7)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(2).getStatus().getObservedGeneration(), is(1L));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(2).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(2).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kao.state.kafkaStatus.getKafkaNodePools().stream().map(UsedNodePoolStatus::getName).toList(), is(List.of("brokers", "controllers", "new")));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation when Kafka pool is removed
     *
     * @param context   Test context
     */
    @Test
    @SuppressWarnings({"checkstyle:MethodLength"})
    public void testRemovePool(VertxTestContext context)  {
        KafkaNodePool newPool = new KafkaNodePoolBuilder()
            .withNewMetadata()
                .withName("new")
                .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withReplicas(2)
                .withNewJbodStorage()
                    .withVolumes(new PersistentClaimStorageBuilder().withId(0).withSize("300Gi").build())
                .endJbodStorage()
                .withRoles(ProcessRoles.BROKER)
            .endSpec()
            .build();

        List<KafkaPool> oldPools = NodePoolUtils.createKafkaPools(Reconciliation.DUMMY_RECONCILIATION, KAFKA, List.of(CONTROLLERS, BROKERS, newPool), Map.of(), Map.of(), true, SHARED_ENV_PROVIDER);
        KafkaCluster oldKafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, oldPools, VERSIONS, KafkaVersionTestUtils.DEFAULT_KRAFT_VERSION_CHANGE, true, null, SHARED_ENV_PROVIDER);
        List<StrimziPodSet> oldKafkaPodSets = oldKafkaCluster.generatePodSets(false, null, null, brokerId -> null);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        ConfigMapOperator mockCmOps = supplier.configMapOperations;
        when(mockCmOps.listAsync(any(), eq(oldKafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaCluster.generatePerBrokerConfigurationConfigMaps(new MetricsAndLogging(null, null), ADVERTISED_HOSTNAMES, ADVERTISED_PORTS)));
        ArgumentCaptor<String> cmReconciliationCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.reconcile(any(), any(), cmReconciliationCaptor.capture(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> cmDeletionCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCmOps.deleteAsync(any(), any(), cmDeletionCaptor.capture(), anyBoolean())).thenReturn(Future.succeededFuture());

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        // Kafka
        when(mockPodSetOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(oldKafkaPodSets));
        @SuppressWarnings({ "unchecked" })
        ArgumentCaptor<List<StrimziPodSet>> kafkaPodSetBatchCaptor =  ArgumentCaptor.forClass(List.class);
        when(mockPodSetOps.batchReconcile(any(), any(), kafkaPodSetBatchCaptor.capture(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenAnswer(i -> {
            List<StrimziPodSet> podSets = i.getArgument(2);
            HashMap<String, ReconcileResult<StrimziPodSet>> result = new HashMap<>();

            for (StrimziPodSet podSet : podSets)    {
                result.put(podSet.getMetadata().getName(), ReconcileResult.noop(podSet));
            }

            return Future.succeededFuture(result);
        });
        ArgumentCaptor<StrimziPodSet> kafkaPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), startsWith("my-cluster-new"), kafkaPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null)); // Kafka STS is queried and deleted if it still exists

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());
        when(mockPodOps.waitFor(any(), any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        ArgumentCaptor<KafkaNodePool> kafkaNodePoolStatusCaptor = ArgumentCaptor.forClass(KafkaNodePool.class);
        when(mockKafkaNodePoolOps.updateStatusAsync(any(), kafkaNodePoolStatusCaptor.capture())).thenReturn(Future.succeededFuture());

        MockKafkaReconciler kr = new MockKafkaReconciler(
                new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME),
                vertx,
                CONFIG,
                supplier,
                new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                KAFKA,
                List.of(CONTROLLERS, BROKERS),
                KAFKA_CLUSTER,
                CLUSTER_CA,
                CLIENTS_CA);

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                CONFIG,
                kr);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-down of Kafka is done in one go => we should see two invocations (first from scale-down and second from regular patching)
                    assertThat(kafkaPodSetCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(0)); // => The removed pool is first scaled to 0
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().size(), is(1));
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).size(), is(2)); // Number of PodSets
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(0).getSpec().getPods().size(), is(3)); // => The unchanged controllers pool
                    assertThat(kafkaPodSetBatchCaptor.getAllValues().get(0).get(1).getSpec().getPods().size(), is(3)); // => The unchanged brokers pool

                    // Still one maybe-roll invocation
                    assertThat(kr.maybeRollKafkaInvocations, is(1));

                    // CMs for all remaining pods + the old shared config CM are reconciled
                    assertThat(cmReconciliationCaptor.getAllValues().size(), is(7));
                    assertThat(cmReconciliationCaptor.getAllValues(), is(List.of("my-cluster-controllers-0", "my-cluster-controllers-1", "my-cluster-controllers-2", "my-cluster-brokers-3", "my-cluster-brokers-4", "my-cluster-brokers-5", "my-cluster-kafka-config")));

                    // The  CMs for scaled down pods are deleted
                    assertThat(cmDeletionCaptor.getAllValues().size(), is(2));
                    assertThat(cmDeletionCaptor.getAllValues(), is(List.of("my-cluster-new-6", "my-cluster-new-7")));

                    // Check statuses
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().size(), is(2));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getNodeIds(), is(List.of(0, 1, 2)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(0).getStatus().getRoles(), hasItems(ProcessRoles.CONTROLLER));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getReplicas(), is(3));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getNodeIds(), is(List.of(3, 4, 5)));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles().size(), is(1));
                    assertThat(kafkaNodePoolStatusCaptor.getAllValues().get(1).getStatus().getRoles(), hasItems(ProcessRoles.BROKER));
                    assertThat(kao.state.kafkaStatus.getKafkaNodePools().stream().map(UsedNodePoolStatus::getName).toList(), is(List.of("brokers", "controllers")));

                    async.flag();
                })));
    }

    /**
     * Tests that KRaft cluster cannot be deployed without using NodePools
     *
     * @param context   Test context
     */
    @Test
    public void testKRaftClusterWithoutNodePools(VertxTestContext context)  {
        Kafka kafka = new KafkaBuilder(KAFKA)
                .editMetadata()
                    .withAnnotations(Map.of(Annotations.ANNO_STRIMZI_IO_KRAFT, "enabled"))
                .endMetadata()
                .editSpec()
                    .withZookeeper(null)
                .endSpec()
                .build();

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(kafka));

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        KafkaAssemblyOperator kao = new KafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.failing(v -> context.verify(() -> {
                    assertThat(v, instanceOf(InvalidConfigurationException.class));
                    assertThat(v.getMessage(), is("The UseKRaft feature gate can be used only together with a Kafka cluster based on the KafkaNodePool resources."));
                    async.flag();
                })));
    }

    /**
     * Tests that InvalidConfigurationException is thrown when no KafkaNodePool resource is found
     *
     * @param context   Test context
     */
    @Test
    public void testNoNodePoolsValidation(VertxTestContext context)  {
        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(List.of()));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator<KubernetesClient, Kafka, KafkaList> mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null));

        StrimziPodSetOperator mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.listAsync(eq(NAMESPACE), eq(KAFKA_CLUSTER.getSelectorLabels()))).thenReturn(Future.succeededFuture(null));
        when(mockPodSetOps.getAsync(any(), eq(KAFKA_CLUSTER.getComponentName()))).thenReturn(Future.succeededFuture(null));

        CrdOperator<KubernetesClient, KafkaNodePool, KafkaNodePoolList> mockKafkaNodePoolOps = supplier.kafkaNodePoolOperator;
        when(mockKafkaNodePoolOps.listAsync(any(), any(Labels.class))).thenReturn(Future.succeededFuture(null));

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);

        KafkaAssemblyOperator kao = new KafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();

        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.failing(v -> context.verify(() -> {
                    assertThat(v, instanceOf(InvalidConfigurationException.class));
                    assertThat(v.getMessage(), is("KafkaNodePools are enabled, but no KafkaNodePools found for Kafka cluster my-cluster"));
                    async.flag();
                })));
    }

    // Internal utility methods
    private Pod podFromPodSet(StrimziPodSet podSet, String name) {
        return PodSetUtils.podSetToPods(podSet).stream().filter(p -> name.equals(p.getMetadata().getName())).findFirst().orElse(null);
    }

    static class MockKafkaAssemblyOperator extends KafkaAssemblyOperator  {
        KafkaReconciler mockKafkaReconciler;
        ReconciliationState state;

        public MockKafkaAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa, CertManager certManager, PasswordGenerator passwordGenerator, ResourceOperatorSupplier supplier, ClusterOperatorConfig config, KafkaReconciler mockKafkaReconciler) {
            super(vertx, pfa, certManager, passwordGenerator, supplier, config);
            this.mockKafkaReconciler = mockKafkaReconciler;
        }

        ReconciliationState createReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            state = new MockReconciliationState(reconciliation, kafkaAssembly);
            return state;
        }

        class MockReconciliationState extends ReconciliationState {
            MockReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
                super(reconciliation, kafkaAssembly);
            }

            @Override
            Future<KafkaReconciler> kafkaReconciler()    {
                return Future.succeededFuture(mockKafkaReconciler);
            }

            @Override
            Future<ReconciliationState> reconcileEntityOperator(Clock clock)    {
                return Future.succeededFuture(this);
            }

            @Override
            Future<ReconciliationState> reconcileCruiseControl(Clock clock)    {
                return Future.succeededFuture(this);
            }

            @Override
            Future<ReconciliationState> reconcileKafkaExporter(Clock clock)    {
                return Future.succeededFuture(this);
            }
        }
    }

    static class MockKafkaReconciler extends KafkaReconciler   {
        int maybeRollKafkaInvocations = 0;
        Function<Pod, RestartReasons> kafkaPodNeedsRestart = null;

        public MockKafkaReconciler(Reconciliation reconciliation, Vertx vertx, ClusterOperatorConfig config, ResourceOperatorSupplier supplier, PlatformFeaturesAvailability pfa, Kafka kafkaAssembly, List<KafkaNodePool> nodePools, KafkaCluster kafkaCluster, ClusterCa clusterCa, ClientsCa clientsCa) {
            super(reconciliation, kafkaAssembly, nodePools, kafkaCluster, clusterCa, clientsCa, config, supplier, pfa, vertx);
        }

        @Override
        public Future<Void> reconcile(KafkaStatus kafkaStatus, Clock clock)    {
            return manualPodCleaning()
                    .compose(i -> manualRollingUpdate())
                    .compose(i -> scaleDown())
                    .compose(i -> updateNodePoolStatuses(kafkaStatus))
                    .compose(i -> listeners())
                    .compose(i -> brokerConfigurationConfigMaps())
                    .compose(i -> migrateFromStatefulSetToPodSet())
                    .compose(i -> podSet())
                    .compose(this::rollingUpdate)
                    .compose(i -> sharedKafkaConfigurationCleanup());
        }

        @Override
        protected Future<Void> maybeRollKafka(
                Set<NodeRef> nodes,
                Function<Pod, RestartReasons> podNeedsRestart,
                Map<Integer, Map<String, String>> kafkaAdvertisedHostnames,
                Map<Integer, Map<String, String>> kafkaAdvertisedPorts,
                boolean allowReconfiguration
        ) {
            maybeRollKafkaInvocations++;
            kafkaPodNeedsRestart = podNeedsRestart;
            return Future.succeededFuture();
        }

        @Override
        protected Future<Void> listeners()  {
            listenerReconciliationResults = new KafkaListenersReconciler.ReconciliationResult();
            listenerReconciliationResults.advertisedHostnames.putAll(ADVERTISED_HOSTNAMES);
            listenerReconciliationResults.advertisedPorts.putAll(ADVERTISED_PORTS);

            return Future.succeededFuture();
        }
    }
}
