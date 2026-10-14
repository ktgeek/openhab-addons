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
package org.openhab.binding.mqtt.awtrixng.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Settings;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.Units;

/**
 * Test cases for the {@link SettingsChannels}. The settings are the example of the AWTRIX NG reference documentation.
 *
 * @author Keith T. Garner - Initial contribution
 */
class SettingsChannelsTest {

    private static final String REPORTED = """
            {"autoBrightness":false,"brightness":120,"textColor":"#FFFFFF","uppercase":true,
             "autoTransition":true,"appDurationMs":7000,"transitionEffect":"Rain","transitionDirection":"normal",
             "transitionDurationMs":1000,"timeMode":1,"timeColor":null,"calendarHeaderColor":"#FF0000",
             "time24h":true,"timeLeadingZero":true,"timeShowSeconds":false,"timeSeparatorMode":"pulse",
             "dateOrder":"dayMonthYear","dateSeparator":"dot","dateYearMode":"twoDigit",
             "weekdayBar":{"show":true,"startOnMonday":true,"weekendDays":["sunday","saturday"],
              "activeColor":"#FFFFFF","inactiveColor":"#666666"},
             "useCelsius":true,"volume":60,"appVolume":100,"alertVolume":100,"blockNavigation":false}
            """;

    private static PayloadChannel<Settings> channel(String id) {
        PayloadChannel<Settings> channel = SettingsChannels.get(id);
        assertNotNull(channel, id);
        return channel;
    }

    private static String json(String channelId, org.openhab.core.types.Command command) {
        Settings update = channel(channelId).toPayload(Settings::new, command);
        assertNotNull(update, channelId);
        return Helper.toJson(update);
    }

    @Test
    void readsStateFromReportedSettings() {
        Settings settings = Helper.fromJson(REPORTED, Settings.class);
        assertNotNull(settings);

        assertEquals(OnOffType.ON, channel("auto-transition").toState(settings));
        assertEquals(OnOffType.OFF, channel("time-show-seconds").toState(settings));
        assertEquals(new DecimalType(1), channel("time-mode").toState(settings));
        assertEquals(new StringType("Rain"), channel("transition-effect").toState(settings));
        assertEquals(new QuantityType<>(7000, MetricPrefix.MILLI(Units.SECOND)),
                channel("app-duration").toState(settings));
        assertEquals(new QuantityType<>(60, Units.PERCENT), channel("volume").toState(settings));
        assertEquals(HSBType.WHITE, channel("text-color").toState(settings));
        assertEquals(HSBType.RED, channel("calendar-header-color").toState(settings));
        assertEquals(OnOffType.ON, channel("weekday-bar-show").toState(settings));
        assertEquals(HSBType.fromRGB(0x66, 0x66, 0x66), channel("weekday-bar-inactive-color").toState(settings));
    }

    @Test
    void fieldsTheClockDidNotReportHaveNoState() {
        Settings settings = Helper.fromJson(REPORTED, Settings.class);
        assertNotNull(settings);

        // timeColor is null: the clock uses the text color
        assertNull(channel("time-color").toState(settings));
        assertNull(channel("date-color").toState(settings));
        assertNull(channel("weekday-bar-active-color").toState(new Settings()));
        assertNull(channel("volume").toState(new Settings()));
    }

    @Test
    void switchCommand() {
        assertEquals("{\"time24h\":false}", json("time-24h", OnOffType.OFF));
        assertEquals("{\"blockNavigation\":true}", json("block-navigation", OnOffType.ON));
    }

    @Test
    void numberAndTextCommands() {
        assertEquals("{\"timeMode\":3}", json("time-mode", new DecimalType(3)));
        assertEquals("{\"transitionEffect\":\"Fade\"}", json("transition-effect", new StringType("Fade")));
        assertEquals("{\"dateOrder\":\"yearMonthDay\"}", json("date-order", new StringType("yearMonthDay")));
    }

    @Test
    void percentCommand() {
        assertEquals("{\"volume\":40}", json("volume", new QuantityType<>(40, Units.PERCENT)));
        assertEquals("{\"alertVolume\":75}", json("alert-volume", new DecimalType(75)));
    }

    @Test
    void durationCommandIsSentInMilliseconds() {
        assertEquals("{\"appDurationMs\":7500}", json("app-duration", new QuantityType<>(7.5, Units.SECOND)));
        assertEquals("{\"appDurationMs\":3000}",
                json("app-duration", new QuantityType<>(3000, MetricPrefix.MILLI(Units.SECOND))));
    }

    @Test
    void durationWithoutUnitIsMilliseconds() {
        assertEquals("{\"transitionDurationMs\":2000}", json("transition-duration", new DecimalType(2000)));
    }

    @Test
    void colorCommand() {
        assertEquals("{\"textColor\":\"#FF0000\"}", json("text-color", HSBType.RED));
    }

    @Test
    void weekdayBarCommandsAreNested() {
        assertEquals("{\"weekdayBar\":{\"show\":false}}", json("weekday-bar-show", OnOffType.OFF));
        assertEquals("{\"weekdayBar\":{\"activeColor\":\"#FFFFFF\"}}", json("weekday-bar-active-color", HSBType.WHITE));
    }

    @Test
    void commandOfTheWrongTypeIsRejected() {
        assertNull(channel("time-24h").toPayload(Settings::new, new StringType("yes")));
        assertNull(channel("volume").toPayload(Settings::new, OnOffType.ON));
        assertNull(channel("text-color").toPayload(Settings::new, OnOffType.ON));
        assertNull(channel("transition-effect").toPayload(Settings::new, new DecimalType(1)));
        assertNull(channel("time-mode").toPayload(Settings::new, new StringType("1")));
    }

    @Test
    void unknownChannelHasNoMapping() {
        assertNull(SettingsChannels.get("brightness"));
        assertNull(SettingsChannels.get("nope"));
    }

    @Test
    void everyMappedChannelIsDefinedOnTheClock() throws IOException {
        String xml;
        try (InputStream in = getClass().getResourceAsStream("/OH-INF/thing/thing-types.xml")) {
            assertNotNull(in);
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = Pattern.compile("<channel id=\"([^\"]+)\"").matcher(xml);
        var defined = new java.util.HashSet<String>();
        while (matcher.find()) {
            defined.add(matcher.group(1));
        }

        for (var entry : SettingsChannels.all()) {
            assertTrue(defined.contains(entry.getKey()), "channel not in thing-types.xml: " + entry.getKey());
        }
    }
}
