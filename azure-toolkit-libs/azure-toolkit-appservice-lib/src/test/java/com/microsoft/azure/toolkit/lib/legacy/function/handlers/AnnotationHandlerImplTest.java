/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.legacy.function.handlers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.junit.Assert;
import org.junit.Test;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.BlobInput;
import com.microsoft.azure.functions.annotation.BlobOutput;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.Cardinality;
import com.microsoft.azure.functions.annotation.CosmosDBOutput;
import com.microsoft.azure.functions.annotation.CosmosDBTrigger;
import com.microsoft.azure.functions.annotation.CustomBinding;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.EventHubOutput;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpOutput;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import com.microsoft.azure.functions.annotation.SendGridOutput;
import com.microsoft.azure.functions.annotation.ServiceBusQueueOutput;
import com.microsoft.azure.functions.annotation.ServiceBusQueueTrigger;
import com.microsoft.azure.functions.annotation.ServiceBusTopicOutput;
import com.microsoft.azure.functions.annotation.ServiceBusTopicTrigger;
import com.microsoft.azure.functions.annotation.StorageAccount;
import com.microsoft.azure.functions.annotation.TableInput;
import com.microsoft.azure.functions.annotation.TableOutput;
import com.microsoft.azure.functions.annotation.TimerTrigger;
import com.microsoft.azure.functions.annotation.TwilioSmsOutput;
import com.microsoft.azure.toolkit.lib.legacy.function.bindings.Binding;
import com.microsoft.azure.toolkit.lib.legacy.function.configurations.FunctionConfiguration;
import com.microsoft.azure.toolkit.lib.legacy.function.utils.IndexUtils;

import static org.junit.Assert.assertEquals;

public class AnnotationHandlerImplTest {
    public static final String HTTP_TRIGGER_FUNCTION = "HttpTriggerFunction";
    public static final String HTTP_TRIGGER_METHOD = "httpTriggerMethod";
    public static final String QUEUE_TRIGGER_FUNCTION = "QueueTriggerFunction";
    public static final String QUEUE_TRIGGER_METHOD = "queueTriggerMethod";
    public static final String COSMOSDB_TRIGGER_FUNCTION = "cosmosDBTriggerFunction";
    public static final String COSMOSDB_TRIGGER_METHOD = "cosmosDBTriggerMethod";
    public static final String TIMER_TRIGGER_FUNCTION = "TimerTriggerFunction";
    public static final String TIMER_TRIGGER_METHOD = "timerTriggerMethod";
    public static final String MULTI_OUTPUT_FUNCTION = "MultiOutputFunction";
    public static final String MULTI_OUTPUT_METHOD = "multipleOutput";
    public static final String BLOB_TRIGGER_FUNCTION = "blobTriggerFunction";
    public static final String BLOB_TRIGGER_METHOD = "blobTriggerMethod";
    public static final String EVENTHUB_TRIGGER_FUNCTION = "eventHubTriggerFunction";
    public static final String EVENTHUB_TRIGGER_METHOD = "eventHubTriggerMethod";
    public static final String EVENTGRID_TRIGGER_FUNCTION = "eventGridTriggerFunction";
    public static final String EVENTGRID_TRIGGER_METHOD = "eventGridTriggerMethod";
    public static final String SERVICE_BUS_QUEUE_TRIGGER_FUNCTION = "serviceBusQueueTriggerFunction";
    public static final String SERVICE_BUS_QUEUE_TRIGGER_METHOD = "serviceBusQueueTriggerMethod";
    public static final String SERVICE_BUS_TOPIC_TRIGGER_FUNCTION = "serviceBusTopicTriggerFunction";
    public static final String SERVICE_BUS_TOPIC_TRIGGER_METHOD = "serviceBusTopicTriggerMethod";
    public static final String CUSTOM_BINDING_FUNCTION = "customBindingFunction";
    public static final String CUSTOM_BINDING_METHOD = "customBindingMethod";
    public static final String EXTENDING_CUSTOM_BINDING_FUNCTION = "extendingCustomBindingFunction";
    public static final String EXTENDING_CUSTOM_BINDING_METHOD = "extendingCustomBindingMethod";
    public static final String EXTENDING_CUSTOM_BINDING_WITHOUT_NAME_FUNCTION =
            "extendingCustomBindingWithoutNameFunction";
    public static final String EXTENDING_CUSTOM_BINDING_WITHOUT_NAME_METHOD = "extendingCustomBindingWithoutNameMethod";

    public static final String[] COSMOSDB_TRIGGER_REQUIRED_ATTRIBUTES = new String[]{"name", "dataType",
        "databaseName", "collectionName", "leaseConnectionStringSetting", "leaseCollectionName",
        "leaseDatabaseName", "createLeaseCollectionIfNotExists", "leasesCollectionThroughput",
        "leaseCollectionPrefix", "checkpointInterval", "checkpointDocumentCount", "feedPollDelay",
        "connectionStringSetting", "leaseRenewInterval", "leaseAcquireInterval", "leaseExpirationInterval",
        "maxItemsPerInvocation", "startFromBeginning", "preferredLocations"};

    public static final String[] COSMOSDB_OUTPUT_REQUIRED_ATTRIBUTES = new String[]{"name", "type",
        "direction", "databaseName", "collectionName", "connectionStringSetting"};

    public static final String[] EVENTHUB_TRIGGER_REQUIRED_ATTRIBUTES = new String[]{"name", "type", "direction",
        "connection", "eventHubName", "cardinality", "consumerGroup"};

    public static final String[] CUSTOM_BINDING_REQUIRED_ATTRIBUTES = new String[]{"name", "type", "direction"};

    public static final String[] EXTENDING_CUSTOM_BINDING_REQUIRED_ATTRIBUTES = new String[]{"name", "type", "path"};

    public static class FunctionEntryPoints {

        @Target(ElementType.PARAMETER)
        @Retention(RetentionPolicy.RUNTIME)
        @CustomBinding(direction = "in", name = "message", type = "customBinding")
        public @interface TestCustomBinding {
            String path();
            String name() default "";
        }

        @FunctionName(CUSTOM_BINDING_FUNCTION)
        public void customBindingMethod(
                @CustomBinding(name = "input", type = "customBinding", direction = "in") String input) {
        }

        @FunctionName(EXTENDING_CUSTOM_BINDING_FUNCTION)
        public void extendingCustomBindingMethod(
            @TestCustomBinding(name = "extendingCustomBinding", path = "testPath")
                    String customTriggerInput) {
        }

        @FunctionName(EXTENDING_CUSTOM_BINDING_WITHOUT_NAME_FUNCTION)
        public void extendingCustomBindingWithoutNameMethod(
                @TestCustomBinding(path = "testPath")
                        String customTriggerInput) {
        }

        @FunctionName(HTTP_TRIGGER_FUNCTION)
        public String httpTriggerMethod(@HttpTrigger(name = "req") String req) {
            return "Hello!";
        }

        @FunctionName(MULTI_OUTPUT_FUNCTION)
        @HttpOutput(name = "$return")
        @QueueOutput(name = "$return", queueName = "qOut", connection = "conn")
        public String multipleOutput(@HttpTrigger(name = "req") String req) {
            return "Hello!";
        }

        @FunctionName(QUEUE_TRIGGER_FUNCTION)
        public void queueTriggerMethod(@QueueTrigger(name = "in", queueName = "qIn", connection = "conn") String in,
                                       @QueueOutput(name = "out", queueName = "qOut", connection = "conn") String out) {
        }

        @FunctionName(COSMOSDB_TRIGGER_FUNCTION)
        public void cosmosDBTriggerMethod(@CosmosDBTrigger(name = "cosmos",
                dataType = "string",
                databaseName = "db",
                collectionName = "cl",
                connectionStringSetting = "conn",
                leaseCollectionName = "lease",
                leaseConnectionStringSetting = "leaseconnectionstringsetting",
                leaseDatabaseName = "leasedatabasename",
                createLeaseCollectionIfNotExists = true,
                leasesCollectionThroughput = 1,
                leaseCollectionPrefix = "prefix",
                checkpointInterval = 1,
                checkpointDocumentCount = 1,
                feedPollDelay = 1,
                leaseRenewInterval = 1,
                leaseAcquireInterval = 1,
                maxItemsPerInvocation = 1,
                startFromBeginning = true,
                preferredLocations = "location",
                leaseExpirationInterval = 1
        ) String in,
            @CosmosDBOutput(name = "itemOut", databaseName = "CosmosDBDatabaseName", collectionName = "out",
                connectionStringSetting = "conn") OutputBinding<String> outPutItem) {
        }

        @FunctionName(EVENTGRID_TRIGGER_FUNCTION)
        public void eventGridTriggerMethod(@EventGridTrigger(name = "eventgrid") String in) {
        }

        @FunctionName(TIMER_TRIGGER_FUNCTION)
        @CosmosDBOutput(name = "$return", databaseName = "db", collectionName = "col", connectionStringSetting = "conn")
        @SendGridOutput(name = "$return", apiKey = "key", to = "to", from = "from", subject = "sub", text = "text")
        @TwilioSmsOutput(name = "$return", accountSid = "sid", authToken = "auth", to = "to", from = "from", body = "b")
        public String timerTriggerMethod(@TimerTrigger(name = "timer", schedule = "") String timer,
                                         @CosmosDBOutput(name = "in1",
                                                 databaseName = "db",
                                                 collectionName = "col",
                                                 connectionStringSetting = "conn") String in1
                                         ) {
            return "Hello!";
        }

        @FunctionName(BLOB_TRIGGER_FUNCTION)
        @StorageAccount("storageAccount")
        @BlobOutput(name = "$return", path = "path")
        @TableOutput(name = "$return", tableName = "table")
        public String blobTriggerMethod(@BlobTrigger(name = "in1", path = "path") String in1,
                                        @BlobInput(name = "in2", path = "path") String in2,
                                        @TableInput(name = "in3", tableName = "table") String in3) {
            return "Hello!";
        }

        @FunctionName(EVENTHUB_TRIGGER_FUNCTION)
        @EventHubOutput(name = "$return", eventHubName = "eventHub", connection = "conn")
        public String eventHubTriggerMethod(
            @EventHubTrigger(name = "messages", eventHubName = "test-input-java", connection =
                "AzureWebJobsEventHubSender", consumerGroup = "consumerGroup", cardinality = Cardinality.MANY)
                String[] messages) {
            return "Hello!";
        }

        @FunctionName(SERVICE_BUS_QUEUE_TRIGGER_FUNCTION)
        @ServiceBusQueueOutput(name = "$return", queueName = "queue", connection = "conn")
        public String serviceBusQueueTriggerMethod(
                @ServiceBusQueueTrigger(name = "in", queueName = "queue", connection = "conn") String in) {
            return "Hello!";
        }

        @FunctionName(SERVICE_BUS_TOPIC_TRIGGER_FUNCTION)
        @ServiceBusTopicOutput(name = "$return", topicName = "topic", subscriptionName = "subs", connection = "conn")
        public String serviceBusTopicTriggerMethod(@ServiceBusTopicTrigger(name = "in", topicName = "topic",
                subscriptionName = "subs", connection = "conn") String in) {
            return "Hello!";
        }
    }

    @Test
    public void findFunctions() throws Exception {
        final AnnotationHandler handler = getAnnotationHandler();
        IndexView testClassIndex = handler.buildIndex(Collections.singletonList(getClassUrl()));
        IndexView fullIndex = IndexUtils.enrichIndex(testClassIndex);
        final Set<MethodInfo> functions = handler.findFunctions(fullIndex);

        Assert.assertEquals(13, functions.size());
        final List<String> methodNames = functions.stream().map(MethodInfo::name).collect(Collectors.toList());
        Assert.assertTrue(methodNames.contains(HTTP_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(QUEUE_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(TIMER_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(MULTI_OUTPUT_METHOD));
        Assert.assertTrue(methodNames.contains(BLOB_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(EVENTHUB_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(SERVICE_BUS_QUEUE_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(SERVICE_BUS_TOPIC_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(COSMOSDB_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(EVENTGRID_TRIGGER_METHOD));
        Assert.assertTrue(methodNames.contains(CUSTOM_BINDING_METHOD));
        Assert.assertTrue(methodNames.contains(EXTENDING_CUSTOM_BINDING_METHOD));
        Assert.assertTrue(methodNames.contains(EXTENDING_CUSTOM_BINDING_WITHOUT_NAME_METHOD));
    }

    @Test
    public void generateConfigurations() throws Exception {
        final AnnotationHandler handler = getAnnotationHandler();
        final List<URL> urls = Collections.singletonList(getClassUrl());
        IndexView fullIndex = IndexUtils.enrichIndex(handler.buildIndex(urls));
        final Set<MethodInfo> functions = handler.findFunctions(fullIndex);
        final Map<String, FunctionConfiguration> configMap = handler.generateConfigurations(fullIndex, functions);
        configMap.values().forEach(FunctionConfiguration::validate);

        Assert.assertEquals(13, configMap.size());

        verifyFunctionConfiguration(configMap, HTTP_TRIGGER_FUNCTION, HTTP_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, QUEUE_TRIGGER_FUNCTION, QUEUE_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, TIMER_TRIGGER_FUNCTION, TIMER_TRIGGER_METHOD, 5);

        verifyFunctionConfiguration(configMap, MULTI_OUTPUT_FUNCTION, MULTI_OUTPUT_METHOD, 3);

        verifyFunctionConfiguration(configMap, BLOB_TRIGGER_FUNCTION, BLOB_TRIGGER_METHOD, 5);

        verifyFunctionConfiguration(configMap, EVENTHUB_TRIGGER_FUNCTION, EVENTHUB_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, SERVICE_BUS_QUEUE_TRIGGER_FUNCTION, SERVICE_BUS_QUEUE_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, SERVICE_BUS_TOPIC_TRIGGER_FUNCTION, SERVICE_BUS_TOPIC_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, COSMOSDB_TRIGGER_FUNCTION, COSMOSDB_TRIGGER_METHOD, 2);

        verifyFunctionConfiguration(configMap, EVENTGRID_TRIGGER_FUNCTION, EVENTGRID_TRIGGER_METHOD, 1);

        verifyFunctionConfiguration(configMap, CUSTOM_BINDING_FUNCTION, CUSTOM_BINDING_METHOD, 1);

        verifyFunctionConfiguration(configMap, EXTENDING_CUSTOM_BINDING_FUNCTION, EXTENDING_CUSTOM_BINDING_METHOD, 1);

        verifyFunctionBinding(configMap.get(COSMOSDB_TRIGGER_FUNCTION).getBindings().stream()
                        .filter(baseBinding -> baseBinding.getName().equals("cosmos")).findFirst().get(),
                COSMOSDB_TRIGGER_REQUIRED_ATTRIBUTES, true);

        verifyFunctionBinding(configMap.get(COSMOSDB_TRIGGER_FUNCTION).getBindings().stream()
                        .filter(baseBinding -> baseBinding.getName().equals("itemOut")).findFirst().get(),
                COSMOSDB_OUTPUT_REQUIRED_ATTRIBUTES, false);

        verifyFunctionBinding(configMap.get(EVENTHUB_TRIGGER_FUNCTION).getBindings().stream()
                        .filter(baseBinding -> baseBinding.getName().equals("messages")).findFirst().get(),
                EVENTHUB_TRIGGER_REQUIRED_ATTRIBUTES, true);

        final Binding customBinding = configMap.get(CUSTOM_BINDING_FUNCTION).getBindings().get(0);
        verifyFunctionBinding(customBinding, CUSTOM_BINDING_REQUIRED_ATTRIBUTES, true);
        assertEquals(customBinding.getName(), "input");
        assertEquals(customBinding.getDirection(), "in");
        assertEquals(customBinding.getType(), "customBinding");

        final Binding extendingCustomBinding = configMap.get(EXTENDING_CUSTOM_BINDING_FUNCTION).getBindings().get(0);
        verifyFunctionBinding(extendingCustomBinding, EXTENDING_CUSTOM_BINDING_REQUIRED_ATTRIBUTES, true);
        assertEquals(extendingCustomBinding.getName(), "extendingCustomBinding");
        assertEquals(extendingCustomBinding.getDirection(), "in");
        assertEquals(extendingCustomBinding.getType(), "customBinding");

        final Binding extendingCustomBindingWithoutName = configMap.get(EXTENDING_CUSTOM_BINDING_WITHOUT_NAME_FUNCTION)
                .getBindings().get(0);
        verifyFunctionBinding(extendingCustomBindingWithoutName, EXTENDING_CUSTOM_BINDING_REQUIRED_ATTRIBUTES, true);
        assertEquals(extendingCustomBindingWithoutName.getName(), "message");
        assertEquals(extendingCustomBindingWithoutName.getDirection(), "in");
        assertEquals(extendingCustomBindingWithoutName.getType(), "customBinding");
    }

    private AnnotationHandlerImpl getAnnotationHandler() {
        return new AnnotationHandlerImpl();
    }

    private URL getClassUrl() {
        return ClasspathHelper.forPackage("com.microsoft.azure.toolkit.lib.legacy.function.handlers")
                .iterator()
                .next();
    }

    private String getFullyQualifiedMethodName(final String methodName) {
        return FunctionEntryPoints.class.getName() + "." + methodName;
    }

    private void verifyFunctionConfiguration(final Map<String, FunctionConfiguration> configMap,
                                             final String functionName, final String methodName, final int bindingNum) {
        Assert.assertTrue(configMap.containsKey(functionName));
        final FunctionConfiguration functionConfig = configMap.get(functionName);
        assertEquals(getFullyQualifiedMethodName(methodName), functionConfig.getEntryPoint());
        assertEquals(bindingNum, functionConfig.getBindings().size());
    }

    private void verifyFunctionBinding(final Binding binding, final String[] requiredAttributes,
                                       boolean isInDirection) throws JsonProcessingException {
        final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
        final String functionJson = writer.writeValueAsString(binding);
        Arrays.stream(requiredAttributes).forEach(
            attribute -> Assert.assertTrue(functionJson.contains(String.format("\"%s\"", attribute))));
        Assert.assertTrue(functionJson.contains(isInDirection ? "\"in\"" : "\"out\""));
    }
}
