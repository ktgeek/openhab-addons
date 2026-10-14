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
package org.openhab.binding.mqtt.awtrixng.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.mqtt.awtrixng.internal.AwtrixNgBindingConstants.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonParser;

/**
 * Test cases for the {@link AwtrixNgAppHandler}.
 *
 * @author Keith T. Garner - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwtrixNgAppHandlerTest {

    private static final ThingUID CLOCK_UID = new ThingUID(THING_TYPE_CLOCK, new ThingUID("mqtt", "broker", "b"), "c");
    private static final String STORE_TOPIC = "a4cf12ab34cd/openhab/apps/weather";

    private @Mock ThingHandlerCallback callback;
    private @Mock Bridge clock;
    private @Mock AwtrixNgBridgeHandler bridgeHandler;

    /** Changes the configuration the way openHAB does, which also drops the configuration the handler cached. */
    private static class TestHandler extends AwtrixNgAppHandler {
        TestHandler(Thing thing) {
            super(thing);
        }

        void configure(String appName) {
            configure(appName, false);
        }

        void configure(String appName, boolean useButtons) {
            updateConfiguration(new Configuration(Map.of("appname", appName, "useButtons", useButtons)));
        }
    }

    private Thing app;
    private TestHandler handler;

    @BeforeEach
    void setUp() {
        app = ThingBuilder.create(THING_TYPE_APP, "weather").withBridge(CLOCK_UID)
                .withConfiguration(new Configuration(Map.of("appname", "weather"))).build();
        handler = new TestHandler(app);
        handler.setCallback(callback);

        when(callback.getBridge(CLOCK_UID)).thenReturn(clock);
        when(clock.getStatusInfo()).thenReturn(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        when(clock.getHandler()).thenReturn(bridgeHandler);
    }

    private ChannelUID channel(String id) {
        return new ChannelUID(app.getUID(), id);
    }

    private ThingStatusInfo lastStatus() {
        ArgumentCaptor<ThingStatusInfo> captor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback, atLeastOnce()).statusUpdated(eq(app), captor.capture());
        return captor.getValue();
    }

    private void stored(String json) {
        handler.processMessage(STORE_TOPIC, json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertPushed(String expectedJson) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(bridgeHandler, atLeastOnce()).pushApp(eq("weather"), payload.capture());
        assertEquals(JsonParser.parseString(expectedJson), JsonParser.parseString(payload.getValue()),
                payload.getValue());
    }

    private void assertStored(String expectedJson) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(bridgeHandler, atLeastOnce()).storeApp(eq("weather"), payload.capture());
        assertEquals(JsonParser.parseString(expectedJson), JsonParser.parseString(payload.getValue()),
                payload.getValue());
    }

    /** Starts the handler with the stored state it would get from the broker. */
    private void synchronizedWith(String json) {
        handler.initialize();
        stored(json);
        clearInvocations(bridgeHandler, callback);
    }

    @Test
    void waitsForTheStoredStateOfTheApp() {
        handler.initialize();

        assertEquals(ThingStatus.UNKNOWN, lastStatus().getStatus());
        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void storedStateIsRestoredAndPushedToTheClock() {
        handler.initialize();

        stored("{\"text\":\"21 C\",\"icon\":\"2422\",\"durationMs\":5000}");

        verify(callback).stateUpdated(channel("text"), new StringType("21 C"));
        verify(callback).stateUpdated(channel("icon"), new StringType("2422"));
        verify(callback).stateUpdated(channel("duration"), new QuantityType<>(5000, MetricPrefix.MILLI(Units.SECOND)));
        verify(callback).stateUpdated(channel("lifetime"), UnDefType.NULL);
        assertPushed("{\"text\":\"21 C\",\"icon\":\"2422\",\"durationMs\":5000}");
        assertEquals(ThingStatus.ONLINE, lastStatus().getStatus());
    }

    @Test
    void withoutStoredStateTheAppStartsWithEmptyText() {
        handler.initialize();

        handler.finishSynchronization();

        // An empty payload would delete the app on the clock, so the text is always sent
        assertPushed("{\"text\":\"\"}");
        assertStored("{\"text\":\"\"}");
        assertEquals(ThingStatus.ONLINE, lastStatus().getStatus());
    }

    @Test
    void invalidStoredStateStartsEmpty() {
        handler.initialize();

        stored("{not json");

        assertPushed("{\"text\":\"\"}");
    }

    @Test
    void storedStateWithoutTextStillSendsText() {
        handler.initialize();

        stored("{\"icon\":\"1\"}");

        assertPushed("{\"text\":\"\",\"icon\":\"1\"}");
    }

    @Test
    void laterStoredStateIsIgnored() {
        synchronizedWith("{\"text\":\"first\"}");

        stored("{\"text\":\"echo of another change\"}");

        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void commandChangesPushesAndStoresTheApp() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.handleCommand(channel("text-color"), HSBType.RED);
        handler.handleCommand(channel("duration"), new QuantityType<>(10, Units.SECOND));

        assertPushed("{\"text\":\"21 C\",\"textColor\":\"#FF0000\",\"durationMs\":10000}");
        assertStored("{\"text\":\"21 C\",\"textColor\":\"#FF0000\",\"durationMs\":10000}");
        // Every command republishes all states, so the color is reported again by the second command
        verify(callback, atLeastOnce()).stateUpdated(channel("text-color"), HSBType.RED);
        verify(callback, atLeastOnce()).stateUpdated(channel("text-palette"), OnOffType.OFF);
    }

    @Test
    void commandOfTheWrongTypeChangesNothing() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.handleCommand(channel("text-color"), OnOffType.ON);
        handler.handleCommand(channel("duration"), new StringType("long"));

        verify(bridgeHandler, never()).pushApp(any(), any());
        verify(bridgeHandler, never()).storeApp(any(), any());
    }

    @Test
    void commandsBeforeSynchronizationAreIgnored() {
        handler.initialize();

        handler.handleCommand(channel("text"), new StringType("too early"));

        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void resetStartsOverWithEmptyText() {
        synchronizedWith("{\"text\":\"21 C\",\"icon\":\"2422\"}");

        handler.handleCommand(channel(CHANNEL_RESET), OnOffType.ON);

        assertPushed("{\"text\":\"\"}");
        assertStored("{\"text\":\"\"}");
        verify(callback).stateUpdated(channel("icon"), UnDefType.NULL);
    }

    @Test
    void refreshRepublishesTheStatesOnly() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.handleCommand(channel("text"), RefreshType.REFRESH);

        verify(callback).stateUpdated(channel("text"), new StringType("21 C"));
        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void enabledCommandSwitchesTheAppInTheRotation() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.handleCommand(channel(CHANNEL_ENABLED), OnOffType.OFF);
        handler.handleCommand(channel(CHANNEL_ENABLED), OnOffType.ON);

        verify(bridgeHandler).setAppEnabled("weather", false);
        verify(bridgeHandler).setAppEnabled("weather", true);
    }

    @Test
    void clockComingOnlinePushesTheAppAgain() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.deviceOnline();

        assertPushed("{\"text\":\"21 C\"}");
        verify(bridgeHandler, never()).storeApp(any(), any());
    }

    @Test
    void clockComingOnlineBeforeSynchronizationPushesNothing() {
        handler.initialize();

        handler.deviceOnline();

        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void activeChannelFollowsTheClock() {
        handler.setActive(true);
        handler.setActive(false);

        verify(callback).stateUpdated(channel(CHANNEL_ACTIVE), OnOffType.ON);
        verify(callback).stateUpdated(channel(CHANNEL_ACTIVE), OnOffType.OFF);
    }

    @Test
    void invalidAppNameIsAConfigurationError() {
        for (String name : new String[] { "", "has space", "ümlaut", "a".repeat(33) }) {
            handler.configure(name);

            handler.initialize();

            assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, lastStatus().getStatusDetail(), name);
        }
        verify(bridgeHandler, never()).pushApp(any(), any());
    }

    @Test
    void renamingDeletesTheOldApp() {
        synchronizedWith("{\"text\":\"21 C\"}");
        handler.configure("forecast");

        handler.initialize();

        verify(bridgeHandler).deleteApp("weather");
        assertEquals("forecast", handler.getAppName());
    }

    @Test
    void removalDeletesTheApp() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.handleRemoval();

        verify(bridgeHandler).deleteApp("weather");
    }

    @Test
    void offlineBridgeTakesTheAppOffline() {
        synchronizedWith("{\"text\":\"21 C\"}");

        handler.bridgeStatusChanged(new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE, null));

        assertEquals(ThingStatusDetail.BRIDGE_OFFLINE, lastStatus().getStatusDetail());
    }

    @Test
    void textMarkupIsSentAsFragments() {
        synchronizedWith("{\"text\":\"\"}");

        handler.handleCommand(channel("text"), new StringType("a <font color=\"#00FF00\">b</font>"));

        assertPushed("{\"text\":[{\"text\":\"a \"},{\"text\":\"b\",\"color\":\"#00FF00\"}]}");
        verify(callback).stateUpdated(channel("text"), new StringType("a <font color=\"#00FF00\">b</font>"));
    }

    @Test
    void numberChannelsTakeNumbers() {
        synchronizedWith("{\"text\":\"x\"}");

        handler.handleCommand(channel("repeat"), new DecimalType(2));

        assertPushed("{\"text\":\"x\",\"repeat\":2}");
        State state = new DecimalType(2);
        verify(callback).stateUpdated(channel("repeat"), state);
    }

    @Test
    void buttonsOfALockedAppTriggerItsChannels() {
        handler.handleButton(CHANNEL_BUTTON_LEFT, EVENT_PRESSED);
        handler.handleButton(CHANNEL_BUTTON_SELECT, EVENT_RELEASED);

        verify(callback).channelTriggered(app, channel(CHANNEL_BUTTON_LEFT), EVENT_PRESSED);
        verify(callback).channelTriggered(app, channel(CHANNEL_BUTTON_SELECT), EVENT_RELEASED);
    }

    @Test
    void useButtonsComesFromTheConfiguration() {
        assertFalse(handler.isButtonControlled());

        handler.configure("weather", true);
        handler.initialize();

        assertTrue(handler.isButtonControlled());
    }

    @Test
    void appIdIsTheClockAndTheNameWhenTheClockKnowsItsUniqueId() {
        when(clock.getProperties()).thenReturn(Map.of(PROP_UNIQUEID, "a4cf12ab34cd"));

        handler.initialize();

        assertEquals("a4cf12ab34cd-weather", app.getProperties().get(PROP_APPID));
    }

    @Test
    void appIdIsLeftAloneWhenTheClockDoesNotKnowItsUniqueIdYet() {
        handler.initialize();

        assertNull(app.getProperties().get(PROP_APPID));
    }
}
