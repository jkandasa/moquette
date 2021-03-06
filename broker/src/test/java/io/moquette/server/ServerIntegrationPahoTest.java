/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.server;

import io.moquette.server.config.IConfig;
import io.moquette.server.config.MemoryConfig;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.*;

public class ServerIntegrationPahoTest {

    private static final Logger LOG = LoggerFactory.getLogger(ServerIntegrationPahoTest.class);

    static MqttClientPersistence s_dataStore;
    static MqttClientPersistence s_pubDataStore;
    
    Server m_server;
    IMqttClient m_client;
    TestCallback m_callback;
    IConfig m_config;

    @BeforeClass
    public static void beforeTests() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        s_dataStore = new MqttDefaultFilePersistence(tmpDir);
        s_pubDataStore = new MqttDefaultFilePersistence(tmpDir + File.separator + "publisher");
    }

    protected void startServer() throws IOException {
        m_server = new Server();
        final Properties configProps = IntegrationUtils.prepareTestPropeties();
        m_config = new MemoryConfig(configProps);
        m_server.startServer(m_config);
    }

    @Before
    public void setUp() throws Exception {
        String dbPath = IntegrationUtils.localMapDBPath();
        IntegrationUtils.cleanPersistenceFile(dbPath);

        startServer();

        m_client = new MqttClient("tcp://localhost:1883", "TestClient", s_dataStore);
        m_callback = new TestCallback();
        m_client.setCallback(m_callback);
    }

    @After
    public void tearDown() throws Exception {
        if (m_client.isConnected()) {
            m_client.disconnect();
        }

        stopServer();
    }

    private void stopServer() {
        m_server.stopServer();
        IntegrationUtils.cleanPersistenceFile(m_config);
    }

    @Test
    public void testSubscribe() throws Exception {
        LOG.info("*** testSubscribe ***");
        m_client.connect();
        m_client.subscribe("/topic", 0);

        MqttMessage message = new MqttMessage("Hello world!!".getBytes());
        message.setQos(0);
        message.setRetained(false);
        m_client.publish("/topic", message);

        assertEquals("/topic", m_callback.getTopic());
    }
    

    @Test
    public void testCleanSession_maintainClientSubscriptions() throws Exception {
        LOG.info("*** testCleanSession_maintainClientSubscriptions ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.disconnect();

        //reconnect and publish
        MqttClient anotherClient = new MqttClient("tcp://localhost:1883", "TestClient", s_dataStore);
        m_callback = new TestCallback();
        anotherClient.setCallback(m_callback);
        anotherClient.connect(options);
        anotherClient.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertEquals("/topic", m_callback.getTopic());
    }


    @Test
    public void testCleanSession_maintainClientSubscriptions_againstClientDestruction() throws Exception {
        LOG.info("*** testCleanSession_maintainClientSubscriptions_againstClientDestruction ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.disconnect();

        //reconnect and publish
        m_client.connect(options);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertEquals("/topic", m_callback.getTopic());
    }

    /**
     * Check that after a client has connected with clean session false, subscribed
     * to some topic and exited, if it reconnects with clean session true, the m_server
     * correctly cleanup every previous subscription
     */
    @Test
    public void testCleanSession_correctlyClientSubscriptions() throws Exception {
        LOG.info("*** testCleanSession_correctlyClientSubscriptions ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.disconnect();

        //reconnect and publish
        m_client.connect(options);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertEquals("Test my payload", new String(m_callback.getMessage(false).getPayload()));
    }

    @Test
    public void testCleanSession_maintainClientSubscriptions_withServerRestart() throws Exception {
        LOG.info("*** testCleanSession_maintainClientSubscriptions_withServerRestart ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.disconnect();

        m_server.stopServer();

        m_server.startServer(IntegrationUtils.prepareTestPropeties());

        //reconnect and publish
        m_client.connect(options);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertEquals("/topic", m_callback.getTopic());
    }

    @Test
    public void testRetain_maintainMessage_againstClientDestruction() throws Exception {
        LOG.info("*** testRetain_maintainMessage_againstClientDestruction ***");
        m_client.connect();
        m_client.publish("/topic", "Test my payload".getBytes(), 1, true);
        m_client.disconnect();

        //reconnect and publish
        m_client.connect();
        m_client.subscribe("/topic", 0);

        assertEquals("/topic", m_callback.getTopic());
    }

    @Test
    public void testUnsubscribe_do_not_notify_anymore_same_session() throws Exception {
        LOG.info("*** testUnsubscribe_do_not_notify_anymore_same_session ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);
        //m_client.disconnect();
        assertEquals("/topic", m_callback.getTopic());

        m_client.unsubscribe("/topic");
        m_callback.reinit();
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertNull(m_callback.getMessage(false));
    }

    @Test
    public void testUnsubscribe_do_not_notify_anymore_new_session() throws Exception {
        LOG.info("*** testUnsubscribe_do_not_notify_anymore_new_session ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);
        //m_client.disconnect();
        assertEquals("/topic", m_callback.getTopic());

        m_client.unsubscribe("/topic");
        m_client.disconnect();

        m_callback.reinit();
        m_client.connect(options);
        m_client.publish("/topic", "Test my payload".getBytes(), 0, false);

        assertNull(m_callback.getMessage(false));
    }

    @Test
    public void testPublishWithQoS1() throws Exception {
        LOG.info("*** testPublishWithQoS1 ***");
        m_client.connect();
        m_client.subscribe("/topic", 1);
        m_client.publish("/topic", "Hello MQTT".getBytes(), 1, false);
        m_client.disconnect();

        //reconnect and publish
        MqttMessage message = m_callback.getMessage(true);
        assertEquals("Hello MQTT", message.toString());
        assertEquals(1, message.getQos());
    }

    @Test
    public void testPublishWithQoS1_notCleanSession() throws Exception {
        LOG.info("*** testPublishWithQoS1_notCleanSession ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 1);
        m_client.disconnect();

        //publish a QoS 1 message another client publish a message on the topic
        publishFromAnotherClient("/topic", "Hello MQTT".getBytes(), 1);

        m_client.connect(options);

        assertEquals("Hello MQTT", m_callback.getMessage(true).toString());
    }

    @Test
    public void checkReceivePublishedMessage_after_a_reconnect_with_notCleanSession() throws Exception {
        LOG.info("*** checkReceivePublishedMessage_after_a_reconnect_with_notCleanSession ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 1);
        m_client.disconnect();

        m_client.connect(options);
        m_client.subscribe("/topic", 1);

        //publish a QoS 1 message another client publish a message on the topic
        publishFromAnotherClient("/topic", "Hello MQTT".getBytes(), 1);

        //Verify that after a reconnection the client receive the message
        MqttMessage message = m_callback.getMessage(true);
        assertNotNull(message);
        assertEquals("Hello MQTT", message.toString());
    }

    private void publishFromAnotherClient(String topic, byte[] payload, int qos) throws Exception {
        IMqttClient anotherClient = new MqttClient("tcp://localhost:1883", "TestClientPUB", s_pubDataStore);
        anotherClient.connect();
        anotherClient.publish(topic, payload, qos, false);
        anotherClient.disconnect();
    }

    @Test
    public void testPublishWithQoS2() throws Exception {
        LOG.info("*** testPublishWithQoS2 ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 2);
        m_client.disconnect();

        //publish a QoS 1 message another client publish a message on the topic
        publishFromAnotherClient("/topic", "Hello MQTT".getBytes(), 2);
        m_callback.reinit();
        m_client.connect(options);

        MqttMessage message = m_callback.getMessage(true);
        assertEquals("Hello MQTT", message.toString());
        assertEquals(2, message.getQos());
    }

    @Test
    public void testPublishReceiveWithQoS2() throws Exception {
        LOG.info("*** testPublishReceiveWithQoS2 ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 2);
        m_client.disconnect();

        //publish a QoS 2 message another client publish a message on the topic
        publishFromAnotherClient("/topic", "Hello MQTT".getBytes(), 2);
        m_callback.reinit();
        m_client.connect(options);

        assertNotNull(m_callback);
        MqttMessage message = m_callback.getMessage(true);
        assertNotNull(message);
        assertEquals("Hello MQTT", message.toString());
    }

    @Test
    public void avoidMultipleNotificationsAfterMultipleReconnection_cleanSessionFalseQoS1() throws Exception {
        LOG.info("*** avoidMultipleNotificationsAfterMultipleReconnection_cleanSessionFalseQoS1, issue #16 ***");
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        m_client.connect(options);
        m_client.subscribe("/topic", 1);
        m_client.disconnect();

        publishFromAnotherClient("/topic", "Hello MQTT 1".getBytes(), 1);
        m_callback.reinit();
        m_client.connect(options);

        assertNotNull(m_callback);
        MqttMessage message = m_callback.getMessage(true);
        assertNotNull(message);
        assertEquals("Hello MQTT 1", message.toString());
        m_client.disconnect();

        //publish other message
        publishFromAnotherClient("/topic", "Hello MQTT 2".getBytes(), 1);

        //reconnect the second time
        m_callback.reinit();
        m_client.connect(options);
        assertNotNull(m_callback);
        message = m_callback.getMessage(true);
        assertNotNull(message);
        assertEquals("Hello MQTT 2", message.toString());
    }

    /**
     * subscriber A connect and subscribe on "a/b" QoS 1
     * subscriber B connect and subscribe on "a/+"  BUT with QoS 2
     * publisher connects and send a message "hello" on "a/b"
     * subscriber A must receive a notification with QoS1
     * subscriber B must receive a notification with QoS2
     */
    @Test
    public void checkSubscribersGetCorrectQosNotifications() throws Exception {
        LOG.info("*** checkSubscribersGetCorrectQosNotifications ***");
        String tmpDir = System.getProperty("java.io.tmpdir");

        MqttClientPersistence dsSubscriberA = new MqttDefaultFilePersistence(tmpDir + File.separator + "subscriberA");

        MqttClient subscriberA = new MqttClient("tcp://localhost:1883", "SubscriberA", dsSubscriberA);
        TestCallback cbSubscriberA = new TestCallback();
        subscriberA.setCallback(cbSubscriberA);
        subscriberA.connect();
        subscriberA.subscribe("a/b", 1);

        MqttClientPersistence dsSubscriberB = new MqttDefaultFilePersistence(tmpDir + File.separator + "subscriberB");

        MqttClient subscriberB = new MqttClient("tcp://localhost:1883", "SubscriberB", dsSubscriberB);
        TestCallback cbSubscriberB = new TestCallback();
        subscriberB.setCallback(cbSubscriberB);
        subscriberB.connect();
        subscriberB.subscribe("a/+", 2);


        m_client.connect();
        m_client.publish("a/b", "Hello world MQTT!!".getBytes(), 2, false);

        MqttMessage messageOnA = cbSubscriberA.getMessage(true);
        assertEquals("Hello world MQTT!!", new String(messageOnA.getPayload()));
        assertEquals(1, messageOnA.getQos());
        subscriberA.disconnect();

        MqttMessage messageOnB = cbSubscriberB.getMessage(true);
        assertEquals("Hello world MQTT!!", new String(messageOnB.getPayload()));
        assertEquals(2, messageOnB.getQos());
        subscriberB.disconnect();
    }

    @Test
    public void testSubcriptionDoesntStayActiveAfterARestart() throws Exception {
        LOG.info("*** testSubcriptionDoesntStayActiveAfterARestart ***");
        String tmpDir = System.getProperty("java.io.tmpdir");
        //clientForSubscribe1 connect and subscribe to /topic QoS2
        MqttClientPersistence dsSubscriberA = new MqttDefaultFilePersistence(tmpDir + File.separator + "clientForSubscribe1");

        MqttClient clientForSubscribe1 = new MqttClient("tcp://localhost:1883", "clientForSubscribe1", dsSubscriberA);
        TestCallback cbSubscriber1 = new TestCallback();
        clientForSubscribe1.setCallback(cbSubscriber1);
        clientForSubscribe1.connect();
        clientForSubscribe1.subscribe("topic", 0);

        //server stop
        m_server.stopServer();
        System.out.println("\n\n SEVER REBOOTING \n\n");
        //server start
        startServer();

        //clientForSubscribe2 connect and subscribe to /topic QoS2
        MqttClientPersistence dsSubscriberB = new MqttDefaultFilePersistence(tmpDir + File.separator + "clientForSubscribe2");
        MqttClient clientForSubscribe2 = new MqttClient("tcp://localhost:1883", "clientForSubscribe2", dsSubscriberB);
        TestCallback cbSubscriber2 = new TestCallback();
        clientForSubscribe2.setCallback(cbSubscriber2);
        clientForSubscribe2.connect();
        clientForSubscribe2.subscribe("topic", 0);

        //clientForPublish publish on /topic with QoS2 a message
        MqttClientPersistence dsSubscriberPUB = new MqttDefaultFilePersistence(tmpDir + File.separator + "clientForPublish");
        MqttClient clientForPublish = new MqttClient("tcp://localhost:1883", "clientForPublish", dsSubscriberPUB);
        clientForPublish.connect();
        clientForPublish.publish("topic", "Hello".getBytes(), 2, true);

        //verify clientForSubscribe1 doesn't receive a notification but clientForSubscribe2 yes
        System.out.println("Before waiting to receive 1 sec from " + clientForSubscribe1.getClientId());
        assertFalse(clientForSubscribe1.isConnected());
        assertTrue(clientForSubscribe2.isConnected());
        System.out.println("Waiting to receive 1 sec from " + clientForSubscribe2.getClientId());
        MqttMessage messageOnB = cbSubscriber2.getMessage(true);
        assertEquals("Hello", new String(messageOnB.getPayload()));
    }

    @Test
    public void testForceClientDisconnection_issue116() throws Exception {
        LOG.info("*** testForceClientDisconnection_issue118 ***");
        TestCallback cbSubscriber1 = new TestCallback();
        MqttClient clientXA = createClient("subscriber", "X", cbSubscriber1);
        clientXA.subscribe("topic", 0);

        MqttClient clientXB = createClient("publisher", "X");
        clientXB.publish("topic", "Hello".getBytes(), 2, true);

        TestCallback cbSubscriber2 = new TestCallback();
        MqttClient clientYA = createClient("subscriber", "Y", cbSubscriber2);
        clientYA.subscribe("topic", 0);

        MqttClient clientYB = createClient("publisher", "Y");
        clientYB.publish("topic", "Hello 2".getBytes(), 2, true);

        //Verify that the second subscriber client get notified and not the first.
        assertTrue(cbSubscriber1.connectionLost());
        assertEquals("Hello 2", new String(cbSubscriber2.getMessage(true).getPayload()));
    }


    protected MqttClient createClient(String clientName, String storeSuffix) throws MqttException {
        return createClient(clientName, storeSuffix, null);
    }

    protected MqttClient createClient(String clientName, String storeSuffix, TestCallback cb) throws MqttException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        //clientX connect and subscribe to /topic QoS2
        MqttClientPersistence dsClient = new MqttDefaultFilePersistence(tmpDir + File.separator + storeSuffix + clientName);
        MqttClient client = new MqttClient("tcp://localhost:1883", clientName, dsClient);
        if (cb != null) {
            client.setCallback(cb);
        }
        client.connect();
        return client;
    }

}
