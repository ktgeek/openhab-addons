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
package org.openhab.binding.mqtt.awtrixlight.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ThingUID;

/**
 * Test cases for the {@link FirmwareSwitchGuard}.
 *
 * @author Keith T. Garner - Initial contribution
 */
class FirmwareSwitchGuardTest {

    private static final ThingUID CLOCK = new ThingUID("mqtt", "awtrix-clock", "c");

    private static class SettableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final SettableClock clock = new SettableClock();
    private final FirmwareSwitchGuard guard = new FirmwareSwitchGuard(clock);

    @Test
    void firstSwitchIsAllowed() {
        assertTrue(guard.tryRecordSwitch(CLOCK));
    }

    @Test
    void secondSwitchRightAfterIsRefused() {
        assertTrue(guard.tryRecordSwitch(CLOCK));

        clock.advance(FirmwareSwitchGuard.HOLD_OFF.minusSeconds(1));

        assertFalse(guard.tryRecordSwitch(CLOCK));
    }

    @Test
    void switchIsAllowedAgainAfterTheHoldOff() {
        assertTrue(guard.tryRecordSwitch(CLOCK));

        clock.advance(FirmwareSwitchGuard.HOLD_OFF);

        assertTrue(guard.tryRecordSwitch(CLOCK));
    }

    @Test
    void refusedSwitchDoesNotExtendTheHoldOff() {
        assertTrue(guard.tryRecordSwitch(CLOCK));
        clock.advance(FirmwareSwitchGuard.HOLD_OFF.minusSeconds(1));
        assertFalse(guard.tryRecordSwitch(CLOCK));

        clock.advance(Duration.ofSeconds(1));

        assertTrue(guard.tryRecordSwitch(CLOCK));
    }

    @Test
    void clocksAreGuardedIndependently() {
        assertTrue(guard.tryRecordSwitch(CLOCK));

        assertTrue(guard.tryRecordSwitch(new ThingUID("mqtt", "awtrix-clock", "other")));
    }
}
