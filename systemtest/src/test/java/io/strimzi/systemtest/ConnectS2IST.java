/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.strimzi.api.kafka.model.KafkaConnectS2IResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.utils.StUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@OpenShiftOnly
@Tag(REGRESSION)
class ConnectS2IST extends AbstractST {

    public static final String NAMESPACE = "connect-s2i-cluster-test";
    private static final Logger LOGGER = LogManager.getLogger(ConnectS2IST.class);
    private static final String CONNECT_S2I_TOPIC_NAME = "connect-s2i-topic-example";

    @Test
    void testDeployS2IWithMongoDBPlugin() {
        testMethodResources().kafkaEphemeral(CLUSTER_NAME, 3, 1).done();

        final String kafkaConnectS2IName = "kafka-connect-s2i-name-1";

        testMethodResources().kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
            .endMetadata()
            .done();

        Map<String, String> connectSnapshot = StUtils.depConfigSnapshot(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName));

        File dir = StUtils.downloadAndUnzip("https://repo1.maven.org/maven2/io/debezium/debezium-connector-mongodb/0.7.5/debezium-connector-mongodb-0.7.5-plugin.zip");

        // Start a new image build using the plugins directory
        cmdKubeClient().exec("oc", "start-build", KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), "--from-dir", dir.getAbsolutePath(), "-n", NAMESPACE);
        // Wait for rolling update connect pods
        StUtils.waitTillDepConfigHasRolled(kafkaConnectS2IName, connectSnapshot);
        String connectS2IPodName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        LOGGER.info("Collect plugins information from connect s2i pod");
        String plugins = cmdKubeClient().execInPod(connectS2IPodName, "curl", "-X", "GET", "http://localhost:8083/connector-plugins").out();

        assertThat(plugins, containsString("io.debezium.connector.mongodb.MongoDbConnector"));
    }

    @Test
    @Tag(NODEPORT_SUPPORTED)
    void testSecretsWithKafkaConnectS2IWithTlsAndScramShaAuthentication() throws Exception {
        testMethodResources().kafkaEphemeral(CLUSTER_NAME, 3, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewTls()
                            .withNewKafkaListenerAuthenticationScramSha512Auth()
                            .endKafkaListenerAuthenticationScramSha512Auth()
                        .endTls()
                        .withNewKafkaListenerExternalNodePort()
                            .withNewKafkaListenerAuthenticationScramSha512Auth()
                            .endKafkaListenerAuthenticationScramSha512Auth()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
            .endSpec()
            .done();


        final String userName = "user-example-one";
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-2";

        testMethodResources().scramShaUser(CLUSTER_NAME, userName).done();

        StUtils.waitForSecretReady(userName);

        testMethodResources().kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1)
                .editMetadata()
                    .addToLabels("type", "kafka-connect-s2i")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .withNewTls()
                        .addNewTrustedCertificate()
                            .withSecretName(KafkaResources.clusterCaCertificateSecretName(CLUSTER_NAME))
                            .withCertificate("ca.crt")
                        .endTrustedCertificate()
                    .endTls()
                    .withBootstrapServers(KafkaResources.tlsBootstrapAddress(CLUSTER_NAME))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(userName)
                        .withNewPasswordSecret()
                            .withSecretName(userName)
                            .withPassword("password")
                        .endPasswordSecret()
                    .endKafkaClientAuthenticationScramSha512()
                .endSpec()
                .done();

        testMethodResources().topic(CLUSTER_NAME, CONNECT_S2I_TOPIC_NAME).done();

        String kafkaConnectS2IPodName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        String kafkaConnectS2ILogs = kubeClient().logs(kafkaConnectS2IPodName);

        LOGGER.info("Verifying that in kafka connect logs are everything fine");
        assertThat(kafkaConnectS2ILogs, not(containsString("ERROR")));

        LOGGER.info("Creating FileStreamSink connector in pod {} with topic {}", kafkaConnectS2IPodName, CONNECT_S2I_TOPIC_NAME);
        StUtils.createFileSinkConnector(kafkaConnectS2IPodName, CONNECT_S2I_TOPIC_NAME);

        waitForClusterAvailabilityScramSha(userName, NAMESPACE, CLUSTER_NAME, CONNECT_S2I_TOPIC_NAME, 2);

        StUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectS2IPodName);

        assertThat(cmdKubeClient().execInPod(kafkaConnectS2IPodName, "/bin/bash", "-c", "cat /tmp/test-file-sink.txt").out(),
                containsString("0\n1\n"));
    }

    @Test
    void testCustomAndUpdatedValues() {
        final String kafkaConnectS2IName = "kafka-connect-s2i-name-3";
        testMethodResources().kafkaEphemeral(CLUSTER_NAME, 3, 1).done();

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");

        testMethodResources().kafkaConnectS2I(kafkaConnectS2IName, CLUSTER_NAME, 1)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
            .endMetadata()
            .editSpec()
                .withNewTemplate()
                    .withNewConnectContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endConnectContainer()
                .endTemplate()
            .endSpec()
            .done();

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, String> connectSnapshot = StUtils.depConfigSnapshot(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName));

        LOGGER.info("Verify values before update");

        LabelSelector deploymentConfigSelector = new LabelSelectorBuilder().addToMatchLabels(kubeClient().getDeploymentConfigSelectors(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName))).build();
        String connectPodName = kubeClient().listPods(deploymentConfigSelector).get(0).getMetadata().getName();

        checkSpecificVariablesInContainer(connectPodName, KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), envVarGeneral);

        LOGGER.info("Updating values in ConnectS2I container");
        replaceConnectS2IResource(kafkaConnectS2IName, kc -> {
            kc.getSpec().getTemplate().getConnectContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
        });

        StUtils.waitTillDepConfigHasRolled(kafkaConnectS2IName, connectSnapshot);

        deploymentConfigSelector = new LabelSelectorBuilder().addToMatchLabels(kubeClient().getDeploymentConfigSelectors(KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName))).build();
        connectPodName = kubeClient().listPods(deploymentConfigSelector).get(0).getMetadata().getName();

        LOGGER.info("Verify values after update");
        checkSpecificVariablesInContainer(connectPodName, KafkaConnectS2IResources.deploymentName(kafkaConnectS2IName), envVarUpdated);
    }


    @BeforeEach
    void createTestResources() {
        createTestMethodResources();
    }

    @AfterEach
    void deleteTestResources() {
        deleteTestMethodResources();
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(NAMESPACE);

        createTestClassResources();
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        testClassResources().clusterOperator(NAMESPACE, Constants.CO_OPERATION_TIMEOUT_DEFAULT,  Constants.RECONCILIATION_INTERVAL).done();
    }
}
