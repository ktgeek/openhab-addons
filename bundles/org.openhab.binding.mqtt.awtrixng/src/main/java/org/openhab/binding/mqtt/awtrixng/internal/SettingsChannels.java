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

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Settings;
import org.openhab.binding.mqtt.awtrixng.internal.dto.WeekdayBar;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.Units;

/**
 * The {@link SettingsChannels} lists the channels of the clock that are plain fields of its settings, and how each one
 * maps to its field.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class SettingsChannels {

    private static final Map<String, SettingsChannel> CHANNELS = new HashMap<>();

    static {
        // App rotation
        switchChannel("auto-transition", s -> s.autoTransition, (s, v) -> s.autoTransition = v);
        textChannel("transition-effect", s -> s.transitionEffect, (s, v) -> s.transitionEffect = v);
        textChannel("transition-direction", s -> s.transitionDirection, (s, v) -> s.transitionDirection = v);
        durationChannel("transition-duration", s -> s.transitionDurationMs, (s, v) -> s.transitionDurationMs = v);
        durationChannel("app-duration", s -> s.appDurationMs, (s, v) -> s.appDurationMs = v);
        switchChannel("block-navigation", s -> s.blockNavigation, (s, v) -> s.blockNavigation = v);

        // Sound
        percentChannel("volume", s -> s.volume, (s, v) -> s.volume = v);
        percentChannel("app-volume", s -> s.appVolume, (s, v) -> s.appVolume = v);
        percentChannel("alert-volume", s -> s.alertVolume, (s, v) -> s.alertVolume = v);

        // Clock and date text
        numberChannel("time-mode", s -> s.timeMode, (s, v) -> s.timeMode = v);
        switchChannel("time-24h", s -> s.time24h, (s, v) -> s.time24h = v);
        switchChannel("time-leading-zero", s -> s.timeLeadingZero, (s, v) -> s.timeLeadingZero = v);
        switchChannel("time-show-seconds", s -> s.timeShowSeconds, (s, v) -> s.timeShowSeconds = v);
        switchChannel("time-show-ampm", s -> s.timeShowAmPm, (s, v) -> s.timeShowAmPm = v);
        textChannel("time-separator", s -> s.timeSeparatorMode, (s, v) -> s.timeSeparatorMode = v);
        textChannel("date-order", s -> s.dateOrder, (s, v) -> s.dateOrder = v);
        textChannel("date-separator", s -> s.dateSeparator, (s, v) -> s.dateSeparator = v);
        textChannel("date-year-mode", s -> s.dateYearMode, (s, v) -> s.dateYearMode = v);
        switchChannel("date-show-weekday", s -> s.dateShowWeekday, (s, v) -> s.dateShowWeekday = v);
        switchChannel("date-month-names", s -> s.dateMonthNames, (s, v) -> s.dateMonthNames = v);
        switchChannel("use-celsius", s -> s.useCelsius, (s, v) -> s.useCelsius = v);
        switchChannel("uppercase", s -> s.uppercase, (s, v) -> s.uppercase = v);

        // Colors
        colorChannel("text-color", s -> s.textColor, (s, v) -> s.textColor = v);
        colorChannel("time-color", s -> s.timeColor, (s, v) -> s.timeColor = v);
        colorChannel("date-color", s -> s.dateColor, (s, v) -> s.dateColor = v);
        colorChannel("temperature-color", s -> s.temperatureColor, (s, v) -> s.temperatureColor = v);
        colorChannel("humidity-color", s -> s.humidityColor, (s, v) -> s.humidityColor = v);
        colorChannel("battery-color", s -> s.batteryColor, (s, v) -> s.batteryColor = v);
        colorChannel("calendar-header-color", s -> s.calendarHeaderColor, (s, v) -> s.calendarHeaderColor = v);
        colorChannel("calendar-text-color", s -> s.calendarTextColor, (s, v) -> s.calendarTextColor = v);
        colorChannel("calendar-body-color", s -> s.calendarBodyColor, (s, v) -> s.calendarBodyColor = v);

        // Weekday bar of the clock app
        switchChannel("weekday-bar-show", s -> s.weekdayBar == null ? null : s.weekdayBar.show,
                (s, v) -> weekdayBar(s).show = v);
        switchChannel("weekday-bar-start-on-monday", s -> s.weekdayBar == null ? null : s.weekdayBar.startOnMonday,
                (s, v) -> weekdayBar(s).startOnMonday = v);
        colorChannel("weekday-bar-active-color", s -> s.weekdayBar == null ? null : s.weekdayBar.activeColor,
                (s, v) -> weekdayBar(s).activeColor = v);
        colorChannel("weekday-bar-inactive-color", s -> s.weekdayBar == null ? null : s.weekdayBar.inactiveColor,
                (s, v) -> weekdayBar(s).inactiveColor = v);
    }

    private SettingsChannels() {
    }

    /**
     * @return the mapping for the channel, or null if the channel is not a plain settings field
     */
    public static @Nullable SettingsChannel get(String channelId) {
        return CHANNELS.get(channelId);
    }

    public static Iterable<Map.Entry<String, SettingsChannel>> all() {
        return CHANNELS.entrySet();
    }

    private static WeekdayBar weekdayBar(Settings settings) {
        WeekdayBar bar = settings.weekdayBar;
        if (bar == null) {
            bar = new WeekdayBar();
            settings.weekdayBar = bar;
        }
        return bar;
    }

    private static void switchChannel(String id, Function<Settings, @Nullable Boolean> get,
            BiConsumer<Settings, Boolean> set) {
        CHANNELS.put(id, new SettingsChannel(s -> {
            Boolean value = get.apply(s);
            return value == null ? null : OnOffType.from(value);
        }, (s, command) -> {
            if (command instanceof OnOffType onOff) {
                set.accept(s, onOff == OnOffType.ON);
                return true;
            }
            return false;
        }));
    }

    private static void numberChannel(String id, Function<Settings, @Nullable Integer> get,
            BiConsumer<Settings, Integer> set) {
        CHANNELS.put(id, new SettingsChannel(s -> {
            Integer value = get.apply(s);
            return value == null ? null : new DecimalType(value);
        }, (s, command) -> {
            if (command instanceof Number number) {
                set.accept(s, number.intValue());
                return true;
            }
            return false;
        }));
    }

    private static void percentChannel(String id, Function<Settings, @Nullable Integer> get,
            BiConsumer<Settings, Integer> set) {
        CHANNELS.put(id, new SettingsChannel(s -> {
            Integer value = get.apply(s);
            return value == null ? null : new QuantityType<>(value, Units.PERCENT);
        }, (s, command) -> {
            Double percent = Helper.toPercent(command);
            if (percent != null) {
                set.accept(s, (int) Math.round(percent));
                return true;
            }
            return false;
        }));
    }

    /** A duration the clock keeps in milliseconds; a plain number is taken as milliseconds. */
    private static void durationChannel(String id, Function<Settings, @Nullable Integer> get,
            BiConsumer<Settings, Integer> set) {
        CHANNELS.put(id, new SettingsChannel(s -> {
            Integer millis = get.apply(s);
            return millis == null ? null : new QuantityType<>(millis, MetricPrefix.MILLI(Units.SECOND));
        }, (s, command) -> {
            Double millis = Helper.toMillis(command);
            if (millis != null) {
                set.accept(s, (int) Math.round(millis));
                return true;
            }
            return false;
        }));
    }

    private static void textChannel(String id, Function<Settings, @Nullable String> get,
            BiConsumer<Settings, String> set) {
        CHANNELS.put(id, new SettingsChannel(s -> {
            String value = get.apply(s);
            return value == null ? null : new StringType(value);
        }, (s, command) -> {
            if (command instanceof StringType) {
                set.accept(s, command.toString());
                return true;
            }
            return false;
        }));
    }

    private static void colorChannel(String id, Function<Settings, @Nullable String> get,
            BiConsumer<Settings, String> set) {
        CHANNELS.put(id, new SettingsChannel(s -> Helper.fromHex(get.apply(s)), (s, command) -> {
            if (command instanceof HSBType color) {
                set.accept(s, Helper.toHex(color));
                return true;
            }
            return false;
        }));
    }
}
