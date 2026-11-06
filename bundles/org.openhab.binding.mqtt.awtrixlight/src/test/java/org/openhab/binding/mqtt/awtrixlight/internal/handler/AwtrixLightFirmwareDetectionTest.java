/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.awtrixlight.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.mqtt.awtrixlight.internal.AwtrixLightBindingConstants.*;
import static org.openhab.binding.mqtt.awtrixlight.internal.ng.AwtrixNgBindingConstants.THING_TYPE_NG_APP;
import static org.openhab.binding.mqtt.awtrixlight.internal.ng.AwtrixNgBindingConstants.THING_TYPE_NG_CLOCK;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.mqtt.awtrixlight.internal.FirmwareSwitchGuard;
import org.openhab.binding.mqtt.handler.AbstractBrokerHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.mqtt.MqttBrokerConnection;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;

/**
 * Test cases for the change of the AWTRIX 3 things to the AWTRIX NG thing types.
 *
 * @author Keith T. Garner - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwtrixLightFirmwareDetectionTest {

    private static final String BASE = "awtrix_abc123";
    private static final ThingUID BROKER_UID = new ThingUID("mqtt", "broker", "b");
    private static final String AWTRIX3_STATS = "{\"version\":\"0.98\",\"uid\":\"awtrix_abc123\",\"bat\":80}";

    private @Mock ThingHandlerCallback callback;
    private @Mock Bridge broker;
    private @Mock AbstractBrokerHandler brokerHandler;
    private @Mock MqttBrokerConnection connection;

    private Bridge clock;
    private FirmwareSwitchGuard guard;
    private AwtrixLightBridgeHandler handler;

    @BeforeEach
    void setUp() {
        clock = BridgeBuilder.create(THING_TYPE_BRIDGE, "clock").withBridge(BROKER_UID)
                .withConfiguration(new Configuration(Map.of("basetopic", BASE, "appLockTimeout", 12,
                        "discoverDefaultApps", true, "lowBatteryThreshold", 30)))
                .build();
        guard = new FirmwareSwitchGuard();
        // The long delay keeps the scheduled switch from running within a test, the tests call it themselves
        handler = new AwtrixLightBridgeHandler(clock, guard, 60_000);
        handler.setCallback(callback);

        when(callback.getBridge(any())).thenReturn(broker);
        when(broker.getStatusInfo()).thenReturn(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        when(broker.getHandler()).thenReturn(brokerHandler);
        when(brokerHandler.getConnectionAsync()).thenReturn(CompletableFuture.completedFuture(connection));
    }

    @AfterEach
    void tearDown() {
        handler.dispose();
    }

    private void message(String suffix, String payload) {
        handler.processMessage(BASE + suffix, payload.getBytes(StandardCharsets.UTF_8));
    }

    private void verifyNoThingTypeChange() {
        verify(callback, never()).migrateThingType(any(), any(), any());
    }

    @Test
    void subscribesToTheAvailabilityOfAwtrixNg() {
        handler.initialize();

        verify(connection).subscribe(BASE + "/availability", handler);
    }

    @Test
    void disposeUnsubscribesFromTheAvailability() {
        handler.initialize();

        handler.dispose();

        verify(connection).unsubscribe(BASE + "/availability", handler);
    }

    @Test
    void availabilityOnlineWaitsForTheConfirmation() {
        handler.initialize();

        message("/availability", "online");

        assertTrue(handler.isNgSwitchPending());
        verifyNoThingTypeChange();
    }

    @Test
    void availabilityOfflineIsNoReasonToSwitch() {
        handler.initialize();

        message("/availability", "offline");

        assertFalse(handler.isNgSwitchPending());
    }

    @Test
    void confirmedAvailabilityChangesToTheNgClockAndKeepsTheSharedConfiguration() {
        handler.initialize();
        message("/availability", "online");

        handler.switchToNgUnlessStatsSeen();

        ArgumentCaptor<Configuration> configuration = ArgumentCaptor.forClass(Configuration.class);
        verify(callback).migrateThingType(eq(clock), eq(THING_TYPE_NG_CLOCK), configuration.capture());
        assertEquals(new Configuration(Map.of("basetopic", BASE, "appLockTimeout", 12, "discoverDefaultApps", true))
                .getProperties(), configuration.getValue().getProperties());
    }

    @Test
    void statsOfAwtrix3AfterTheAvailabilityKeepTheThingType() {
        handler.initialize();
        message("/availability", "online");
        message("/stats", AWTRIX3_STATS);

        handler.switchToNgUnlessStatsSeen();

        verifyNoThingTypeChange();
    }

    @Test
    void newAvailabilityStartsTheConfirmationAgain() {
        handler.initialize();
        message("/stats", AWTRIX3_STATS);
        message("/availability", "online");

        handler.switchToNgUnlessStatsSeen();

        verify(callback).migrateThingType(eq(clock), eq(THING_TYPE_NG_CLOCK), any());
    }

    @Test
    void switchIsRefusedWhileTheGuardHoldsTheClock() {
        assertTrue(guard.tryRecordSwitch(clock.getUID()));
        handler.initialize();
        message("/availability", "online");

        handler.switchToNgUnlessStatsSeen();

        verifyNoThingTypeChange();
    }

    @Test
    void scheduledConfirmationChangesTheThingType() {
        AwtrixLightBridgeHandler immediate = new AwtrixLightBridgeHandler(clock, guard, 0);
        immediate.setCallback(callback);
        immediate.initialize();

        immediate.processMessage(BASE + "/availability", "online".getBytes(StandardCharsets.UTF_8));

        verify(callback, timeout(5000)).migrateThingType(eq(clock), eq(THING_TYPE_NG_CLOCK), any());
        immediate.dispose();
    }

    @Test
    void appChangesToTheNgAppWhenItsClockIsNg() {
        Bridge ngClock = BridgeBuilder.create(THING_TYPE_NG_CLOCK, "clock").withBridge(BROKER_UID).build();
        Thing app = ThingBuilder.create(THING_TYPE_APP, "washing").withBridge(ngClock.getUID())
                .withConfiguration(new Configuration(Map.of("appname", "washing", "useButtons", true))).build();
        AwtrixLightAppHandler appHandler = new AwtrixLightAppHandler(app);
        appHandler.setCallback(callback);
        when(callback.getBridge(ngClock.getUID())).thenReturn(ngClock);

        appHandler.initialize();

        ArgumentCaptor<Configuration> configuration = ArgumentCaptor.forClass(Configuration.class);
        verify(callback).migrateThingType(eq(app), eq(THING_TYPE_NG_APP), configuration.capture());
        assertEquals(Map.of("appname", "washing", "useButtons", true), configuration.getValue().getProperties());
    }

    @Test
    void appStaysAnAwtrix3AppWhenItsClockIsAwtrix3() {
        Thing app = ThingBuilder.create(THING_TYPE_APP, "washing").withBridge(clock.getUID())
                .withConfiguration(new Configuration(Map.of("appname", "washing"))).build();
        AwtrixLightAppHandler appHandler = new AwtrixLightAppHandler(app);
        appHandler.setCallback(callback);
        when(callback.getBridge(clock.getUID())).thenReturn(clock);

        appHandler.initialize();

        verifyNoThingTypeChange();
        appHandler.dispose();
    }
}
